package org.apache.rocketmq.client.impl;

import apache.rocketmq.v1.AckMessageRequest;
import apache.rocketmq.v1.AckMessageResponse;
import apache.rocketmq.v1.Digest;
import apache.rocketmq.v1.HealthCheckRequest;
import apache.rocketmq.v1.HealthCheckResponse;
import apache.rocketmq.v1.HeartbeatRequest;
import apache.rocketmq.v1.HeartbeatResponse;
import apache.rocketmq.v1.Message;
import apache.rocketmq.v1.NackMessageRequest;
import apache.rocketmq.v1.NackMessageResponse;
import apache.rocketmq.v1.Partition;
import apache.rocketmq.v1.QueryAssignmentRequest;
import apache.rocketmq.v1.QueryAssignmentResponse;
import apache.rocketmq.v1.QueryRouteRequest;
import apache.rocketmq.v1.QueryRouteResponse;
import apache.rocketmq.v1.ReceiveMessageRequest;
import apache.rocketmq.v1.ReceiveMessageResponse;
import apache.rocketmq.v1.Resource;
import apache.rocketmq.v1.ResponseCommon;
import apache.rocketmq.v1.SendMessageRequest;
import apache.rocketmq.v1.SendMessageResponse;
import apache.rocketmq.v1.SystemAttribute;
import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.constant.CommunicationMode;
import org.apache.rocketmq.client.constant.ServiceState;
import org.apache.rocketmq.client.consumer.PopResult;
import org.apache.rocketmq.client.consumer.PopStatus;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.exception.MQServerException;
import org.apache.rocketmq.client.impl.consumer.ConsumerObserver;
import org.apache.rocketmq.client.impl.consumer.TopicAssignmentInfo;
import org.apache.rocketmq.client.impl.producer.ProducerObserver;
import org.apache.rocketmq.client.message.MessageExt;
import org.apache.rocketmq.client.message.MessageImpl;
import org.apache.rocketmq.client.message.protocol.MessageType;
import org.apache.rocketmq.client.message.protocol.TransactionPhase;
import org.apache.rocketmq.client.misc.MixAll;
import org.apache.rocketmq.client.misc.TopAddressing;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendMessageResponseCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.remoting.Endpoints;
import org.apache.rocketmq.client.remoting.HeadersClientInterceptor;
import org.apache.rocketmq.client.remoting.IpNameResolverFactory;
import org.apache.rocketmq.client.remoting.RpcClient;
import org.apache.rocketmq.client.remoting.RpcClientImpl;
import org.apache.rocketmq.client.remoting.RpcTarget;
import org.apache.rocketmq.client.route.TopicRouteData;
import org.apache.rocketmq.client.tracing.SpanName;
import org.apache.rocketmq.client.tracing.TracingAttribute;
import org.apache.rocketmq.client.tracing.TracingUtility;
import org.apache.rocketmq.utility.ThreadFactoryImpl;
import org.apache.rocketmq.utility.UtilAll;

@Slf4j
public class ClientInstance {
    private static final long RPC_DEFAULT_TIMEOUT_MILLIS = 3 * 1000;

    private final ClientInstanceConfig clientInstanceConfig;
    @Setter
    private String tenantId = "";

    private final ConcurrentMap<RpcTarget, RpcClient> clientTable;

    @Getter
    private final ScheduledExecutorService scheduler;
    /**
     * Public executor for all async rpc, <strong>should never submit heavy task.</strong>
     */
    private final ThreadPoolExecutor asyncRpcExecutor;
    @Setter
    private ThreadPoolExecutor sendCallbackExecutor;

    private final ThreadPoolExecutor receiveCallbackExecutor;

    private Endpoints nameServerEndpoints = null;

    private final TopAddressing topAddressing;

    private final ConcurrentMap<String, ProducerObserver> producerObserverTable;
    private final ConcurrentMap<String, ConsumerObserver> consumerObserverTable;

    private final ConcurrentHashMap<String /* Topic */, TopicRouteData> topicRouteTable;

    private final AtomicReference<ServiceState> state;

    private volatile RpcTarget tracingRpcTarget = null;
    private volatile Tracer tracer = null;

    public ClientInstance(ClientInstanceConfig clientInstanceConfig, Endpoints nameServerEndpointsList) {
        this.clientInstanceConfig = clientInstanceConfig;
        this.clientTable = new ConcurrentHashMap<RpcTarget, RpcClient>();

        this.scheduler =
                new ScheduledThreadPoolExecutor(4, new ThreadFactoryImpl("ClientInstanceScheduler_"));

        this.asyncRpcExecutor = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
                                                       Runtime.getRuntime().availableProcessors(),
                                                       60,
                                                       TimeUnit.SECONDS,
                                                       new LinkedBlockingQueue<Runnable>(),
                                                       new ThreadFactoryImpl("AsyncRpcThread_"));

        this.sendCallbackExecutor = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
                                                           Runtime.getRuntime().availableProcessors(),
                                                           60,
                                                           TimeUnit.SECONDS,
                                                           new LinkedBlockingQueue<Runnable>(),
                                                           new ThreadFactoryImpl("SendCallbackThread_"));

        this.receiveCallbackExecutor = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors(),
                                                              Runtime.getRuntime().availableProcessors(),
                                                              60,
                                                              TimeUnit.SECONDS,
                                                              new LinkedBlockingQueue<Runnable>(),
                                                              new ThreadFactoryImpl("ReceiveCallbackThread_"));

        this.nameServerEndpoints = nameServerEndpointsList;

        this.topAddressing = new TopAddressing();

        this.producerObserverTable = new ConcurrentHashMap<String, ProducerObserver>();
        this.consumerObserverTable = new ConcurrentHashMap<String, ConsumerObserver>();

        this.topicRouteTable = new ConcurrentHashMap<String, TopicRouteData>();

        this.state = new AtomicReference<ServiceState>(ServiceState.CREATED);
    }

    private void updateNameServerEndpointsFromTopAddressing() throws IOException {
        this.nameServerEndpoints = topAddressing.fetchNameServerAddresses();
    }

    /**
     * Start the instance.
     *
     * @throws MQClientException
     */
    public synchronized void start() throws MQClientException {
        if (ServiceState.STARTED == state.get()) {
            log.info("Client instance has been started before");
            return;
        }
        log.info("Begin to start the client instance.");
        if (!state.compareAndSet(ServiceState.CREATED, ServiceState.STARTING)) {
            throw new MQClientException(
                    "The client instance has attempted to be stared before, state=" + state.get());
        }

        // Only for internal usage of Alibaba group.
        if (null == nameServerEndpoints) {
            try {
                updateNameServerEndpointsFromTopAddressing();
            } catch (Throwable t) {
                throw new MQClientException(
                        "Failed to fetch name server list from top address while starting.", t);
            }
            scheduler.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                updateNameServerEndpointsFromTopAddressing();
                            } catch (Throwable t) {
                                log.error(
                                        "Exception occurs while updating name server list from top addressing", t);
                            }
                        }
                    },
                    3 * 1000,
                    30 * 1000,
                    TimeUnit.MILLISECONDS);
        }

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            updateRouteInfo();
                        } catch (Throwable t) {
                            log.error("Exception occurs while updating route info.", t);
                        }
                    }
                },
                10 * 1000,
                60 * 1000,
                TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            scanConsumersLoadAssignments();
                        } catch (Throwable t) {
                            log.error("Exception occurs while scanning load assignments of consumers.", t);
                        }
                    }
                },
                1000,
                5 * 1000,
                TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            restoreIsolatedTarget();
                        } catch (Throwable t) {
                            log.error("Exception occurs while restoring isolated target.", t);
                        }
                    }
                },
                5 * 1000,
                15 * 1000,
                TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            cleanOutdatedClient();
                        } catch (Throwable t) {
                            log.error("Exception occurs while cleaning outdated client.", t);
                        }
                    }
                },
                30 * 1000,
                60 * 1000,
                TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            doHeartbeat();
                        } catch (Throwable t) {
                            log.error("Exception occurs while heartbeat.", t);
                        }
                    }
                },
                0,
                30 * 1000,
                TimeUnit.MILLISECONDS);

        scheduler.scheduleWithFixedDelay(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            logStats();
                        } catch (Throwable t) {
                            log.error("Exception occurs while logging stats.", t);
                        }
                    }
                },
                1000,
                1000,
                TimeUnit.MILLISECONDS);

        state.compareAndSet(ServiceState.STARTING, ServiceState.STARTED);
        log.info("Start the client instance successfully.");
    }

    public synchronized void shutdown() throws MQClientException {
        log.info("Begin to start client instance.");
        if (ServiceState.STOPPED == state.get()) {
            return;
        }
        if (!producerObserverTable.isEmpty()) {
            log.info(
                    "Not all producerObserver has been unregistered, producerObserver num={}",
                    producerObserverTable.size());
            return;
        }
        if (!consumerObserverTable.isEmpty()) {
            log.info(
                    "Not all consumerObserver has been unregistered, consumerObserver num={}",
                    consumerObserverTable.size());
            return;
        }
        state.compareAndSet(ServiceState.STARTING, ServiceState.STOPPING);
        state.compareAndSet(ServiceState.STARTED, ServiceState.STOPPING);
        final ServiceState serviceState = state.get();
        if (ServiceState.STOPPING == serviceState) {
            scheduler.shutdown();
            asyncRpcExecutor.shutdown();
            if (state.compareAndSet(ServiceState.STOPPING, ServiceState.STOPPED)) {
                log.info("Shutdown client instance successfully");
                return;
            }
        }
        throw new MQClientException("Failed to shutdown consumer, state=" + state.get());
    }

    private void logStats() {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }
        for (ProducerObserver producerObserver : producerObserverTable.values()) {
            producerObserver.logStats();
        }
        for (ConsumerObserver consumerObserver : consumerObserverTable.values()) {
            consumerObserver.logStats();
        }
    }

    private void doHeartbeat() {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }

        log.debug("Start to send heartbeat for a new round.");

        final HeartbeatRequest.Builder builder = HeartbeatRequest.newBuilder();

        for (ProducerObserver producerObserver : producerObserverTable.values()) {
            builder.addHeartbeats(producerObserver.prepareHeartbeatData());
        }

        for (ConsumerObserver consumerObserver : consumerObserverTable.values()) {
            builder.addHeartbeats(consumerObserver.prepareHeartbeatData());
        }

        final HeartbeatRequest request = builder.build();

        Set<RpcTarget> filteredTarget = new HashSet<RpcTarget>();
        for (RpcTarget rpcTarget : clientTable.keySet()) {
            if (!rpcTarget.isNeedHeartbeat()) {
                return;
            }
            filteredTarget.add(rpcTarget);
        }

        for (RpcTarget rpcTarget : filteredTarget) {
            final RpcClient rpcClient = clientTable.get(rpcTarget);
            if (null == rpcClient) {
                continue;
            }
            final HeartbeatResponse response =
                    rpcClient.heartbeat(request, RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            final Status status = response.getCommon().getStatus();
            final Code code = Code.forNumber(status.getCode());
            final Endpoints endpoints = rpcTarget.getEndpoints();
            if (Code.OK != code) {
                log.warn("Failed to send heartbeat, responseCode={}, endpoints={}", code, endpoints);
                continue;
            }
            log.debug("Send heartbeat successfully, endpoints={}", endpoints);
        }
    }

    private void restoreIsolatedTarget() {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }

        for (Map.Entry<RpcTarget, RpcClient> entry : clientTable.entrySet()) {
            final RpcTarget rpcTarget = entry.getKey();
            final RpcClient rpcClient = entry.getValue();
            if (!rpcTarget.isIsolated()) {
                continue;
            }
            final String target = rpcTarget.getEndpoints().getTarget();
            final HealthCheckRequest request =
                    HealthCheckRequest.newBuilder().setClientHost(target).build();
            final HealthCheckResponse response =
                    rpcClient.healthCheck(request, RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            final Status status = response.getCommon().getStatus();
            final Code code = Code.forNumber(status.getCode());
            if (Code.OK != code) {
                rpcTarget.setIsolated(false);
                log.info("Isolated target={} has been restored", target);
                continue;
            }
            log.debug("Isolated target={} was not restored", target);
        }
    }

    private void cleanOutdatedClient() {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }

        Set<Endpoints> currentEndpointsSet = new HashSet<Endpoints>();
        if (null != nameServerEndpoints) {
            currentEndpointsSet.add(nameServerEndpoints);
        }

        for (TopicRouteData topicRouteData : topicRouteTable.values()) {
            final Set<Endpoints> endpoints = topicRouteData.getAllEndpoints();
            currentEndpointsSet.addAll(endpoints);
        }

        for (RpcTarget rpcTarget : clientTable.keySet()) {
            if (!currentEndpointsSet.contains(rpcTarget.getEndpoints())) {
                clientTable.remove(rpcTarget);
            }
        }
    }

    /**
     * Update topic route info from name server and notify observer if changed.
     */
    private void updateRouteInfo() throws SSLException {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }
        final Set<String> topics = new HashSet<String>(topicRouteTable.keySet());
        if (topics.isEmpty()) {
            return;
        }
        for (String topic : topics) {
            boolean updated = false;
            TopicRouteData after;

            try {
                after = fetchTopicRouteData(topic);
            } catch (Throwable t) {
                log.warn("Failed to fetch topic route from name server, topic={}", topic);
                continue;
            }

            final TopicRouteData before = topicRouteTable.get(topic);
            if (!after.equals(before)) {
                topicRouteTable.put(topic, after);
                updated = true;
            }

            if (updated) {
                log.info("Topic route updated, topic={}, before={}, after={}", topic, before, after);
            } else {
                log.debug("Topic route remains unchanged, topic={}", topic);
            }

            if (updated) {
                for (ProducerObserver producerObserver : producerObserverTable.values()) {
                    producerObserver.onTopicRouteChanged(topic, after);
                }
            }
        }
        updateTracer();
    }

    /**
     * Fetch all available tracing rpc target
     *
     * @return set of all available tracing rpc target
     */
    private Set<RpcTarget> getTracingRpcTargetSet() {
        Set<RpcTarget> tracingRpcTargetSet = new HashSet<RpcTarget>();
        for (TopicRouteData topicRouteData : topicRouteTable.values()) {
            final List<org.apache.rocketmq.client.route.Partition> partitions = topicRouteData.getPartitions();
            for (org.apache.rocketmq.client.route.Partition partition : partitions) {
                if (MixAll.MASTER_BROKER_ID != partition.getBrokerId()) {
                    continue;
                }
                tracingRpcTargetSet.add(partition.getRpcTarget());
            }
        }
        return tracingRpcTargetSet;
    }

    private void updateTracer() throws SSLException {
        final Set<RpcTarget> tracingRpcTargetSet = getTracingRpcTargetSet();
        if (tracingRpcTargetSet.isEmpty()) {
            log.info("No available tracing rpc target.");
            return;
        }
        if (null != tracingRpcTarget && tracingRpcTargetSet.contains(tracingRpcTarget)) {
            log.info("Tracing rpc target remains unchanged");
            return;
        }
        List<RpcTarget> tracingRpcTargetList = new ArrayList<RpcTarget>(tracingRpcTargetSet);
        Collections.shuffle(tracingRpcTargetList);
        // Pick up tracing rpc target randomly.
        final RpcTarget randomTracingRpcTarget = tracingRpcTargetList.iterator().next();
        final SslContext sslContext =
                GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();

        final NettyChannelBuilder channelBuilder =
                NettyChannelBuilder
                        .forTarget(randomTracingRpcTarget.getEndpoints().getTarget())
                        .sslContext(sslContext)
                        .intercept(new HeadersClientInterceptor(clientInstanceConfig));

        final List<InetSocketAddress> socketAddresses =
                randomTracingRpcTarget.getEndpoints().convertToSocketAddresses();
        // If scheme is not domain.
        if (null != socketAddresses) {
            IpNameResolverFactory tracingResolverFactory = new IpNameResolverFactory(socketAddresses);
            channelBuilder.nameResolverFactory(tracingResolverFactory);
        }

        OtlpGrpcSpanExporter exporter =
                OtlpGrpcSpanExporter.builder().setChannel(channelBuilder.build())
                                    .setTimeout(RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build();
        BatchSpanProcessor spanProcessor =
                BatchSpanProcessor.builder(exporter)
                                  .setScheduleDelay(1, TimeUnit.SECONDS)
                                  .setMaxExportBatchSize(4096)
                                  .build();

        SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build();
        // TODO: no need propagators here.
        OpenTelemetrySdk openTelemetry =
                OpenTelemetrySdk.builder()
                                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                                .setTracerProvider(sdkTracerProvider).build();
        tracer = openTelemetry.getTracer("org.apache.rocketmq.message.tracer");
        tracingRpcTarget = randomTracingRpcTarget;
    }

    /**
     * Scan load assignments for all consumers.
     */
    private void scanConsumersLoadAssignments() {
        final ServiceState serviceState = state.get();
        if (ServiceState.STARTED != serviceState && ServiceState.STARTING != serviceState) {
            log.warn("Unexpected client instance state={}", serviceState);
            return;
        }
        for (ConsumerObserver consumerObserver : consumerObserverTable.values()) {
            consumerObserver.scanLoadAssignments();
        }
    }

    /**
     * Register producer observer.
     *
     * @param producerGroup group of producer, caller must ensure that it is not blank.
     * @param observer      producer observer.
     * @return result of register.
     */
    public boolean registerProducerObserver(String producerGroup, ProducerObserver observer) {
        final ProducerObserver prev = producerObserverTable.putIfAbsent(producerGroup, observer);
        if (null != prev) {
            log.warn("The producer group exists already, producerGroup={}", producerGroup);
            return false;
        }
        return true;
    }

    /**
     * Unregister producer observer.
     *
     * @param producerGroup the producer group
     */
    public void unregisterProducerObserver(String producerGroup) {
        producerObserverTable.remove(producerGroup);
    }

    /**
     * Register consumer observer.
     *
     * @param consumerGroup group of consumer, caller must ensure that it is not blank.
     * @param observer      consumer observer.
     * @return result of register.
     */
    public boolean registerConsumerObserver(String consumerGroup, ConsumerObserver observer) {
        final ConsumerObserver prev = consumerObserverTable.putIfAbsent(consumerGroup, observer);
        if (null != prev) {
            log.warn("The consumer group exists already, producerGroup={}", consumerGroup);
            return false;
        }
        return true;
    }

    /**
     * Unregister consumer observer.
     *
     * @param consumerGroup the consumer group
     */
    public void unregisterConsumerObserver(String consumerGroup) {
        consumerObserverTable.remove(consumerGroup);
    }


    /**
     * Get rpc client by remote address, would create client automatically if it does not exist.
     *
     * @param target remote address.
     * @return rpc client.
     */
    private RpcClient getRpcClient(RpcTarget target) throws MQClientException {
        RpcClient rpcClient = clientTable.get(target);
        if (null != rpcClient) {
            return rpcClient;
        }
        RpcClientImpl newRpcClient;
        try {
            newRpcClient = new RpcClientImpl(target);
        } catch (SSLException e) {
            log.error("Failed to get rpc client, endpoints={}", target.getEndpoints());
            throw new MQClientException("Failed to get rpc client");
        }
        newRpcClient.setArn(clientInstanceConfig.getArn());
        newRpcClient.setTenantId(tenantId);
        newRpcClient.setAccessCredential(clientInstanceConfig.getAccessCredential());
        clientTable.put(target, newRpcClient);

        return newRpcClient;
    }


    public Set<RpcTarget> getIsolatedTargets() {
        Set<RpcTarget> targetSet = new HashSet<RpcTarget>();
        for (RpcTarget rpcTarget : clientTable.keySet()) {
            if (!rpcTarget.isIsolated()) {
                continue;
            }
            targetSet.add(rpcTarget);
        }
        return targetSet;
    }

    SendMessageResponse send(
            RpcTarget target, SendMessageRequest request, long duration, TimeUnit unit) throws MQClientException {
        RpcClient rpcClient = this.getRpcClient(target);

        Span span = null;
        if (null != tracer) {
            span = tracer.spanBuilder(SpanName.SEND_MESSAGE_SYNC).startSpan();
            final Message message = request.getMessage();
            final SystemAttribute systemAttribute = message.getSystemAttribute();

            span.setAttribute(TracingAttribute.ARN, message.getTopic().getArn());
            span.setAttribute(TracingAttribute.TOPIC, message.getTopic().getName());
            span.setAttribute(TracingAttribute.TAGS, systemAttribute.getTag());
            span.setAttribute(TracingAttribute.MSG_ID, systemAttribute.getMessageId());

            final String serializedSpanContext = TracingUtility.injectSpanContextToTraceParent(span.getSpanContext());
            systemAttribute.toBuilder().setTraceContext(serializedSpanContext);
        }
        SendMessageResponse response = null;
        try {
            response = rpcClient.sendMessage(request, duration, unit);
            return response;
        } finally {
            // Ensure span MUST be ended.
            if (null != span) {
                span.setAttribute(TracingAttribute.SUCCESS,
                                  null != response
                                  && Code.OK == Code.forNumber(response.getCommon().getStatus().getCode()));
                span.end();
            }
        }
    }

    void sendAsync(
            RpcTarget target,
            SendMessageRequest request,
            final SendCallback sendCallback,
            long duration,
            TimeUnit unit) {
        final SendMessageResponseCallback callback =
                new SendMessageResponseCallback(request, state, sendCallback);
        try {
            final ListenableFuture<SendMessageResponse> future =
                    getRpcClient(target).sendMessage(request, asyncRpcExecutor, duration, unit);
            Futures.addCallback(
                    future,
                    new FutureCallback<SendMessageResponse>() {
                        @Override
                        public void onSuccess(@Nullable final SendMessageResponse response) {
                            try {
                                callback.onReceiveResponse(response);
                            } catch (Throwable t) {
                                log.error("Failed to handle async-send message response.", t);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t1) {
                            try {
                                callback.onException(t1);
                            } catch (Throwable t2) {
                                log.error("Failed to handle async-send message throwable.", t2);
                            }
                        }
                    }, sendCallbackExecutor);

        } catch (final Throwable t) {
            try {
                sendCallbackExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.onException(t);
                        } catch (Throwable t1) {
                            log.error("Failed to handle async-send message throwable.", t1);
                        }
                    }
                });
            } catch (Throwable t2) {
                log.error("Exception occurs while handling async-send message throwable.", t2);
            }
        }
    }

    public SendMessageResponse sendClientApi(
            RpcTarget target,
            CommunicationMode mode,
            SendMessageRequest request,
            SendCallback sendCallback,
            long duration,
            TimeUnit unit) throws MQClientException {
        switch (mode) {
            case SYNC:
            case ONE_WAY:
                return send(target, request, duration, unit);
            case ASYNC:
            default:
                sendAsync(target, request, sendCallback, duration, unit);
                ResponseCommon common =
                        ResponseCommon.newBuilder().setStatus(Status.newBuilder().setCode(Code.OK_VALUE)).build();
                return SendMessageResponse.newBuilder().setCommon(common).build();
        }
    }

    public ListenableFuture<PopResult> receiveMessageAsync(
            final RpcTarget target, final ReceiveMessageRequest request, long duration, TimeUnit unit)
            throws MQClientException {
        final ListenableFuture<ReceiveMessageResponse> future =
                getRpcClient(target).receiveMessage(request, asyncRpcExecutor, duration, unit);
        return Futures.transform(
                future,
                new Function<ReceiveMessageResponse, PopResult>() {
                    @Override
                    public PopResult apply(ReceiveMessageResponse response) {
                        return processReceiveMessageResponse(target, response);
                    }
                }, receiveCallbackExecutor);
    }

    public void ackMessage(final RpcTarget target, final AckMessageRequest request)
            throws MQClientException {
        final AckMessageResponse response =
                getRpcClient(target).ackMessage(request, RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        if (Code.OK != code) {
            log.error("Failed to ack message, endpoints={}, status={}.", target.getEndpoints(), status);
            throw new MQClientException("Failed to ack message.");
        }
    }

    public void ackMessageAsync(final RpcTarget target, final AckMessageRequest request) throws MQClientException {
        final ListenableFuture<AckMessageResponse> future =
                getRpcClient(target).ackMessage(request, asyncRpcExecutor, RPC_DEFAULT_TIMEOUT_MILLIS,
                                                TimeUnit.MILLISECONDS);
        Futures.addCallback(
                future,
                new FutureCallback<AckMessageResponse>() {
                    @Override
                    public void onSuccess(AckMessageResponse result) {
                        final Status status = result.getCommon().getStatus();
                        final Code code = Code.forNumber(status.getCode());
                        if (Code.OK != code) {
                            log.error("Failed to async-ack message, endpoints={}, status={}", target.getEndpoints(),
                                      status);
                        }

                    }

                    @Override
                    public void onFailure(Throwable t) {
                        log.warn("Failed to async-ack message, endpoints={}", target.getEndpoints(), t);
                    }
                });
    }

    public void nackMessage(final RpcTarget target, final NackMessageRequest request)
            throws MQClientException {
        final NackMessageResponse response =
                getRpcClient(target)
                        .nackMessage(request, RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        final Status status = response.getCommon().getStatus();
        final int code = status.getCode();
        if (Code.OK_VALUE != code) {
            log.error("Failed to nack message, target={}, status={}.", target, status);
            throw new MQClientException("Failed to nack message.");
        }
    }

    private QueryRouteResponse queryRoute(QueryRouteRequest request) throws MQClientException {
        if (null == nameServerEndpoints) {
            log.error("No name server endpoints found, topic={}", request.getTopic());
            throw new MQClientException("No name server endpoints found.");
        }
        final RpcClient rpcClient = this.getRpcClient(new RpcTarget(nameServerEndpoints, true, false));
        return rpcClient.queryRoute(request, RPC_DEFAULT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    public TopicAssignmentInfo queryLoadAssignment(RpcTarget target, QueryAssignmentRequest request)
            throws MQServerException, MQClientException {
        final RpcClient rpcClient = this.getRpcClient(target);
        QueryAssignmentResponse response = rpcClient.queryAssignment(request, RPC_DEFAULT_TIMEOUT_MILLIS,
                                                                     TimeUnit.MILLISECONDS);
        final Status status = response.getCommon().getStatus();
        final int code = status.getCode();
        if (Code.OK_VALUE != code) {
            throw new MQServerException("Failed to query load assignment from remote");
        }
        return new TopicAssignmentInfo(response.getLoadAssignmentsList());
    }

    /**
     * Get topic route info from remote,
     *
     * @param topic the requested topic.
     * @return topic route into.
     * @throws MQClientException throw exception when failed to fetch topic route info from remote.
     *                           e.g. topic does not exist.
     */
    private TopicRouteData fetchTopicRouteData(String topic) throws MQClientException {
        Resource topicResource = Resource.newBuilder().setArn(clientInstanceConfig.getArn()).setName(topic).build();
        if (null == nameServerEndpoints) {
            log.error("No name server endpoints found, topic={}", topic);
            throw new MQClientException("No name server endpoints found");
        }
        final QueryRouteRequest request =
                QueryRouteRequest.newBuilder().setTopic(topicResource).build();
        final QueryRouteResponse response = queryRoute(request);
        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        if (Code.OK != code) {
            log.error(
                    "Failed to fetch topic route, topic={}, responseCode={}, name server endpoints={}",
                    topic, code, nameServerEndpoints);
            throw new MQClientException("Failed to fetch topic route");
        }
        final List<Partition> partitionsList = response.getPartitionsList();
        if (partitionsList.isEmpty()) {
            log.error(
                    "Topic route is empty unexpectedly , topic={}, name server endpoints={}",
                    topic, nameServerEndpoints);
            throw new MQClientException("Topic does not exist.");
        }
        return new TopicRouteData(partitionsList);
    }

    /**
     * Get topic route info, would fetch topic route info from remote only when it does not exist in
     * local cache.
     *
     * @param topic the requested topic.
     * @return topic route info.
     * @throws MQClientException throw exception when failed to fetch topic route info from remote.
     *                           e.g. topic does not exist.
     */
    public TopicRouteData getTopicRouteInfo(String topic) throws MQClientException {
        TopicRouteData topicRouteData = topicRouteTable.get(topic);
        if (null != topicRouteData) {
            return topicRouteData;
        }
        topicRouteData = fetchTopicRouteData(topic);
        topicRouteTable.put(topic, topicRouteData);
        return topicRouteTable.get(topic);
    }

    public static SendResult processSendResponse(SendMessageResponse response)
            throws MQServerException {
        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        if (null == code) {
            throw new MQServerException("Unrecognized code=" + status.getCode());
        }
        if (Code.OK == code) {
            return new SendResult(response.getMessageId());
        }
        log.debug("Response indicates failure of sending message, information={}", status.getMessage());
        throw new MQServerException(status.getMessage());
    }

    // TODO: handle the case that the topic does not exist.
    public static PopResult processReceiveMessageResponse(RpcTarget target, ReceiveMessageResponse response) {
        PopStatus popStatus;

        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        switch (code != null ? code : Code.UNKNOWN) {
            case OK:
                popStatus = PopStatus.FOUND;
                break;
            case RESOURCE_EXHAUSTED:
                popStatus = PopStatus.POLLING_FULL;
                log.warn("Too may pop request in broker, brokerAddress={}", target);
                break;
            case DEADLINE_EXCEEDED:
                popStatus = PopStatus.NO_NEW_MSG;
                break;
            case NOT_FOUND:
                popStatus = PopStatus.POLLING_NOT_FOUND;
                break;
            default:
                popStatus = PopStatus.SERVICE_UNSTABLE;
                log.warn(
                        "Pop response indicated server-side error, brokerAddress={}, code={}, status message={}",
                        target, code, status.getMessage());
        }

        List<MessageExt> msgFoundList = new ArrayList<MessageExt>();
        if (PopStatus.FOUND == popStatus) {
            final List<Message> msgList = response.getMessagesList();
            for (Message msg : msgList) {
                try {
                    MessageImpl impl = new MessageImpl(msg.getTopic().getName());
                    final SystemAttribute systemAttribute = msg.getSystemAttribute();
                    // Target
                    impl.getSystemAttribute().setAckRpcTarget(target);
                    // Tag
                    impl.getSystemAttribute().setTag(systemAttribute.getTag());
                    // Key
                    List<String> keys = new ArrayList<String>(systemAttribute.getKeysList());
                    impl.getSystemAttribute().setKeys(keys);
                    // Message Id
                    impl.getSystemAttribute().setMessageId(systemAttribute.getMessageId());
                    // Check digest.
                    final Digest bodyDigest = systemAttribute.getBodyDigest();
                    byte[] body = msg.getBody().toByteArray();
                    boolean bodyDigestMatch = false;
                    String expectedCheckSum;
                    switch (bodyDigest.getType()) {
                        case CRC32:
                            expectedCheckSum = UtilAll.getCrc32CheckSum(body);
                            if (expectedCheckSum.equals(bodyDigest.getChecksum())) {
                                bodyDigestMatch = true;
                            }
                            break;
                        case MD5:
                            try {
                                expectedCheckSum = UtilAll.getMd5CheckSum(body);
                                if (expectedCheckSum.equals(bodyDigest.getChecksum())) {
                                    bodyDigestMatch = true;
                                }
                            } catch (NoSuchAlgorithmException e) {
                                bodyDigestMatch = true;
                                log.warn("MD5 is not supported unexpectedly, skip it.");
                            }
                            break;
                        case SHA1:
                            try {
                                expectedCheckSum = UtilAll.getSha1CheckSum(body);
                                if (expectedCheckSum.equals(bodyDigest.getChecksum())) {
                                    bodyDigestMatch = true;
                                }
                            } catch (NoSuchAlgorithmException e) {
                                bodyDigestMatch = true;
                                log.warn("SHA-1 is not supported unexpectedly, skip it.");
                            }
                            break;
                        default:
                            log.warn("Unsupported message body digest algorithm.");
                    }
                    if (!bodyDigestMatch) {
                        log.warn("Message body checksum failed.");
                        // Need NACK immediately ?
                        continue;
                    }

                    switch (systemAttribute.getBodyEncoding()) {
                        case GZIP:
                            body = UtilAll.uncompressBytesGzip(body);
                            break;
                        case SNAPPY:
                            log.warn("SNAPPY encoding algorithm is not supported.");
                            break;
                        case IDENTITY:
                            break;
                        default:
                            log.warn("Unsupported message encoding algorithm.");
                    }
                    // Body
                    impl.setBody(body);

                    MessageType messageType;
                    switch (systemAttribute.getMessageType()) {
                        case NORMAL:
                            messageType = MessageType.NORMAL;
                            break;
                        case FIFO:
                            messageType = MessageType.FIFO;
                            break;
                        case DELAY:
                            messageType = MessageType.DELAY;
                            break;
                        case TRANSACTION:
                            messageType = MessageType.TRANSACTION;
                            break;
                        default:
                            messageType = MessageType.NORMAL;
                            log.warn("Unknown message type, fall through to normal type");
                    }
                    // MessageType
                    impl.getSystemAttribute().setMessageType(messageType);

                    TransactionPhase transactionPhase;
                    switch (systemAttribute.getTransactionPhase()) {
                        case NOT_APPLICABLE:
                            transactionPhase = TransactionPhase.NOT_APPLICABLE;
                            break;
                        case PREPARE:
                            transactionPhase = TransactionPhase.PREPARE;
                            break;
                        case COMMIT:
                            transactionPhase = TransactionPhase.COMMIT;
                            break;
                        case ROLLBACK:
                            transactionPhase = TransactionPhase.ROLLBACK;
                            break;
                        default:
                            transactionPhase = TransactionPhase.NOT_APPLICABLE;
                            log.warn("Unknown transaction phase, fall through to N/A");
                    }
                    // TransactionPhase
                    impl.getSystemAttribute().setTransactionPhase(transactionPhase);

                    // BornTimestamp
                    impl.getSystemAttribute().setBornTimestamp(Timestamps.toMillis(systemAttribute.getBornTimestamp()));
                    // BornHost
                    impl.getSystemAttribute().setBornHost(systemAttribute.getBornHost());

                    switch (systemAttribute.getTimedDeliveryCase()) {
                        case DELAY_LEVEL:
                            // DelayLevel
                            impl.getSystemAttribute().setDelayLevel(systemAttribute.getDelayLevel());
                            break;
                        case DELIVERY_TIMESTAMP:
                            // DelayTimestamp
                            impl.getSystemAttribute()
                                .setDeliveryTimestamp(Timestamps.toMillis(systemAttribute.getDeliveryTimestamp()));
                            break;
                        case TIMEDDELIVERY_NOT_SET:
                        default:
                            break;
                    }

                    // DeliveryTimestamp
                    impl.getSystemAttribute()
                        .setDeliveryTimestamp(Timestamps.toMillis(systemAttribute.getDeliveryTimestamp()));
                    // DecodedTimestamp
                    impl.getSystemAttribute().setDecodedTimestamp(System.currentTimeMillis());
                    // BornTimestamp
                    impl.getSystemAttribute().setBornTimestamp(Timestamps.toMillis(systemAttribute.getBornTimestamp()));
                    // ReceiptHandle
                    impl.getSystemAttribute().setReceiptHandle(systemAttribute.getReceiptHandle());
                    // PartitionId
                    impl.getSystemAttribute().setPartitionId(systemAttribute.getPartitionId());
                    // PartitionOffset
                    impl.getSystemAttribute().setPartitionOffset(systemAttribute.getPartitionOffset());
                    // InvisiblePeriod
                    impl.getSystemAttribute()
                        .setInvisiblePeriod(Durations.toMillis(systemAttribute.getInvisiblePeriod()));
                    // DeliveryCount
                    impl.getSystemAttribute().setDeliveryCount(systemAttribute.getDeliveryCount());
                    // TraceContext
                    impl.getSystemAttribute().setTraceContext(systemAttribute.getTraceContext());
                    // UserProperties
                    impl.getUserAttribute().putAll(msg.getUserAttributeMap());

                    MessageExt messageExt = new MessageExt(impl);
                    msgFoundList.add(messageExt);
                } catch (Throwable t) {
                    log.error("Failed to parse messageExt from protocol buffer, msgId={}",
                              msg.getSystemAttribute().getMessageId());
                }
            }
        }

        return new PopResult(
                target,
                popStatus,
                Timestamps.toMillis(response.getDeliveryTimestamp()),
                Durations.toMillis(response.getInvisibleDuration()),
                msgFoundList);
    }
}
