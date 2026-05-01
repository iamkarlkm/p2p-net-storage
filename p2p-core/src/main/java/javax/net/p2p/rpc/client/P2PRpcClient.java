package javax.net.p2p.rpc.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.BoundStreamMessageService;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.api.RpcClient;
import javax.net.p2p.rpc.api.RpcClientStreamHandle;
import javax.net.p2p.rpc.api.RpcClientStreamObserver;
import javax.net.p2p.rpc.api.RpcBidiStreamHandle;
import javax.net.p2p.rpc.api.RpcStreamSubscription;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeItem;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveResponse;
import javax.net.p2p.rpc.model.RpcCallOptions;
import javax.net.p2p.rpc.echo.proto.EchoRequest;
import javax.net.p2p.rpc.echo.proto.EchoResponse;
import javax.net.p2p.rpc.pubsub.proto.PubSubEvent;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishRequest;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishResponse;
import javax.net.p2p.rpc.pubsub.proto.PubSubSubscribeRequest;
import javax.net.p2p.rpc.proto.DiscoverRequest;
import javax.net.p2p.rpc.proto.DiscoverResponse;
import javax.net.p2p.rpc.proto.HealthCheckRequest;
import javax.net.p2p.rpc.proto.HealthCheckResponse;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.server.DfsMapRpcServices;
import javax.net.p2p.rpc.server.RpcBuiltinServices;
import javax.net.p2p.rpc.server.RpcPubSubServices;
import javax.net.p2p.rpc.server.RpcStreamingBuiltinServices;
import javax.net.p2p.rpc.stream.proto.StreamChatRequest;
import javax.net.p2p.rpc.stream.proto.StreamChatResponse;
import javax.net.p2p.rpc.stream.proto.StreamCollectRequest;
import javax.net.p2p.rpc.stream.proto.StreamCollectResponse;

/**
 * 基于现有 P2PMessageService 的最小 RPC 客户端。
 */
public final class P2PRpcClient implements RpcClient {
    private final P2PMessageService messageService;

    public P2PRpcClient(P2PMessageService messageService) {
        if (messageService == null) {
            throw new IllegalArgumentException("messageService 不能为空");
        }
        this.messageService = messageService;
    }

    @Override
    public <Req extends Message, Resp extends Message> Resp unary(
        String service,
        String method,
        Req request,
        Class<Resp> responseType,
        RpcCallOptions options
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildUnaryFrame(service, method, request, callOptions);
        P2PWrapper<byte[]> wrapper = P2PWrapper.build(P2PCommand.RPC_UNARY, frame.toByteArray());
        P2PWrapper<?> response = messageService.excute(wrapper);
        return parseUnaryResponse(response, responseType);
    }

    @Override
    public <Req extends Message, Resp extends Message> CompletableFuture<Resp> unaryAsync(
        String service,
        String method,
        Req request,
        Class<Resp> responseType,
        RpcCallOptions options
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
                RpcFrame frame = buildUnaryFrame(service, method, request, callOptions);
                P2PWrapper<byte[]> wrapper = P2PWrapper.build(P2PCommand.RPC_UNARY, frame.toByteArray());
                Future<P2PWrapper> future = messageService.asyncExcute(wrapper);
                return parseUnaryResponse(future.get(), responseType);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    public HealthCheckResponse health(String service, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildFrame(
            service,
            "Check",
            HealthCheckRequest.newBuilder().setService(service == null ? "" : service).build(),
            callOptions,
            RpcCallType.UNARY
        );
        P2PWrapper<byte[]> response = P2PWrapper.build(P2PCommand.RPC_HEALTH, frame.toByteArray());
        return parseUnaryResponse(messageService.excute(response), HealthCheckResponse.class);
    }

    public DiscoverResponse discover(String service, boolean includeMethods, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildFrame(
            service,
            "Discover",
            DiscoverRequest.newBuilder()
                .setService(service == null ? "" : service)
                .setIncludeMethods(includeMethods)
                .build(),
            callOptions,
            RpcCallType.UNARY
        );
        P2PWrapper<byte[]> response = P2PWrapper.build(P2PCommand.RPC_DISCOVER, frame.toByteArray());
        return parseUnaryResponse(messageService.excute(response), DiscoverResponse.class);
    }

    public EchoResponse echo(String message, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return unary(
            RpcBuiltinServices.ECHO_SERVICE,
            RpcBuiltinServices.ECHO_METHOD,
            EchoRequest.newBuilder().setMessage(message == null ? "" : message).build(),
            EchoResponse.class,
            callOptions
        );
    }

    public DfsMapGetResponse dfsMapGet(long key, long epoch, int apiVersion, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return unary(
            DfsMapRpcServices.SERVICE,
            DfsMapRpcServices.METHOD_GET,
            DfsMapGetRequest.newBuilder()
                .setKey(key)
                .setEpoch(epoch)
                .setApiVersion(apiVersion)
                .build(),
            DfsMapGetResponse.class,
            callOptions
        );
    }

    public DfsMapPutResponse dfsMapPut(
        long key,
        long value,
        long epoch,
        int apiVersion,
        boolean returnOldValue,
        RpcCallOptions options
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return unary(
            DfsMapRpcServices.SERVICE,
            DfsMapRpcServices.METHOD_PUT,
            DfsMapPutRequest.newBuilder()
                .setKey(key)
                .setValue(value)
                .setEpoch(epoch)
                .setApiVersion(apiVersion)
                .setReturnOldValue(returnOldValue)
                .build(),
            DfsMapPutResponse.class,
            callOptions
        );
    }

    public DfsMapRemoveResponse dfsMapRemove(
        long key,
        long epoch,
        int apiVersion,
        boolean returnOldValue,
        RpcCallOptions options
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return unary(
            DfsMapRpcServices.SERVICE,
            DfsMapRpcServices.METHOD_REMOVE,
            DfsMapRemoveRequest.newBuilder()
                .setKey(key)
                .setEpoch(epoch)
                .setApiVersion(apiVersion)
                .setReturnOldValue(returnOldValue)
                .build(),
            DfsMapRemoveResponse.class,
            callOptions
        );
    }

    public List<DfsMapRangeItem> dfsMapRange(long start, int count, long epoch, int apiVersion, boolean keysOnly, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RangeCollector collector = new RangeCollector();
        dfsMapRangeStreaming(start, count, epoch, apiVersion, keysOnly, callOptions, collector);
        return collector.await(callOptions.deadlineEpochMs());
    }

    public void dfsMapRangeStreaming(
        long start,
        int count,
        long epoch,
        int apiVersion,
        boolean keysOnly,
        RpcCallOptions options,
        RpcClientStreamObserver<DfsMapRangeItem> observer
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        startServerStream(
            DfsMapRpcServices.SERVICE,
            DfsMapRpcServices.METHOD_RANGE,
            DfsMapRangeRequest.newBuilder()
                .setStart(start)
                .setCount(count)
                .setEpoch(epoch)
                .setApiVersion(apiVersion)
                .setKeysOnly(keysOnly)
                .build(),
            callOptions,
            DfsMapRangeItem.class,
            observer
        );
    }

    public PubSubPublishResponse rpcPublish(String topic, String message, RpcCallOptions options) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return unary(
            RpcPubSubServices.SERVICE,
            RpcPubSubServices.METHOD_PUBLISH,
            PubSubPublishRequest.newBuilder()
                .setTopic(topic == null ? "" : topic)
                .setMessage(message == null ? "" : message)
                .build(),
            PubSubPublishResponse.class,
            callOptions
        );
    }

    public RpcStreamSubscription rpcSubscribe(String topic, RpcCallOptions options, RpcClientStreamObserver<PubSubEvent> observer) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        return startEventStream(
            RpcPubSubServices.SERVICE,
            RpcPubSubServices.METHOD_SUBSCRIBE,
            PubSubSubscribeRequest.newBuilder().setTopic(topic == null ? "" : topic).build(),
            callOptions,
            PubSubEvent.class,
            observer
        );
    }

    public <Req extends Message, Resp extends Message> Resp clientStream(
        String service,
        String method,
        List<Req> requests,
        Class<Resp> responseType,
        RpcCallOptions options
    ) throws Exception {
        try (RpcClientStreamHandle<Req, Resp> handle = openClientStream(service, method, responseType, options)) {
            if (requests != null) {
                for (Req request : requests) {
                    handle.send(request);
                }
            }
            return handle.halfCloseAndAwait();
        }
    }

    public <Req extends Message, Resp extends Message> void bidiStream(
        String service,
        String method,
        List<Req> requests,
        Class<Resp> responseType,
        RpcCallOptions options,
        RpcClientStreamObserver<Resp> observer
    ) throws Exception {
        try (RpcBidiStreamHandle<Req> handle = openBidiStream(service, method, responseType, options, observer)) {
            if (requests != null) {
                for (Req request : requests) {
                    handle.send(request);
                }
            }
            handle.halfClose();
        }
    }

    public <Req extends Message, Resp extends Message> RpcClientStreamHandle<Req, Resp> openClientStream(
        String service,
        String method,
        Class<Resp> responseType,
        RpcCallOptions options
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        SingleResponseCollector<Resp> collector = new SingleResponseCollector<>();
        ClientManagedStreamSession<Req, Resp> session = openClientManagedStream(
            service,
            method,
            callOptions,
            responseType,
            collector,
            RpcCallType.CLIENT_STREAM
        );
        return new RpcClientStreamHandle<>() {
            @Override
            public int requestId() {
                return session.requestId();
            }

            @Override
            public void send(Req request) throws Exception {
                session.send(request);
            }

            @Override
            public Resp halfCloseAndAwait() throws Exception {
                session.halfClose();
                return collector.await(callOptions.deadlineEpochMs(), "RPC client stream timeout");
            }

            @Override
            public void cancel() throws Exception {
                session.cancel();
            }
        };
    }

    public <Req extends Message, Resp extends Message> RpcBidiStreamHandle<Req> openBidiStream(
        String service,
        String method,
        Class<Resp> responseType,
        RpcCallOptions options,
        RpcClientStreamObserver<Resp> observer
    ) throws Exception {
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        ClientManagedStreamSession<Req, Resp> session = openClientManagedStream(
            service,
            method,
            callOptions,
            responseType,
            observer,
            RpcCallType.BIDI_STREAM
        );
        return new RpcBidiStreamHandle<>() {
            @Override
            public int requestId() {
                return session.requestId();
            }

            @Override
            public void send(Req request) throws Exception {
                session.send(request);
            }

            @Override
            public void halfClose() throws Exception {
                session.halfClose();
            }

            @Override
            public void cancel() throws Exception {
                session.cancel();
            }
        };
    }

    public StreamCollectResponse streamCollect(List<String> messages, RpcCallOptions options) throws Exception {
        List<StreamCollectRequest> requests = new ArrayList<>();
        if (messages != null) {
            for (String message : messages) {
                requests.add(StreamCollectRequest.newBuilder().setMessage(message == null ? "" : message).build());
            }
        }
        return clientStream(
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_COLLECT,
            requests,
            StreamCollectResponse.class,
            options
        );
    }

    public void streamChat(List<String> messages, RpcCallOptions options, RpcClientStreamObserver<StreamChatResponse> observer) throws Exception {
        List<StreamChatRequest> requests = new ArrayList<>();
        if (messages != null) {
            for (String message : messages) {
                requests.add(StreamChatRequest.newBuilder().setMessage(message == null ? "" : message).build());
            }
        }
        bidiStream(
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_CHAT,
            requests,
            StreamChatResponse.class,
            options,
            observer
        );
    }

    public boolean cancelStream(int requestId, RpcCallOptions options) throws Exception {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId 必须大于 0");
        }
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildControlFrame(requestId, "Cancel", RpcFrameType.CANCEL, callOptions);
        P2PWrapper<byte[]> response = P2PWrapper.build(P2PCommand.RPC_CONTROL, frame.toByteArray());
        return parseControlResponse(messageService.excute(response));
    }

    public boolean heartbeatStream(int requestId, RpcCallOptions options) throws Exception {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId 必须大于 0");
        }
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildControlFrame(requestId, "Heartbeat", RpcFrameType.HEARTBEAT, callOptions);
        P2PWrapper<byte[]> response = P2PWrapper.build(P2PCommand.RPC_CONTROL, frame.toByteArray());
        return parseControlResponse(messageService.excute(response));
    }

    public boolean windowUpdateStream(
        int requestId,
        int permits,
        int maxInflightFrames,
        int maxFrameBytes,
        RpcCallOptions options
    ) throws Exception {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId 必须大于 0");
        }
        RpcCallOptions callOptions = options == null ? RpcCallOptions.defaultOptions() : options;
        RpcFrame frame = buildControlFrame(requestId, "WindowUpdate", RpcFrameType.WINDOW_UPDATE, callOptions)
            .toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(Math.max(0, permits))
                .setMaxInflightFrames(Math.max(0, maxInflightFrames))
                .setMaxFrameBytes(Math.max(0, maxFrameBytes))
                .build())
            .build();
        P2PWrapper<byte[]> response = P2PWrapper.build(P2PCommand.RPC_CONTROL, frame.toByteArray());
        return parseControlResponse(messageService.excute(response));
    }

    private <Req extends Message> RpcFrame buildUnaryFrame(
        String service,
        String method,
        Req request,
        RpcCallOptions options
    ) {
        return buildFrame(service, method, request, options, RpcCallType.UNARY);
    }

    private <Req extends Message> RpcFrame buildFrame(
        String service,
        String method,
        Req request,
        RpcCallOptions options,
        RpcCallType callType
    ) {
        RpcMeta meta = RpcMeta.newBuilder()
            .setRequestId(System.nanoTime())
            .setService(service == null ? "" : service)
            .setMethod(method == null ? "" : method)
            .setServiceVersion(options.serviceVersion())
            .setCallType(callType)
            .setDeadlineEpochMs(options.deadlineEpochMs())
            .setCodec("protobuf")
            .setTraceId(options.traceId())
            .setSpanId(options.spanId())
            .putAllHeaders(options.headers())
            .setIdempotent(options.idempotent())
            .build();
        return RpcFrame.newBuilder()
            .setMeta(meta)
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(ByteString.copyFrom(request.toByteArray()))
            .setEndOfStream(true)
            .build();
    }

    private <Resp extends Message> Resp parseUnaryResponse(P2PWrapper<?> wrapper, Class<Resp> responseType) throws Exception {
        if (wrapper == null) {
            throw new IllegalStateException("RPC 响应为空");
        }
        if (wrapper.getCommand() != P2PCommand.RPC_UNARY && wrapper.getCommand() != P2PCommand.RPC_HEALTH && wrapper.getCommand() != P2PCommand.RPC_DISCOVER) {
            throw new IllegalStateException(String.valueOf(wrapper.getData()));
        }
        RpcFrame responseFrame = RpcFrame.parseFrom((byte[]) wrapper.getData());
        if (!responseFrame.hasStatus()) {
            throw new IllegalStateException("RPC 响应缺少状态");
        }
        if (responseFrame.getFrameType() == RpcFrameType.ERROR) {
            throw new IllegalStateException(responseFrame.getStatus().getMessage());
        }
        if (responseFrame.getStatus().getCode() != RpcStatusCode.OK && responseFrame.getPayload().isEmpty()) {
            throw new IllegalStateException(responseFrame.getStatus().getMessage());
        }
        Method parseFrom = responseType.getMethod("parseFrom", byte[].class);
        return responseType.cast(parseFrom.invoke(null, responseFrame.getPayload().toByteArray()));
    }

    private <Req extends Message, Resp extends Message> void startServerStream(
        String service,
        String method,
        Req request,
        RpcCallOptions options,
        Class<Resp> responseType,
        RpcClientStreamObserver<Resp> observer
    ) throws Exception {
        if (observer == null) {
            throw new IllegalArgumentException("observer 不能为空");
        }
        RpcFrame frame = buildStreamFrame(service, method, request, options);
        StreamP2PWrapper<byte[]> wrapper = StreamP2PWrapper.buildStream(0, 0, P2PCommand.RPC_STREAM, frame.toByteArray(), false);
        messageService.streamRequest(wrapper, new MessageStreamAdapter<>(
            this,
            wrapper,
            options,
            responseType,
            observer,
            true,
            options.windowUpdateBatch(),
            null
        ));
    }

    private <Req extends Message, Resp extends Message> RpcStreamSubscription startEventStream(
        String service,
        String method,
        Req request,
        RpcCallOptions options,
        Class<Resp> responseType,
        RpcClientStreamObserver<Resp> observer
    ) throws Exception {
        if (observer == null) {
            throw new IllegalArgumentException("observer 不能为空");
        }
        RpcFrame frame = buildStreamFrame(service, method, request, options);
        StreamP2PWrapper<byte[]> wrapper = StreamP2PWrapper.buildStream(0, 0, P2PCommand.RPC_EVENT, frame.toByteArray(), false);
        messageService.streamRequest(wrapper, new MessageStreamAdapter<>(
            this,
            wrapper,
            options,
            responseType,
            observer,
            true,
            options.windowUpdateBatch(),
            null
        ));
        return new RpcStreamSubscription() {
            @Override
            public int requestId() {
                return wrapper.getSeq();
            }

            @Override
            public void cancel() {
                try {
                    cancelStream(wrapper.getSeq(), options);
                } catch (Exception ignored) {
                } finally {
                    // 保留现有本地回收路径，兼容尚未升级 RPC_CONTROL 的服务端。
                    messageService.cancelExcute(wrapper.getSeq());
                }
            }
        };
    }

    private <Req extends Message, Resp extends Message> ClientManagedStreamSession<Req, Resp> openClientManagedStream(
        String service,
        String method,
        RpcCallOptions options,
        Class<Resp> responseType,
        RpcClientStreamObserver<Resp> observer,
        RpcCallType callType
    ) throws Exception {
        if (observer == null) {
            throw new IllegalArgumentException("observer 不能为空");
        }
        RpcFrame openFrame = buildOpenStreamFrame(service, method, null, options, callType);
        StreamP2PWrapper<byte[]> openWrapper = StreamP2PWrapper.buildStream(0, 0, P2PCommand.RPC_STREAM, openFrame.toByteArray(), false);
        OutboundWindow outboundWindow = OutboundWindow.create(options.initialStreamPermits());
        MessageStreamAdapter<Resp> adapter = new MessageStreamAdapter<>(
            this,
            openWrapper,
            options,
            responseType,
            observer,
            callType == RpcCallType.BIDI_STREAM,
            options.windowUpdateBatch(),
            outboundWindow
        );
        AbstractSendMesageExecutor boundExecutor = null;
        if (messageService instanceof BoundStreamMessageService boundStreamMessageService) {
            BoundStreamMessageService.BoundStreamRequest boundRequest = boundStreamMessageService.openBoundStreamRequest(openWrapper, adapter);
            boundExecutor = boundRequest.executor();
        } else {
            messageService.streamRequest(openWrapper, adapter);
        }
        return new ClientManagedStreamSession<>(this, openWrapper.getSeq(), boundExecutor, options, outboundWindow);
    }

    private RpcFrame buildControlFrame(int requestId, String method, RpcFrameType frameType, RpcCallOptions options) {
        RpcMeta meta = RpcMeta.newBuilder()
            .setRequestId(requestId)
            .setService("rpc.control")
            .setMethod(method)
            .setServiceVersion(options.serviceVersion())
            .setCallType(RpcCallType.UNARY)
            .setDeadlineEpochMs(options.deadlineEpochMs())
            .setCodec("protobuf")
            .setTraceId(options.traceId())
            .setSpanId(options.spanId())
            .putAllHeaders(options.headers())
            .build();
        return RpcFrame.newBuilder()
            .setMeta(meta)
            .setFrameType(frameType)
            .setEndOfStream(true)
            .build();
    }

    private <Req extends Message> RpcFrame buildStreamFrame(String service, String method, Req request, RpcCallOptions options) {
        RpcFrame.Builder builder = buildFrame(service, method, request, options, RpcCallType.SERVER_STREAM)
            .toBuilder()
            .setEndOfStream(false);
        if (options.initialStreamPermits() > 0 || options.initialMaxInflightFrames() > 0 || options.initialMaxFrameBytes() > 0) {
            builder.setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(options.initialStreamPermits())
                .setMaxInflightFrames(options.initialMaxInflightFrames())
                .setMaxFrameBytes(options.initialMaxFrameBytes())
                .build());
        }
        return builder.build();
    }

    private <Req extends Message> RpcFrame buildOpenStreamFrame(
        String service,
        String method,
        Req request,
        RpcCallOptions options,
        RpcCallType callType
    ) {
        RpcFrame.Builder builder = buildFrame(
            service,
            method,
            request == null ? com.google.protobuf.Empty.getDefaultInstance() : request,
            options,
            callType
        ).toBuilder().setEndOfStream(false);
        if ((callType == RpcCallType.BIDI_STREAM || callType == RpcCallType.CLIENT_STREAM)
            && (options.initialStreamPermits() > 0 || options.initialMaxInflightFrames() > 0 || options.initialMaxFrameBytes() > 0)) {
            builder.setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(options.initialStreamPermits())
                .setMaxInflightFrames(options.initialMaxInflightFrames())
                .setMaxFrameBytes(options.initialMaxFrameBytes())
                .build());
        }
        return builder.build();
    }

    private void sendClientStreamFrame(
        AbstractSendMesageExecutor boundExecutor,
        int requestId,
        int frameIndex,
        RpcFrame frame,
        boolean completed,
        RpcCallOptions options
    ) throws Exception {
        AbstractSendMesageExecutor executor = boundExecutor == null
            ? messageService.pollMesageExecutor(0, TimeUnit.SECONDS)
            : boundExecutor;
        executor.sendMessage(StreamP2PWrapper.buildStream(
            requestId,
            frameIndex,
            P2PCommand.RPC_STREAM,
            frame.toByteArray(),
            completed
        ));
    }

    private static final class MessageStreamAdapter<Resp extends Message> extends AbstractStreamResponseAdapter {
        private final P2PRpcClient client;
        private final StreamP2PWrapper<?> requestWrapper;
        private final RpcCallOptions options;
        private final Class<Resp> responseType;
        private final RpcClientStreamObserver<Resp> observer;
        private final boolean autoWindowUpdate;
        private final int windowUpdateBatch;
        private final OutboundWindow outboundWindow;
        private final ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
        private int consumedSinceUpdate;
        private int expectedChunkIndex;
        private volatile boolean completed;

        private MessageStreamAdapter(
            P2PRpcClient client,
            StreamP2PWrapper<?> requestWrapper,
            RpcCallOptions options,
            Class<Resp> responseType,
            RpcClientStreamObserver<Resp> observer,
            boolean autoWindowUpdate,
            int windowUpdateBatch,
            OutboundWindow outboundWindow
        ) {
            this.client = client;
            this.requestWrapper = requestWrapper;
            this.options = options;
            this.responseType = responseType;
            this.observer = observer;
            this.autoWindowUpdate = autoWindowUpdate;
            this.windowUpdateBatch = Math.max(1, windowUpdateBatch);
            this.outboundWindow = outboundWindow;
        }

        @Override
        public void response(StreamP2PWrapper message) {
            try {
                if (completed) {
                    return;
                }
                RpcFrame frame = RpcFrame.parseFrom((byte[]) message.getData());
                if (frame.getFrameType() == RpcFrameType.ERROR) {
                    completed = true;
                    closeOutboundWindow(frame.getStatus().getMessage(), true);
                    observer.onError(new IllegalStateException(frame.getStatus().getMessage()));
                    return;
                }
                if (frame.getFrameType() == RpcFrameType.WINDOW_UPDATE) {
                    if (outboundWindow != null && frame.hasFlowControl()) {
                        outboundWindow.addPermits(frame.getFlowControl().getPermits());
                    }
                    return;
                }
                if (frame.getFrameType() == RpcFrameType.DATA) {
                    handleDataFrame(frame);
                    if (autoWindowUpdate) {
                        tryAutoWindowUpdateIfNeeded();
                    }
                }
                if (frame.getFrameType() == RpcFrameType.CLOSE && !frame.getPayload().isEmpty()) {
                    handleDataFrame(frame);
                }
                if (message.isCompleted() || frame.getEndOfStream() || frame.getFrameType() == RpcFrameType.CLOSE) {
                    completed = true;
                    closeOutboundWindow("RPC request stream closed by server", false);
                    observer.onCompleted();
                }
            } catch (Exception ex) {
                completed = true;
                closeOutboundWindow(ex.getMessage(), true);
                try {
                    observer.onError(ex);
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void cancel(StreamP2PWrapper message) {
            if (completed) {
                return;
            }
            completed = true;
            closeOutboundWindow("RPC stream canceled", true);
            try {
                observer.onError(new IllegalStateException("RPC stream canceled"));
            } catch (Exception ignored) {
            }
        }

        private void tryAutoWindowUpdateIfNeeded() {
            consumedSinceUpdate++;
            if (consumedSinceUpdate < windowUpdateBatch) {
                return;
            }
            int permits = consumedSinceUpdate;
            consumedSinceUpdate = 0;
            int requestId = requestWrapper == null ? 0 : requestWrapper.getSeq();
            if (requestId <= 0 || client == null) {
                return;
            }
            try {
                client.windowUpdateStream(requestId, permits, 0, 0, options);
            } catch (Exception ignored) {
            }
        }

        private void handleDataFrame(RpcFrame frame) throws Exception {
            if (frame.getEndOfMessage()) {
                if (chunkBuffer.size() == 0 && frame.getChunkIndex() == 0) {
                    observer.onNext(parseResponse(responseType, frame.getPayload().toByteArray()));
                    return;
                }
                appendChunk(frame);
                observer.onNext(parseResponse(responseType, chunkBuffer.toByteArray()));
                resetChunkState();
                return;
            }
            appendChunk(frame);
        }

        private void appendChunk(RpcFrame frame) throws Exception {
            if (frame.getChunkIndex() == 0 && chunkBuffer.size() > 0) {
                resetChunkState();
            }
            if (frame.getChunkIndex() != expectedChunkIndex) {
                throw new IllegalStateException("RPC 分片顺序错误");
            }
            chunkBuffer.write(frame.getPayload().toByteArray());
            expectedChunkIndex++;
        }

        private void resetChunkState() {
            chunkBuffer.reset();
            expectedChunkIndex = 0;
        }

        private void closeOutboundWindow(String reason, boolean failed) {
            if (outboundWindow == null) {
                return;
            }
            String safeReason = reason == null || reason.isBlank()
                ? (failed ? "RPC request stream failed" : "RPC request stream closed")
                : reason;
            outboundWindow.close(safeReason);
        }
    }

    private static <Resp extends Message> Resp parseResponse(Class<Resp> responseType, byte[] payload) throws Exception {
        Method parseFrom = responseType.getMethod("parseFrom", byte[].class);
        return responseType.cast(parseFrom.invoke(null, payload));
    }

    private boolean parseControlResponse(P2PWrapper<?> wrapper) throws Exception {
        if (wrapper == null) {
            throw new IllegalStateException("RPC 控制响应为空");
        }
        if (wrapper.getCommand() != P2PCommand.RPC_CONTROL) {
            throw new IllegalStateException(String.valueOf(wrapper.getData()));
        }
        RpcFrame responseFrame = RpcFrame.parseFrom((byte[]) wrapper.getData());
        if (!responseFrame.hasStatus()) {
            throw new IllegalStateException("RPC 控制响应缺少状态");
        }
        RpcStatusCode code = responseFrame.getStatus().getCode();
        if (code == RpcStatusCode.CANCELED || code == RpcStatusCode.OK) {
            return true;
        }
        if (code == RpcStatusCode.NOT_FOUND) {
            return false;
        }
        throw new IllegalStateException(responseFrame.getStatus().getMessage());
    }

    private static final class RangeCollector implements RpcClientStreamObserver<DfsMapRangeItem> {
        private final List<DfsMapRangeItem> items = new ArrayList<>();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Exception error;

        @Override
        public void onNext(DfsMapRangeItem response) {
            items.add(response);
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }

        @Override
        public void onError(Exception exception) {
            error = exception;
            done.countDown();
        }

        private List<DfsMapRangeItem> await(long deadlineEpochMs) throws Exception {
            long timeoutMs = 5000L;
            if (deadlineEpochMs > 0) {
                timeoutMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("RPC range stream timeout");
            }
            if (error != null) {
                throw error;
            }
            return List.copyOf(items);
        }
    }

    private static final class SingleResponseCollector<Resp extends Message> implements RpcClientStreamObserver<Resp> {
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Resp response;
        private volatile Exception error;

        @Override
        public void onNext(Resp response) {
            this.response = response;
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }

        @Override
        public void onError(Exception exception) {
            error = exception;
            done.countDown();
        }

        private Resp await(long deadlineEpochMs, String timeoutMessage) throws Exception {
            long timeoutMs = 5000L;
            if (deadlineEpochMs > 0) {
                timeoutMs = Math.max(1L, deadlineEpochMs - System.currentTimeMillis());
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(timeoutMessage);
            }
            if (error != null) {
                throw error;
            }
            return response;
        }
    }

    private static final class OutboundWindow {
        private final Object lock = new Object();
        private final boolean enabled;
        private int permits;
        private boolean closed;
        private String closeReason = "RPC request stream closed";

        private OutboundWindow(boolean enabled, int permits) {
            this.enabled = enabled;
            this.permits = enabled ? Math.max(0, permits) : Integer.MAX_VALUE;
        }

        private static OutboundWindow create(int initialPermits) {
            return initialPermits > 0 ? new OutboundWindow(true, initialPermits) : new OutboundWindow(false, 0);
        }

        private void awaitPermit(long deadlineEpochMs) throws InterruptedException {
            if (!enabled) {
                return;
            }
            synchronized (lock) {
                while (permits <= 0 && !closed) {
                    long waitMillis = remainingWaitMillis(deadlineEpochMs);
                    if (waitMillis <= 0) {
                        throw new IllegalStateException("RPC request stream permit timeout");
                    }
                    lock.wait(waitMillis);
                }
                if (closed) {
                    throw new IllegalStateException(closeReason);
                }
                permits--;
            }
        }

        private void addPermits(int delta) {
            if (!enabled || delta <= 0) {
                return;
            }
            synchronized (lock) {
                permits += delta;
                lock.notifyAll();
            }
        }

        private void close(String reason) {
            synchronized (lock) {
                closed = true;
                closeReason = reason == null || reason.isBlank() ? "RPC request stream closed" : reason;
                lock.notifyAll();
            }
        }

        private static long remainingWaitMillis(long deadlineEpochMs) {
            if (deadlineEpochMs <= 0) {
                return 5000L;
            }
            return Math.max(0L, deadlineEpochMs - System.currentTimeMillis());
        }
    }

    private static final class ClientManagedStreamSession<Req extends Message, Resp extends Message> {
        private final P2PRpcClient client;
        private final int requestId;
        private final AbstractSendMesageExecutor boundExecutor;
        private final RpcCallOptions options;
        private final OutboundWindow outboundWindow;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private int nextFrameIndex = 1;

        private ClientManagedStreamSession(
            P2PRpcClient client,
            int requestId,
            AbstractSendMesageExecutor boundExecutor,
            RpcCallOptions options,
            OutboundWindow outboundWindow
        ) {
            this.client = client;
            this.requestId = requestId;
            this.boundExecutor = boundExecutor;
            this.options = options;
            this.outboundWindow = outboundWindow;
        }

        private int requestId() {
            return requestId;
        }

        private void send(Req request) throws Exception {
            if (closed.get()) {
                throw new IllegalStateException("RPC request stream already closed");
            }
            outboundWindow.awaitPermit(options.deadlineEpochMs());
            if (closed.get()) {
                throw new IllegalStateException("RPC request stream already closed");
            }
            RpcFrame dataFrame = RpcFrame.newBuilder()
                .setFrameType(RpcFrameType.DATA)
                .setPayload(ByteString.copyFrom((request == null ? com.google.protobuf.Empty.getDefaultInstance() : request).toByteArray()))
                .build();
            client.sendClientStreamFrame(boundExecutor, requestId, nextFrameIndex++, dataFrame, false, options);
        }

        private void halfClose() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            outboundWindow.close("RPC request stream already closed");
            RpcFrame closeFrame = RpcFrame.newBuilder()
                .setFrameType(RpcFrameType.CLOSE)
                .setEndOfStream(true)
                .build();
            client.sendClientStreamFrame(boundExecutor, requestId, nextFrameIndex, closeFrame, true, options);
        }

        private void cancel() throws Exception {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            outboundWindow.close("RPC request stream canceled");
            client.cancelStream(requestId, options);
        }
    }
}
