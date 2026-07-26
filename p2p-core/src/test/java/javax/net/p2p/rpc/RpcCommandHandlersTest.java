package javax.net.p2p.rpc;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.channel.AbstractStreamResponseAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ChannelAwaitOnMessage;
import javax.net.p2p.dfsmap.DfsMapBackend;
import javax.net.p2p.dfsmap.DfsMapRegistry;
import javax.net.p2p.dfsmap.model.DfsMapExecKvReq;
import javax.net.p2p.dfsmap.model.DfsMapExecKvResp;
import javax.net.p2p.dfsmap.model.DfsMapGetReq;
import javax.net.p2p.dfsmap.model.DfsMapGetResp;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyReq;
import javax.net.p2p.dfsmap.model.DfsMapGetTopologyResp;
import javax.net.p2p.dfsmap.model.DfsMapPingReq;
import javax.net.p2p.dfsmap.model.DfsMapPingResp;
import javax.net.p2p.dfsmap.model.DfsMapPutReq;
import javax.net.p2p.dfsmap.model.DfsMapPutResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeLocalResp;
import javax.net.p2p.dfsmap.model.DfsMapRangeReq;
import javax.net.p2p.dfsmap.model.DfsMapRangeResp;
import javax.net.p2p.dfsmap.model.DfsMapRemoveReq;
import javax.net.p2p.dfsmap.model.DfsMapRemoveResp;
import javax.net.p2p.dfsmap.model.DfsMapStatusCodes;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableReq;
import javax.net.p2p.dfsmap.model.DfsMapTablesEnableResp;
import javax.net.p2p.interfaces.BoundStreamMessageService;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.api.RpcClientStreamObserver;
import javax.net.p2p.rpc.api.RpcClientResponseException;
import javax.net.p2p.rpc.api.RpcClientResponseContext;
import javax.net.p2p.rpc.api.RpcClientStreamHandle;
import javax.net.p2p.rpc.api.RpcBidiStreamHandle;
import javax.net.p2p.rpc.api.RpcServerInterceptor;
import javax.net.p2p.rpc.api.RpcUnaryResult;
import javax.net.p2p.rpc.api.RpcStreamSubscription;
import javax.net.p2p.rpc.client.P2PRpcClient;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapGetResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapPutResponse;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeItem;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRangeRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveRequest;
import javax.net.p2p.rpc.dfsmap.proto.DfsMapRemoveResponse;
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
import javax.net.p2p.rpc.proto.MethodDescriptor;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFlowControl;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.model.RpcCallOptions;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.server.DfsMapRpcServices;
import javax.net.p2p.rpc.server.RpcBuiltinServices;
import javax.net.p2p.rpc.server.RpcControlSupport;
import javax.net.p2p.rpc.server.RpcPubSubBroker;
import javax.net.p2p.rpc.server.RpcPubSubServices;
import javax.net.p2p.rpc.server.RpcServerStreamHandler;
import javax.net.p2p.rpc.server.RpcStreamingBuiltinServices;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.server.handler.RpcDiscoverCommandServerHandler;
import javax.net.p2p.server.handler.RpcEventCommandServerHandler;
import javax.net.p2p.server.handler.RpcHealthCommandServerHandler;
import javax.net.p2p.server.handler.RpcStreamCommandServerHandler;
import javax.net.p2p.server.handler.RpcUnaryCommandServerHandler;
import javax.net.p2p.rpc.stream.proto.StreamChatResponse;
import javax.net.p2p.rpc.stream.proto.StreamCollectResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RpcCommandHandlersTest {

    @Test
    public void unaryHandlerDispatchesRegisteredMethod() throws Exception {
        String service = "test.rpc.v1.HealthService";
        String method = "Check";
        RpcBootstrap.registerUnary(
            service,
            method,
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request) -> HealthCheckResponse.newBuilder()
                .setHealthy(true)
                .setReady(true)
                .setMessage("hello:" + request.getService())
                .build()
        );

        RpcFrame frame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(1L)
                .setService(service)
                .setMethod(method)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("demo").build().toByteString())
            .setEndOfStream(true)
            .build();

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(7, P2PCommand.RPC_UNARY, frame.toByteArray()));
        Assertions.assertEquals(P2PCommand.RPC_UNARY, responseWrapper.getCommand());

        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        HealthCheckResponse response = HealthCheckResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertTrue(response.getHealthy());
        Assertions.assertEquals("hello:demo", response.getMessage());
    }

    @Test
    public void healthAndDiscoverHandlersReturnBuiltinPayload() throws Exception {
        RpcBootstrap.registerUnary(
            "test.rpc.v1.DiscoverService",
            "Ping",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request) -> HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("pong").build()
        );

        RpcHealthCommandServerHandler healthHandler = new RpcHealthCommandServerHandler();
        RpcDiscoverCommandServerHandler discoverHandler = new RpcDiscoverCommandServerHandler();

        RpcFrame healthFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(2L)
                .setService("rpc.health")
                .setMethod("Check")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("rpc").build().toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> healthResponseWrapper = healthHandler.process(P2PWrapper.build(8, P2PCommand.RPC_HEALTH, healthFrame.toByteArray()));
        RpcFrame healthResponseFrame = RpcFrame.parseFrom(healthResponseWrapper.getData());
        HealthCheckResponse healthResponse = HealthCheckResponse.parseFrom(healthResponseFrame.getPayload());
        Assertions.assertTrue(healthResponse.getReady());

        RpcFrame discoverFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(3L)
                .setService("rpc.discover")
                .setMethod("List")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(DiscoverRequest.newBuilder().setIncludeMethods(true).build().toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> discoverResponseWrapper = discoverHandler.process(P2PWrapper.build(9, P2PCommand.RPC_DISCOVER, discoverFrame.toByteArray()));
        RpcFrame discoverResponseFrame = RpcFrame.parseFrom(discoverResponseWrapper.getData());
        DiscoverResponse discoverResponse = DiscoverResponse.parseFrom(discoverResponseFrame.getPayload());
        Assertions.assertFalse(discoverResponse.getServicesList().isEmpty());
    }

    @Test
    public void discoverInterceptorCanShortCircuitAndExposeGovernanceFields() throws Exception {
        RpcDiscoverCommandServerHandler discoverHandler = new RpcDiscoverCommandServerHandler();
        RpcFrame discoverFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(43L)
                .setService("rpc.discover")
                .setMethod("List")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(DiscoverRequest.newBuilder().setIncludeMethods(true).build().toByteString())
            .setEndOfStream(true)
            .build();

        try (var ignored = RpcBootstrap.registerInterceptor(new RpcServerInterceptor() {
            @Override
            public RpcStatus beforeHandle(RpcRequestContext context) {
                if ("rpc.discover".equals(context.service()) && "List".equals(context.method())) {
                    return RpcStatus.newBuilder()
                        .setCode(RpcStatusCode.FORBIDDEN)
                        .setMessage("discover-blocked")
                        .setRetriable(false)
                        .build();
                }
                return null;
            }
        })) {
            P2PWrapper<byte[]> responseWrapper = discoverHandler.process(P2PWrapper.build(43, P2PCommand.RPC_DISCOVER, discoverFrame.toByteArray()));
            RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
            Assertions.assertEquals(RpcStatusCode.FORBIDDEN, responseFrame.getStatus().getCode());
            Assertions.assertEquals("discover-blocked", responseFrame.getStatus().getMessage());
            Assertions.assertTrue(responseFrame.getPayload().isEmpty());
            Assertions.assertEquals("rpc.discover", responseFrame.getMeta().getResponseHeadersMap().get("x-rpc-service"));
            Assertions.assertEquals("List", responseFrame.getMeta().getResponseHeadersMap().get("x-rpc-method"));
            Assertions.assertEquals("FORBIDDEN", responseFrame.getMeta().getResponseTrailersMap().get("x-rpc-status"));
        }
    }

    @Test
    public void discoverReturnsStableDfsMapMetadata() throws Exception {
        RpcDiscoverCommandServerHandler discoverHandler = new RpcDiscoverCommandServerHandler();
        RpcFrame discoverFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(4L)
                .setService("rpc.discover")
                .setMethod("List")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(DiscoverRequest.newBuilder()
                .setService(DfsMapRpcServices.SERVICE)
                .setIncludeMethods(true)
                .build()
                .toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = discoverHandler.process(P2PWrapper.build(10, P2PCommand.RPC_DISCOVER, discoverFrame.toByteArray()));
        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        DiscoverResponse response = DiscoverResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertEquals(1, response.getServicesCount());
        Assertions.assertEquals(DfsMapRpcServices.SERVICE, response.getServices(0).getService());
        Assertions.assertEquals("v1", response.getServices(0).getVersion());
        Assertions.assertEquals(4, response.getServices(0).getMethodsCount());

        MethodDescriptor getMethod = response.getServices(0).getMethods(0);
        MethodDescriptor putMethod = response.getServices(0).getMethods(1);
        MethodDescriptor rangeMethod = response.getServices(0).getMethods(2);
        MethodDescriptor removeMethod = response.getServices(0).getMethods(3);

        Assertions.assertEquals("Get", getMethod.getMethod());
        Assertions.assertEquals("javax.net.p2p.rpc.dfsmap.proto.DfsMapGetRequest", getMethod.getInputType());
        Assertions.assertEquals("javax.net.p2p.rpc.dfsmap.proto.DfsMapGetResponse", getMethod.getOutputType());
        Assertions.assertEquals(RpcCallType.UNARY, getMethod.getCallType());
        Assertions.assertTrue(getMethod.getIdempotent());

        Assertions.assertEquals("Put", putMethod.getMethod());
        Assertions.assertFalse(putMethod.getIdempotent());

        Assertions.assertEquals("Range", rangeMethod.getMethod());
        Assertions.assertEquals(RpcCallType.SERVER_STREAM, rangeMethod.getCallType());
        Assertions.assertTrue(rangeMethod.getIdempotent());

        Assertions.assertEquals("Remove", removeMethod.getMethod());
        Assertions.assertFalse(removeMethod.getIdempotent());
    }

    @Test
    public void discoverReturnsPubSubMetadata() throws Exception {
        RpcDiscoverCommandServerHandler discoverHandler = new RpcDiscoverCommandServerHandler();
        RpcFrame discoverFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(41L)
                .setService("rpc.discover")
                .setMethod("List")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(DiscoverRequest.newBuilder()
                .setService(RpcPubSubServices.SERVICE)
                .setIncludeMethods(true)
                .build()
                .toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = discoverHandler.process(P2PWrapper.build(41, P2PCommand.RPC_DISCOVER, discoverFrame.toByteArray()));
        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        DiscoverResponse response = DiscoverResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertEquals(1, response.getServicesCount());
        Assertions.assertEquals(RpcPubSubServices.SERVICE, response.getServices(0).getService());
        Assertions.assertEquals("v1", response.getServices(0).getVersion());
        Assertions.assertEquals(2, response.getServices(0).getMethodsCount());

        MethodDescriptor publishMethod = response.getServices(0).getMethods(0);
        MethodDescriptor subscribeMethod = response.getServices(0).getMethods(1);

        Assertions.assertEquals("Publish", publishMethod.getMethod());
        Assertions.assertEquals(RpcCallType.UNARY, publishMethod.getCallType());
        Assertions.assertFalse(publishMethod.getIdempotent());
        Assertions.assertEquals("javax.net.p2p.rpc.pubsub.proto.PubSubPublishRequest", publishMethod.getInputType());
        Assertions.assertEquals("javax.net.p2p.rpc.pubsub.proto.PubSubPublishResponse", publishMethod.getOutputType());

        Assertions.assertEquals("Subscribe", subscribeMethod.getMethod());
        Assertions.assertEquals(RpcCallType.SERVER_STREAM, subscribeMethod.getCallType());
        Assertions.assertTrue(subscribeMethod.getIdempotent());
        Assertions.assertEquals("javax.net.p2p.rpc.pubsub.proto.PubSubSubscribeRequest", subscribeMethod.getInputType());
        Assertions.assertEquals("javax.net.p2p.rpc.pubsub.proto.PubSubEvent", subscribeMethod.getOutputType());
    }

    @Test
    public void discoverReturnsStreamingBuiltinMetadata() throws Exception {
        RpcDiscoverCommandServerHandler discoverHandler = new RpcDiscoverCommandServerHandler();
        RpcFrame discoverFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(42L)
                .setService("rpc.discover")
                .setMethod("List")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(DiscoverRequest.newBuilder()
                .setService(RpcStreamingBuiltinServices.SERVICE)
                .setIncludeMethods(true)
                .build()
                .toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = discoverHandler.process(P2PWrapper.build(42, P2PCommand.RPC_DISCOVER, discoverFrame.toByteArray()));
        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        DiscoverResponse response = DiscoverResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertEquals(1, response.getServicesCount());
        Assertions.assertEquals(2, response.getServices(0).getMethodsCount());
        Assertions.assertEquals("Chat", response.getServices(0).getMethods(0).getMethod());
        Assertions.assertEquals(RpcCallType.BIDI_STREAM, response.getServices(0).getMethods(0).getCallType());
        Assertions.assertEquals("Collect", response.getServices(0).getMethods(1).getMethod());
        Assertions.assertEquals(RpcCallType.CLIENT_STREAM, response.getServices(0).getMethods(1).getCallType());
    }

    @Test
    public void streamHandlerEmitsServerStreamFrames() throws Exception {
        String service = "test.rpc.v1.StreamService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-1").build());
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-2").build());
                observer.onCompleted();
            }
        );

        RpcFrame frame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(10L)
                .setService(service)
                .setMethod("Watch")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("watch").build().toByteString())
            .setEndOfStream(true)
            .build();

        TestExecutor executor = new TestExecutor();
        RpcServerStreamHandler handler = new RpcServerStreamHandler();
        handler.processStream(executor, StreamP2PWrapper.buildStream(12, 0, P2PCommand.RPC_STREAM, frame.toByteArray(), false));

        Assertions.assertEquals(3, executor.size());
        RpcFrame item1 = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        RpcFrame item2 = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        RpcFrame close = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals(RpcFrameType.DATA, item1.getFrameType());
        Assertions.assertEquals("part-1", HealthCheckResponse.parseFrom(item1.getPayload()).getMessage());
        Assertions.assertEquals("part-2", HealthCheckResponse.parseFrom(item2.getPayload()).getMessage());
        Assertions.assertEquals(RpcFrameType.CLOSE, close.getFrameType());
        Assertions.assertTrue(close.getEndOfStream());
    }

    @Test
    public void windowUpdateFlushesQueuedServerStreamFrames() throws Exception {
        String service = "test.rpc.v1.WindowedStreamService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-1").build());
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-2").build());
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-3").build());
                observer.onCompleted();
            }
        );

        RpcFrame openFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(120L)
                .setService(service)
                .setMethod("Watch")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("watch").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .setEndOfStream(true)
            .build();

        TestExecutor executor = new TestExecutor();
        RpcServerStreamHandler handler = new RpcServerStreamHandler();
        handler.processStream(executor, StreamP2PWrapper.buildStream(120, 0, P2PCommand.RPC_STREAM, openFrame.toByteArray(), false));

        Assertions.assertEquals(1, executor.size());
        RpcFrame firstFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals("part-1", HealthCheckResponse.parseFrom(firstFrame.getPayload()).getMessage());

        RpcFrame windowUpdateFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(120L)
                .setService("rpc.control")
                .setMethod("WindowUpdate")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.WINDOW_UPDATE)
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(2).build())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> controlResponseWrapper = RpcControlSupport.handleControl(
            P2PWrapper.build(121, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        RpcFrame controlResponse = RpcFrame.parseFrom(controlResponseWrapper.getData());
        Assertions.assertEquals(RpcStatusCode.OK, controlResponse.getStatus().getCode());

        Assertions.assertEquals(3, executor.size());
        RpcFrame secondFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        RpcFrame thirdFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        RpcFrame closeFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals("part-2", HealthCheckResponse.parseFrom(secondFrame.getPayload()).getMessage());
        Assertions.assertEquals("part-3", HealthCheckResponse.parseFrom(thirdFrame.getPayload()).getMessage());
        Assertions.assertEquals(RpcFrameType.CLOSE, closeFrame.getFrameType());
        Assertions.assertTrue(closeFrame.getEndOfStream());
    }

    @Test
    public void maxInflightFramesCapsServerStreamFlushPerUpdate() throws Exception {
        String service = "test.rpc.v1.CappedWindowedStreamService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-1").build());
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-2").build());
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("part-3").build());
                observer.onCompleted();
            }
        );

        RpcFrame openFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(130L)
                .setService(service)
                .setMethod("Watch")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("watch").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(1)
                .setMaxInflightFrames(1)
                .build())
            .setEndOfStream(true)
            .build();

        TestExecutor executor = new TestExecutor();
        RpcServerStreamHandler handler = new RpcServerStreamHandler();
        handler.processStream(executor, StreamP2PWrapper.buildStream(130, 0, P2PCommand.RPC_STREAM, openFrame.toByteArray(), false));

        Assertions.assertEquals(1, executor.size());
        Assertions.assertEquals("part-1", HealthCheckResponse.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());

        RpcFrame windowUpdateFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(130L)
                .setService("rpc.control")
                .setMethod("WindowUpdate")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.WINDOW_UPDATE)
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(5)
                .setMaxInflightFrames(1)
                .build())
            .setEndOfStream(true)
            .build();

        RpcControlSupport.handleControl(
            P2PWrapper.build(131, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        Assertions.assertEquals(1, executor.size());
        Assertions.assertEquals("part-2", HealthCheckResponse.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());

        RpcControlSupport.handleControl(
            P2PWrapper.build(132, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        Assertions.assertEquals(2, executor.size());
        Assertions.assertEquals("part-3", HealthCheckResponse.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());
        Assertions.assertEquals(RpcFrameType.CLOSE, RpcFrame.parseFrom((byte[]) executor.poll().getData()).getFrameType());
    }

    @Test
    public void maxFrameBytesChunksOversizedServerStreamPayload() throws Exception {
        String service = "test.rpc.v1.MaxFrameBytesStreamService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("payload-too-large").build());
                observer.onCompleted();
            }
        );

        RpcFrame openFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(140L)
                .setService(service)
                .setMethod("Watch")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("watch").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(64)
                .setMaxFrameBytes(1)
                .build())
            .setEndOfStream(true)
            .build();

        TestExecutor executor = new TestExecutor();
        new RpcServerStreamHandler().processStream(executor, StreamP2PWrapper.buildStream(140, 0, P2PCommand.RPC_STREAM, openFrame.toByteArray(), false));

        Assertions.assertTrue(executor.size() > 2);
        com.google.protobuf.ByteString.Output payload = com.google.protobuf.ByteString.newOutput();
        int expectedChunkIndex = 0;
        while (executor.size() > 1) {
            RpcFrame dataFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
            Assertions.assertEquals(RpcFrameType.DATA, dataFrame.getFrameType());
            Assertions.assertEquals(expectedChunkIndex++, dataFrame.getChunkIndex());
            payload.write(dataFrame.getPayload().toByteArray());
        }
        RpcFrame closeFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals(RpcFrameType.CLOSE, closeFrame.getFrameType());

        HealthCheckResponse response = HealthCheckResponse.parseFrom(payload.toByteString().toByteArray());
        Assertions.assertEquals("payload-too-large", response.getMessage());
    }

    @Test
    public void streamHandlerReturnsRpcErrorFrameWhenInvokerThrows() throws Exception {
        String service = "test.rpc.v1.ThrowingStreamService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                throw new RuntimeException("boom");
            }
        );
        RpcFrame openFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(150L)
                .setService(service)
                .setMethod("Watch")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(HealthCheckRequest.newBuilder().setService("watch").build().toByteString())
            .setEndOfStream(true)
            .build();

        TestExecutor executor = new TestExecutor();
        new RpcServerStreamHandler().processStream(executor, StreamP2PWrapper.buildStream(150, 0, P2PCommand.RPC_STREAM, openFrame.toByteArray(), false));

        Assertions.assertEquals(1, executor.size());
        P2PWrapper<?> outbound = executor.poll();
        Assertions.assertEquals(P2PCommand.RPC_STREAM, outbound.getCommand());
        RpcFrame errorFrame = RpcFrame.parseFrom((byte[]) outbound.getData());
        Assertions.assertEquals(RpcFrameType.ERROR, errorFrame.getFrameType());
        Assertions.assertEquals(RpcStatusCode.INTERNAL_ERROR, errorFrame.getStatus().getCode());
        Assertions.assertTrue(errorFrame.getEndOfStream());
    }

    @Test
    public void rpcClientHealthAndDiscoverMethodsWork() throws Exception {
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    if (wrapper.getCommand() == P2PCommand.RPC_HEALTH) {
                        RpcFrame request = RpcFrame.parseFrom((byte[]) wrapper.getData());
                        RpcFrame response = RpcFrame.newBuilder()
                            .setMeta(request.getMeta())
                            .setFrameType(RpcFrameType.CLOSE)
                            .setStatus(javax.net.p2p.rpc.proto.RpcStatus.newBuilder().setCode(javax.net.p2p.rpc.proto.RpcStatusCode.OK).build())
                            .setPayload(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("ok").build().toByteString())
                            .setEndOfStream(true)
                            .build();
                        return P2PWrapper.build(wrapper.getSeq(), P2PCommand.RPC_HEALTH, response.toByteArray());
                    }
                    if (wrapper.getCommand() == P2PCommand.RPC_DISCOVER) {
                        RpcFrame request = RpcFrame.parseFrom((byte[]) wrapper.getData());
                        RpcFrame response = RpcFrame.newBuilder()
                            .setMeta(request.getMeta())
                            .setFrameType(RpcFrameType.CLOSE)
                            .setStatus(javax.net.p2p.rpc.proto.RpcStatus.newBuilder().setCode(javax.net.p2p.rpc.proto.RpcStatusCode.OK).build())
                            .setPayload(DiscoverResponse.newBuilder().build().toByteString())
                            .setEndOfStream(true)
                            .build();
                        return P2PWrapper.build(wrapper.getSeq(), P2PCommand.RPC_DISCOVER, response.toByteArray());
                    }
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        HealthCheckResponse health = client.health("svc", null);
        DiscoverResponse discover = client.discover("svc", true, null);

        Assertions.assertTrue(health.getHealthy());
        Assertions.assertEquals(0, discover.getServicesCount());
    }

    @Test
    public void builtinEchoServiceCanBeDispatchedAndCalled() throws Exception {
        RpcFrame frame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(30L)
                .setService(RpcBuiltinServices.ECHO_SERVICE)
                .setMethod(RpcBuiltinServices.ECHO_METHOD)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(EchoRequest.newBuilder().setMessage("hello-rpc").build().toByteString())
            .setEndOfStream(true)
            .build();

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(31, P2PCommand.RPC_UNARY, frame.toByteArray()));
        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        EchoResponse response = EchoResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertEquals("hello-rpc", response.getMessage());

        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        P2PRpcClient client = new P2PRpcClient(messageService);
        EchoResponse echo = client.echo("from-client", null);
        Assertions.assertEquals("from-client", echo.getMessage());
    }

    @Test
    public void unaryClientPropagatesExtendedMetadataToRequestContext() throws Exception {
        String service = "test.rpc.v1.UnaryMetadataService." + System.nanoTime();
        AtomicReference<RpcRequestContext> capturedContext = new AtomicReference<>();
        RpcBootstrap.registerUnary(
            service,
            "EchoMeta",
            "meta-v2",
            true,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> {
                capturedContext.set(context);
                return EchoResponse.newBuilder()
                    .setMessage(context.parentSpanId() + "|" + context.callerNodeId() + "|" + context.callerUserId() + "|" + context.headers().get("tenant"))
                    .build();
            }
        );

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        EchoResponse response = client.unary(
            service,
            "EchoMeta",
            EchoRequest.newBuilder().setMessage("hello").build(),
            EchoResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true)
        );

        assertRpcContextMetadata(capturedContext.get(), "meta-v2");
        Assertions.assertEquals("parent-1|node-1|user-1|tenant-a", response.getMessage());
    }

    @Test
    public void unaryDetailedReturnsResponseContext() throws Exception {
        String service = "test.rpc.v1.UnaryDetailedService." + System.nanoTime();
        RpcBootstrap.registerUnary(
            service,
            "EchoDetailed",
            "meta-v2",
            true,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> {
                context.putResponseHeader("x-rpc-stage", "unary");
                context.putResponseTrailer("x-rpc-finish", "done");
                context.putResponseStatusDetail("result", "ok");
                return EchoResponse.newBuilder().setMessage("detailed:" + request.getMessage()).build();
            }
        );

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcUnaryResult<EchoResponse> result = client.unaryDetailed(
            service,
            "EchoDetailed",
            EchoRequest.newBuilder().setMessage("hello").build(),
            EchoResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true)
        );

        Assertions.assertEquals("detailed:hello", result.response().getMessage());
        assertRpcMetaMetadata(result.context().meta(), "meta-v2", true);
        Assertions.assertEquals(RpcFrameType.CLOSE, result.context().frameType());
        Assertions.assertEquals(RpcStatusCode.OK, result.context().status().getCode());
        Assertions.assertEquals("ok", result.context().status().getDetailsMap().get("result"));
        Assertions.assertEquals("unary", result.context().responseHeaders().get("x-rpc-stage"));
        Assertions.assertEquals("done", result.context().responseTrailers().get("x-rpc-finish"));
        Assertions.assertTrue(result.context().endOfStream());
    }

    @Test
    public void unaryDetailedErrorThrowsResponseExceptionWithContext() throws Exception {
        String service = "test.rpc.v1.UnaryDetailedErrorService." + System.nanoTime();
        RpcBootstrap.registerUnary(
            service,
            "EchoDetailedError",
            "meta-v2",
            false,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> {
                context.putResponseHeader("x-rpc-stage", "unary-error");
                context.putResponseTrailer("x-rpc-finish", "failed");
                context.putResponseStatusDetail("reason", "boom");
                throw new IllegalStateException("unary-detailed-boom");
            }
        );

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcClientResponseException exception = Assertions.assertThrows(RpcClientResponseException.class, () ->
            client.unaryDetailed(
                service,
                "EchoDetailedError",
                EchoRequest.newBuilder().setMessage("hello").build(),
                EchoResponse.class,
                metadataOptions().withServiceVersion("meta-v2")
            )
        );

        Assertions.assertEquals("unary-detailed-boom", exception.getMessage());
        Assertions.assertNotNull(exception.context());
        Assertions.assertEquals(RpcFrameType.ERROR, exception.context().frameType());
        Assertions.assertEquals(RpcStatusCode.INTERNAL_ERROR, exception.context().status().getCode());
        Assertions.assertEquals("unary-detailed-boom", exception.context().status().getMessage());
        Assertions.assertEquals("boom", exception.context().status().getDetailsMap().get("reason"));
        Assertions.assertEquals("unary-error", exception.context().responseHeaders().get("x-rpc-stage"));
        Assertions.assertEquals("failed", exception.context().responseTrailers().get("x-rpc-finish"));
        Assertions.assertTrue(exception.context().endOfStream());
    }

    @Test
    public void unaryNotFoundStillIncludesAuditGovernanceFields() throws Exception {
        String service = "test.rpc.v1.UnaryNotFoundService." + System.nanoTime();

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcClientResponseException exception = Assertions.assertThrows(RpcClientResponseException.class, () ->
            client.unaryDetailed(
                service,
                "Missing",
                EchoRequest.newBuilder().setMessage("hello").build(),
                EchoResponse.class,
                metadataOptions()
            )
        );

        Assertions.assertNotNull(exception.context());
        Assertions.assertEquals(RpcStatusCode.NOT_FOUND, exception.context().status().getCode());
        Assertions.assertEquals(service, exception.context().responseHeaders().get("x-rpc-service"));
        Assertions.assertEquals("Missing", exception.context().responseHeaders().get("x-rpc-method"));
        Assertions.assertEquals(RpcStatusCode.NOT_FOUND.name(), exception.context().responseTrailers().get("x-rpc-status"));
        Assertions.assertEquals(service, exception.context().status().getDetailsMap().get("audit.service"));
        Assertions.assertEquals("Missing", exception.context().status().getDetailsMap().get("audit.method"));
    }

    @Test
    public void unaryStdErrorIsMappedToRpcResponseException() {
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return javax.net.p2p.error.P2PErrors.stdError(wrapper.getSeq(), javax.net.p2p.error.P2PErrorCode.AUTH_PERMISSION_DENIED);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcClientResponseException exception = Assertions.assertThrows(RpcClientResponseException.class, () ->
            client.echo("hello", metadataOptions())
        );
        Assertions.assertEquals(RpcStatusCode.FORBIDDEN, exception.context().status().getCode());
        Assertions.assertEquals("auth.permission_denied", exception.context().status().getDetailsMap().get("p2p.std_error_key"));
    }

    @Test
    public void unaryDetailedIncludesAuditGovernanceFields() throws Exception {
        String service = "test.rpc.v1.UnaryAuditService." + System.nanoTime();
        RpcBootstrap.registerUnary(
            service,
            "EchoAudit",
            "meta-v2",
            true,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> EchoResponse.newBuilder().setMessage("audit:" + request.getMessage()).build()
        );

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcUnaryResult<EchoResponse> result = client.unaryDetailed(
            service,
            "EchoAudit",
            EchoRequest.newBuilder().setMessage("hello").build(),
            EchoResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true)
        );

        Assertions.assertEquals("audit:hello", result.response().getMessage());
        Assertions.assertEquals(service, result.context().responseHeaders().get("x-rpc-service"));
        Assertions.assertEquals("EchoAudit", result.context().responseHeaders().get("x-rpc-method"));
        Assertions.assertEquals("OK", result.context().responseTrailers().get("x-rpc-status"));
        Assertions.assertNotNull(result.context().responseTrailers().get("x-rpc-duration-ms"));
        Assertions.assertEquals(service, result.context().status().getDetailsMap().get("audit.service"));
        Assertions.assertEquals("EchoAudit", result.context().status().getDetailsMap().get("audit.method"));
        Assertions.assertEquals("trace-1", result.context().status().getDetailsMap().get("audit.trace_id"));
        Assertions.assertEquals("user-1", result.context().status().getDetailsMap().get("audit.caller_user_id"));
    }

    @Test
    public void unaryInterceptorCanShortCircuitBeforeInvoker() throws Exception {
        String service = "test.rpc.v1.UnaryGovernanceBlockService." + System.nanoTime();
        AtomicBoolean invoked = new AtomicBoolean(false);
        RpcBootstrap.registerUnary(
            service,
            "Blocked",
            "meta-v2",
            false,
            EchoRequest.class,
            EchoResponse.class,
            (context, request) -> {
                invoked.set(true);
                return EchoResponse.newBuilder().setMessage("should-not-run").build();
            }
        );

        RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    return handler.process((P2PWrapper<byte[]>) wrapper);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );

        try (var ignored = RpcBootstrap.registerInterceptor(new RpcServerInterceptor() {
            @Override
            public RpcStatus beforeHandle(RpcRequestContext context) {
                if (service.equals(context.service()) && "Blocked".equals(context.method())) {
                    return RpcStatus.newBuilder()
                        .setCode(RpcStatusCode.FORBIDDEN)
                        .setMessage("blocked-by-interceptor")
                        .setRetriable(false)
                        .build();
                }
                return null;
            }
        })) {
            P2PRpcClient client = new P2PRpcClient(messageService);
            RpcClientResponseException exception = Assertions.assertThrows(RpcClientResponseException.class, () ->
                client.unaryDetailed(
                    service,
                    "Blocked",
                    EchoRequest.newBuilder().setMessage("hello").build(),
                    EchoResponse.class,
                    metadataOptions().withServiceVersion("meta-v2")
                )
            );

            Assertions.assertFalse(invoked.get());
            Assertions.assertEquals(RpcStatusCode.FORBIDDEN, exception.context().status().getCode());
            Assertions.assertEquals("blocked-by-interceptor", exception.getMessage());
            Assertions.assertEquals(service, exception.context().responseHeaders().get("x-rpc-service"));
            Assertions.assertEquals("Blocked", exception.context().responseHeaders().get("x-rpc-method"));
            Assertions.assertEquals("FORBIDDEN", exception.context().responseTrailers().get("x-rpc-status"));
        }
    }

    @Test
    public void dfsMapGetRpcCanBeDispatchedAndCalled() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(1234L, true));
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                    .setRequestId(40L)
                    .setService(DfsMapRpcServices.SERVICE)
                    .setMethod(DfsMapRpcServices.METHOD_GET)
                    .setServiceVersion("v1")
                    .setCallType(RpcCallType.UNARY)
                    .build())
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(DfsMapGetRequest.newBuilder()
                    .setApiVersion(1)
                    .setEpoch(9L)
                    .setKey(88L)
                    .build()
                    .toByteString())
                .setEndOfStream(true)
                .build();

            RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
            P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(41, P2PCommand.RPC_UNARY, frame.toByteArray()));
            RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
            Assertions.assertEquals(RpcStatusCode.OK, responseFrame.getStatus().getCode());
            DfsMapGetResponse response = DfsMapGetResponse.parseFrom(responseFrame.getPayload());
            Assertions.assertEquals(DfsMapStatusCodes.OK, response.getStatus());
            Assertions.assertTrue(response.getFound());
            Assertions.assertEquals(1234L, response.getValue());
            Assertions.assertEquals(88L, response.getKey());

            P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
                P2PMessageService.class.getClassLoader(),
                new Class<?>[]{P2PMessageService.class},
                (proxy, method, args) -> {
                    if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                        return handler.process((P2PWrapper<byte[]>) wrapper);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
            P2PRpcClient client = new P2PRpcClient(messageService);
            DfsMapGetResponse clientResponse = client.dfsMapGet(99L, 7L, 2, null);
            Assertions.assertEquals(DfsMapStatusCodes.OK, clientResponse.getStatus());
            Assertions.assertTrue(clientResponse.getFound());
            Assertions.assertEquals(1234L, clientResponse.getValue());
            Assertions.assertEquals(99L, clientResponse.getKey());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapGetNotFoundMapsToRpcStatusAndStillReturnsPayload() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(0L, false));
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                    .setRequestId(45L)
                    .setService(DfsMapRpcServices.SERVICE)
                    .setMethod(DfsMapRpcServices.METHOD_GET)
                    .setServiceVersion("v1")
                    .setCallType(RpcCallType.UNARY)
                    .build())
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(DfsMapGetRequest.newBuilder()
                    .setApiVersion(1)
                    .setEpoch(3L)
                    .setKey(66L)
                    .build()
                    .toByteString())
                .setEndOfStream(true)
                .build();

            RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
            P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(46, P2PCommand.RPC_UNARY, frame.toByteArray()));
            RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
            Assertions.assertEquals(RpcStatusCode.NOT_FOUND, responseFrame.getStatus().getCode());
            Assertions.assertFalse(responseFrame.getStatus().getRetriable());
            DfsMapGetResponse response = DfsMapGetResponse.parseFrom(responseFrame.getPayload());
            Assertions.assertEquals(DfsMapStatusCodes.NOT_FOUND, response.getStatus());
            Assertions.assertFalse(response.getFound());

            P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
                P2PMessageService.class.getClassLoader(),
                new Class<?>[]{P2PMessageService.class},
                (proxy, method, args) -> {
                    if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                        return handler.process((P2PWrapper<byte[]>) wrapper);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
            P2PRpcClient client = new P2PRpcClient(messageService);
            DfsMapGetResponse clientResponse = client.dfsMapGet(66L, 3L, 1, null);
            Assertions.assertEquals(DfsMapStatusCodes.NOT_FOUND, clientResponse.getStatus());
            Assertions.assertFalse(clientResponse.getFound());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapPutRpcCanBeDispatchedAndCalled() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(1234L, true));
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                    .setRequestId(50L)
                    .setService(DfsMapRpcServices.SERVICE)
                    .setMethod(DfsMapRpcServices.METHOD_PUT)
                    .setServiceVersion("v1")
                    .setCallType(RpcCallType.UNARY)
                    .build())
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(DfsMapPutRequest.newBuilder()
                    .setApiVersion(1)
                    .setEpoch(11L)
                    .setKey(88L)
                    .setValue(4321L)
                    .setReturnOldValue(true)
                    .build()
                    .toByteString())
                .setEndOfStream(true)
                .build();

            RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
            P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(51, P2PCommand.RPC_UNARY, frame.toByteArray()));
            RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
            Assertions.assertEquals(RpcStatusCode.OK, responseFrame.getStatus().getCode());
            DfsMapPutResponse response = DfsMapPutResponse.parseFrom(responseFrame.getPayload());
            Assertions.assertEquals(DfsMapStatusCodes.OK, response.getStatus());
            Assertions.assertTrue(response.getHadOld());
            Assertions.assertEquals(1234L, response.getOldValue());
            Assertions.assertEquals(88L, response.getKey());

            P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
                P2PMessageService.class.getClassLoader(),
                new Class<?>[]{P2PMessageService.class},
                (proxy, method, args) -> {
                    if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                        return handler.process((P2PWrapper<byte[]>) wrapper);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
            P2PRpcClient client = new P2PRpcClient(messageService);
            DfsMapPutResponse clientResponse = client.dfsMapPut(99L, 7777L, 13L, 2, true, null);
            Assertions.assertEquals(DfsMapStatusCodes.OK, clientResponse.getStatus());
            Assertions.assertTrue(clientResponse.getHadOld());
            Assertions.assertEquals(1234L, clientResponse.getOldValue());
            Assertions.assertEquals(99L, clientResponse.getKey());

            DfsMapGetResponse readBack = client.dfsMapGet(99L, 13L, 2, null);
            Assertions.assertTrue(readBack.getFound());
            Assertions.assertEquals(7777L, readBack.getValue());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapRemoveRpcCanBeDispatchedAndCalled() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(2468L, true));
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                    .setRequestId(60L)
                    .setService(DfsMapRpcServices.SERVICE)
                    .setMethod(DfsMapRpcServices.METHOD_REMOVE)
                    .setServiceVersion("v1")
                    .setCallType(RpcCallType.UNARY)
                    .build())
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(DfsMapRemoveRequest.newBuilder()
                    .setApiVersion(1)
                    .setEpoch(17L)
                    .setKey(88L)
                    .setReturnOldValue(true)
                    .build()
                    .toByteString())
                .setEndOfStream(true)
                .build();

            RpcUnaryCommandServerHandler handler = new RpcUnaryCommandServerHandler();
            P2PWrapper<byte[]> responseWrapper = handler.process(P2PWrapper.build(61, P2PCommand.RPC_UNARY, frame.toByteArray()));
            RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
            Assertions.assertEquals(RpcStatusCode.OK, responseFrame.getStatus().getCode());
            DfsMapRemoveResponse response = DfsMapRemoveResponse.parseFrom(responseFrame.getPayload());
            Assertions.assertEquals(DfsMapStatusCodes.OK, response.getStatus());
            Assertions.assertTrue(response.getRemoved());
            Assertions.assertEquals(2468L, response.getOldValue());
            Assertions.assertEquals(88L, response.getKey());

            P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
                P2PMessageService.class.getClassLoader(),
                new Class<?>[]{P2PMessageService.class},
                (proxy, method, args) -> {
                    if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                        return handler.process((P2PWrapper<byte[]>) wrapper);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
            P2PRpcClient client = new P2PRpcClient(messageService);
            DfsMapRemoveResponse clientResponse = client.dfsMapRemove(99L, 19L, 2, true, null);
            Assertions.assertEquals(DfsMapStatusCodes.OK, clientResponse.getStatus());
            Assertions.assertTrue(clientResponse.getRemoved());
            Assertions.assertEquals(2468L, clientResponse.getOldValue());
            Assertions.assertEquals(99L, clientResponse.getKey());

            DfsMapGetResponse readBack = client.dfsMapGet(99L, 19L, 2, null);
            Assertions.assertEquals(DfsMapStatusCodes.NOT_FOUND, readBack.getStatus());
            Assertions.assertFalse(readBack.getFound());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapRangeRpcStreamsEntriesAndClientCanCollectThem() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(2468L, true));
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                    .setRequestId(70L)
                    .setService(DfsMapRpcServices.SERVICE)
                    .setMethod(DfsMapRpcServices.METHOD_RANGE)
                    .setServiceVersion("v1")
                    .setCallType(RpcCallType.SERVER_STREAM)
                    .build())
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(DfsMapRangeRequest.newBuilder()
                    .setApiVersion(1)
                    .setEpoch(21L)
                    .setStart(66L)
                    .setCount(2)
                    .setKeysOnly(false)
                    .build()
                    .toByteString())
                .setEndOfStream(true)
                .build();

            RpcServerStreamHandler handler = new RpcServerStreamHandler();
            TestExecutor executor = new TestExecutor();
            handler.processStream(executor, StreamP2PWrapper.buildStream(71, 0, P2PCommand.RPC_STREAM, frame.toByteArray(), false));

            Assertions.assertEquals(3, executor.size());
            DfsMapRangeItem item1 = DfsMapRangeItem.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload());
            DfsMapRangeItem item2 = DfsMapRangeItem.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload());
            RpcFrame close = RpcFrame.parseFrom((byte[]) executor.poll().getData());
            Assertions.assertEquals(66L, item1.getKey());
            Assertions.assertEquals(2468L, item1.getValue());
            Assertions.assertTrue(item1.getHasValue());
            Assertions.assertEquals(88L, item2.getKey());
            Assertions.assertEquals(2468L, item2.getValue());
            Assertions.assertTrue(item2.getHasValue());
            Assertions.assertEquals(RpcFrameType.CLOSE, close.getFrameType());

            P2PMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
            P2PRpcClient client = new P2PRpcClient(messageService);
            List<DfsMapRangeItem> items = client.dfsMapRange(66L, 3, 21L, 1, false, null);
            Assertions.assertEquals(3, items.size());
            Assertions.assertEquals(List.of(66L, 88L, 99L), items.stream().map(DfsMapRangeItem::getKey).toList());
            Assertions.assertTrue(items.stream().allMatch(DfsMapRangeItem::getHasValue));
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapRangeStreamingCallbackReceivesItemsInOrder() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(3000L, true));
        try {
            RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());

            P2PRpcClient client = new P2PRpcClient(messageService);
            List<Long> keys = new ArrayList<>();
            List<Long> values = new ArrayList<>();
            List<String> signals = new ArrayList<>();
            client.dfsMapRangeStreaming(66L, 3, 22L, 1, false, null, new RpcClientStreamObserver<>() {
                @Override
                public void onNext(DfsMapRangeItem response) {
                    keys.add(response.getKey());
                    values.add(response.getValue());
                }

                @Override
                public void onCompleted() {
                    signals.add("completed");
                }

                @Override
                public void onError(Exception exception) {
                    signals.add("error:" + exception.getMessage());
                }
            });

            Assertions.assertEquals(List.of(66L, 88L, 99L), keys);
            Assertions.assertEquals(List.of(3000L, 3000L, 3000L), values);
            Assertions.assertEquals(List.of("completed"), signals);
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void serverStreamClientPropagatesExtendedMetadataToRequestContext() throws Exception {
        String service = "test.rpc.v1.ServerMetadataService." + System.nanoTime();
        AtomicReference<RpcRequestContext> capturedContext = new AtomicReference<>();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "meta-v2",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                capturedContext.set(context);
                observer.onNext(HealthCheckResponse.newBuilder()
                    .setHealthy(true)
                    .setReady(true)
                    .setMessage(context.traceId() + "|" + context.parentSpanId())
                    .build());
                observer.onCompleted();
            }
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<String> messages = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        client.serverStream(
            service,
            "Watch",
            HealthCheckRequest.newBuilder().setService("watch").build(),
            HealthCheckResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true),
            new RpcClientStreamObserver<>() {
                @Override
                public void onNext(HealthCheckResponse response) {
                    messages.add(response.getMessage());
                }

                @Override
                public void onCompleted() {
                    signals.add("completed");
                }

                @Override
                public void onError(Exception exception) {
                    signals.add("error:" + exception.getMessage());
                }
            }
        );

        assertRpcContextMetadata(capturedContext.get(), "meta-v2");
        Assertions.assertEquals(List.of("trace-1|parent-1"), messages);
        Assertions.assertEquals(List.of("completed"), signals);
    }

    @Test
    public void serverStreamObserverReceivesResponseContextFrames() throws Exception {
        String service = "test.rpc.v1.ServerResponseContextService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "meta-v2",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                context.putResponseHeader("x-rpc-stage", "server-stream");
                observer.onNext(HealthCheckResponse.newBuilder().setHealthy(true).setReady(true).setMessage("watch-ok").build());
                context.putResponseTrailer("x-rpc-finish", "stream-done");
                context.putResponseStatusDetail("stream", "ok");
                observer.onCompleted();
            }
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<RpcClientResponseContext> contexts = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        client.serverStream(
            service,
            "Watch",
            HealthCheckRequest.newBuilder().setService("watch").build(),
            HealthCheckResponse.class,
            metadataOptions().withServiceVersion("meta-v2"),
            new RpcClientStreamObserver<>() {
                @Override
                public void onResponseContext(RpcClientResponseContext context) {
                    contexts.add(context);
                }

                @Override
                public void onNext(HealthCheckResponse response) {
                    signals.add(response.getMessage());
                }

                @Override
                public void onCompleted() {
                    signals.add("completed");
                }

                @Override
                public void onError(Exception exception) {
                    signals.add("error:" + exception.getMessage());
                }
            }
        );

        Assertions.assertEquals(List.of("watch-ok", "completed"), signals);
        Assertions.assertEquals(2, contexts.size());
        Assertions.assertEquals(RpcFrameType.DATA, contexts.get(0).frameType());
        Assertions.assertEquals("meta-v2", contexts.get(0).meta().getServiceVersion());
        Assertions.assertEquals("trace-1", contexts.get(0).meta().getTraceId());
        Assertions.assertEquals("server-stream", contexts.get(0).responseHeaders().get("x-rpc-stage"));
        Assertions.assertFalse(contexts.get(0).endOfStream());
        Assertions.assertEquals(RpcFrameType.CLOSE, contexts.get(1).frameType());
        Assertions.assertEquals(RpcStatusCode.OK, contexts.get(1).status().getCode());
        Assertions.assertEquals("ok", contexts.get(1).status().getDetailsMap().get("stream"));
        Assertions.assertEquals("stream-done", contexts.get(1).responseTrailers().get("x-rpc-finish"));
        Assertions.assertTrue(contexts.get(1).endOfStream());
    }

    @Test
    public void serverStreamObserverOnErrorIncludesRpcResponseContext() throws Exception {
        String service = "test.rpc.v1.ServerStreamErrorContextService." + System.nanoTime();
        RpcBootstrap.registerServerStream(
            service,
            "Watch",
            "v1",
            true,
            HealthCheckRequest.class,
            HealthCheckResponse.class,
            (context, request, observer) -> {
                throw new RuntimeException("boom");
            }
        );
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<RpcClientResponseContext> contexts = new ArrayList<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        client.serverStream(
            service,
            "Watch",
            HealthCheckRequest.newBuilder().setService("watch").build(),
            HealthCheckResponse.class,
            metadataOptions(),
            new RpcClientStreamObserver<>() {
                @Override
                public void onResponseContext(RpcClientResponseContext context) {
                    contexts.add(context);
                }

                @Override
                public void onNext(HealthCheckResponse response) {
                }

                @Override
                public void onCompleted() {
                }

                @Override
                public void onError(Exception exception) {
                    error.set(exception);
                }
            }
        );

        Assertions.assertNotNull(error.get());
        Assertions.assertTrue(error.get() instanceof RpcClientResponseException);
        RpcClientResponseException ex = (RpcClientResponseException) error.get();
        Assertions.assertNotNull(ex.context());
        Assertions.assertEquals(RpcFrameType.ERROR, ex.context().frameType());
        Assertions.assertEquals(RpcStatusCode.INTERNAL_ERROR, ex.context().status().getCode());
        Assertions.assertTrue(ex.context().status().getMessage().contains("boom"));
        Assertions.assertEquals(1, contexts.size());
        Assertions.assertEquals(RpcFrameType.ERROR, contexts.get(0).frameType());
    }

    @Test
    public void dfsMapRangeStreamingAutoWindowUpdateSendsControlAndKeepsItemsFlowing() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(4000L, true));
        try {
            RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
            P2PRpcClient client = new P2PRpcClient(messageService);
            List<Long> keys = new ArrayList<>();

            client.dfsMapRangeStreaming(66L, 3, 23L, 1, false, null, new RpcClientStreamObserver<>() {
                @Override
                public void onNext(DfsMapRangeItem response) {
                    keys.add(response.getKey());
                }

                @Override
                public void onCompleted() {
                }

                @Override
                public void onError(Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            Assertions.assertEquals(List.of(66L, 88L, 99L), keys);
            Assertions.assertNotNull(messageService.lastControlRequest);
            RpcFrame controlFrame = RpcFrame.parseFrom(messageService.lastControlRequest.getData());
            Assertions.assertEquals(RpcFrameType.WINDOW_UPDATE, controlFrame.getFrameType());
            Assertions.assertEquals(2, controlFrame.getFlowControl().getPermits());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void dfsMapRangeStreamingReassemblesChunkedFrames() throws Exception {
        DfsMapRegistry.setBackend(new InMemoryDfsMapBackend(5000L, true));
        try {
            RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
            P2PRpcClient client = new P2PRpcClient(messageService);
            RpcCallOptions options = RpcCallOptions.defaultOptions()
                .withInitialStreamFlowControl(4, 0, 1)
                .withWindowUpdateBatch(2);
            List<DfsMapRangeItem> items = new ArrayList<>();

            client.dfsMapRangeStreaming(66L, 1, 24L, 1, false, options, new RpcClientStreamObserver<>() {
                @Override
                public void onNext(DfsMapRangeItem response) {
                    items.add(response);
                }

                @Override
                public void onCompleted() {
                }

                @Override
                public void onError(Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            Assertions.assertEquals(1, items.size());
            Assertions.assertEquals(66L, items.get(0).getKey());
            Assertions.assertEquals(5000L, items.get(0).getValue());
            Assertions.assertTrue(items.get(0).getHasValue());
        } finally {
            DfsMapRegistry.setBackend(null);
        }
    }

    @Test
    public void clientStreamCollectAggregatesMessages() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);

        StreamCollectResponse response = client.streamCollect(List.of("a", "b", "c"), null);

        Assertions.assertEquals(3, response.getCount());
        Assertions.assertEquals("a,b,c", response.getJoined());
        Assertions.assertEquals(List.of("a", "b", "c"), response.getMessagesList());
    }

    @Test
    public void clientStreamPropagatesExtendedMetadataToRequestContext() throws Exception {
        String service = "test.rpc.v1.ClientMetadataService." + System.nanoTime();
        AtomicReference<RpcRequestContext> capturedContext = new AtomicReference<>();
        RpcBootstrap.registerClientStream(
            service,
            "Collect",
            "meta-v2",
            true,
            javax.net.p2p.rpc.stream.proto.StreamCollectRequest.class,
            StreamCollectResponse.class,
            context -> {
                capturedContext.set(context);
                class Session implements javax.net.p2p.rpc.api.RpcClientStreamSession<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> {
                    private final RpcRequestContext ctx;
                    private final List<String> messages = new ArrayList<>();

                    private Session(RpcRequestContext ctx) {
                        this.ctx = ctx;
                    }

                    @Override
                    public void onNext(javax.net.p2p.rpc.stream.proto.StreamCollectRequest request) {
                        messages.add(request.getMessage());
                    }

                    @Override
                    public StreamCollectResponse onCompleted() {
                        return StreamCollectResponse.newBuilder()
                            .setCount(messages.size())
                            .setJoined(ctx.callerNodeId() + "|" + ctx.callerUserId() + "|" + ctx.headers().get("tenant"))
                            .addAllMessages(messages)
                            .build();
                    }
                }
                return new Session(context);
            },
            null
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        StreamCollectResponse response = client.clientStream(
            service,
            "Collect",
            List.of(
                javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("m1").build(),
                javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("m2").build()
            ),
            StreamCollectResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true)
        );

        assertRpcContextMetadata(capturedContext.get(), "meta-v2");
        Assertions.assertEquals(2, response.getCount());
        Assertions.assertEquals("node-1|user-1|tenant-a", response.getJoined());
        Assertions.assertEquals(List.of("m1", "m2"), response.getMessagesList());
    }

    @Test
    public void clientStreamHandleCanSendIncrementallyAndAwait() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, StreamCollectResponse.class, null)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("m1").build());
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("m2").build());
            StreamCollectResponse response = handle.halfCloseAndAwait();
            Assertions.assertEquals(2, response.getCount());
            Assertions.assertEquals("m1,m2", response.getJoined());
        }
    }

    @Test
    public void clientStreamRequestChunkingReassemblesOnServer() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.defaultOptions().withInitialStreamFlowControl(128, 0, 8);
        String longMessage = "0123456789abcdef0123456789abcdef0123456789abcdef";

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, StreamCollectResponse.class, options)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage(longMessage).build());
            StreamCollectResponse response = handle.halfCloseAndAwait();
            Assertions.assertEquals(1, response.getCount());
            Assertions.assertEquals(longMessage, response.getMessages(0));
        }
    }

    @Test
    public void bidiStreamChatReturnsResponsesInOrder() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<StreamChatResponse> responses = new ArrayList<>();
        List<String> signals = new ArrayList<>();

        client.streamChat(List.of("alpha", "beta"), null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(StreamChatResponse response) {
                responses.add(response);
            }

            @Override
            public void onCompleted() {
                signals.add("completed");
            }

            @Override
            public void onError(Exception exception) {
                signals.add("error:" + exception.getMessage());
            }
        });

        Assertions.assertEquals(2, responses.size());
        Assertions.assertEquals(1, responses.get(0).getIndex());
        Assertions.assertEquals("ack:alpha", responses.get(0).getMessage());
        Assertions.assertEquals(2, responses.get(1).getIndex());
        Assertions.assertEquals("ack:beta", responses.get(1).getMessage());
        Assertions.assertEquals(List.of("completed"), signals);
    }

    @Test
    public void bidiStreamPropagatesExtendedMetadataToRequestContext() throws Exception {
        String service = "test.rpc.v1.BidiMetadataService." + System.nanoTime();
        AtomicReference<RpcRequestContext> capturedContext = new AtomicReference<>();
        RpcBootstrap.registerBidiStream(
            service,
            "Chat",
            "meta-v2",
            true,
            javax.net.p2p.rpc.stream.proto.StreamChatRequest.class,
            StreamChatResponse.class,
            (context, observer) -> {
                capturedContext.set(context);
                class Session implements javax.net.p2p.rpc.api.RpcBidiStreamSession<javax.net.p2p.rpc.stream.proto.StreamChatRequest, StreamChatResponse> {
                    private final RpcRequestContext ctx;
                    private final javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> obs;
                    private int index;

                    private Session(RpcRequestContext ctx, javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> obs) {
                        this.ctx = ctx;
                        this.obs = obs;
                    }

                    @Override
                    public void onNext(javax.net.p2p.rpc.stream.proto.StreamChatRequest request) throws Exception {
                        index++;
                        obs.onNext(StreamChatResponse.newBuilder()
                            .setIndex(index)
                            .setMessage(ctx.traceId() + "|" + ctx.callerNodeId() + "|" + request.getMessage())
                            .build());
                    }

                    @Override
                    public void onCompleted() throws Exception {
                        obs.onCompleted();
                    }
                }
                return new Session(context, observer);
            }
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<String> signals = new ArrayList<>();
        client.bidiStream(
            service,
            "Chat",
            List.of(
                javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("a").build(),
                javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("b").build()
            ),
            StreamChatResponse.class,
            metadataOptions().withServiceVersion("meta-v2").withIdempotent(true),
            new RpcClientStreamObserver<>() {
                @Override
                public void onNext(StreamChatResponse response) {
                    signals.add(response.getMessage());
                }

                @Override
                public void onCompleted() {
                    signals.add("completed");
                }

                @Override
                public void onError(Exception exception) {
                    signals.add("error:" + exception.getMessage());
                }
            }
        );

        assertRpcContextMetadata(capturedContext.get(), "meta-v2");
        Assertions.assertEquals(List.of("trace-1|node-1|a", "trace-1|node-1|b", "completed"), signals);
    }

    @Test
    public void bidiObserverReceivesErrorResponseContext() throws Exception {
        String service = "test.rpc.v1.BidiErrorContextService." + System.nanoTime();
        RpcBootstrap.registerBidiStream(
            service,
            "Chat",
            "meta-v2",
            true,
            javax.net.p2p.rpc.stream.proto.StreamChatRequest.class,
            StreamChatResponse.class,
            (context, observer) -> {
                class Session implements javax.net.p2p.rpc.api.RpcBidiStreamSession<javax.net.p2p.rpc.stream.proto.StreamChatRequest, StreamChatResponse> {
                    private final RpcRequestContext ctx;

                    private Session(RpcRequestContext ctx) {
                        this.ctx = ctx;
                    }

                    @Override
                    public void onNext(javax.net.p2p.rpc.stream.proto.StreamChatRequest request) {
                        ctx.putResponseHeader("x-rpc-stage", "bidi-error");
                        ctx.putResponseTrailer("x-rpc-finish", "bidi-failed");
                        ctx.putResponseStatusDetail("bidi", "error");
                        throw new IllegalStateException("bidi-context-boom");
                    }

                    @Override
                    public void onCompleted() {
                    }
                }
                return new Session(context);
            }
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<RpcClientResponseContext> contexts = new ArrayList<>();
        List<String> signals = new ArrayList<>();
        client.bidiStream(
            service,
            "Chat",
            List.of(javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("a").build()),
            StreamChatResponse.class,
            metadataOptions().withServiceVersion("meta-v2"),
            new RpcClientStreamObserver<>() {
                @Override
                public void onResponseContext(RpcClientResponseContext context) {
                    contexts.add(context);
                }

                @Override
                public void onNext(StreamChatResponse response) {
                    signals.add(response.getMessage());
                }

                @Override
                public void onCompleted() {
                    signals.add("completed");
                }

                @Override
                public void onError(Exception exception) {
                    signals.add("error:" + exception.getMessage());
                }
            }
        );

        Assertions.assertEquals(List.of("error:bidi-context-boom"), signals);
        Assertions.assertEquals(1, contexts.size());
        Assertions.assertEquals(RpcFrameType.ERROR, contexts.get(0).frameType());
        Assertions.assertEquals(RpcStatusCode.INTERNAL_ERROR, contexts.get(0).status().getCode());
        Assertions.assertEquals("bidi-context-boom", contexts.get(0).status().getMessage());
        Assertions.assertEquals("error", contexts.get(0).status().getDetailsMap().get("bidi"));
        Assertions.assertEquals("bidi-error", contexts.get(0).responseHeaders().get("x-rpc-stage"));
        Assertions.assertEquals("bidi-failed", contexts.get(0).responseTrailers().get("x-rpc-finish"));
        Assertions.assertTrue(contexts.get(0).endOfStream());
    }

    @Test
    public void bidiStreamHandleCanSendIncrementally() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        List<String> messages = new ArrayList<>();

        try (RpcBidiStreamHandle<javax.net.p2p.rpc.stream.proto.StreamChatRequest> handle =
                 client.openBidiStream(
                     RpcStreamingBuiltinServices.SERVICE,
                     RpcStreamingBuiltinServices.METHOD_CHAT,
                     StreamChatResponse.class,
                     null,
                     new RpcClientStreamObserver<>() {
                         @Override
                         public void onNext(StreamChatResponse response) {
                             messages.add(response.getMessage());
                         }

                         @Override
                         public void onCompleted() {
                         }

                         @Override
                         public void onError(Exception exception) {
                             throw new RuntimeException(exception);
                         }
                     })) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("left").build());
            handle.send(javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("right").build());
            handle.halfClose();
        }

        Assertions.assertEquals(List.of("ack:left", "ack:right"), messages);
    }

    @Test
    public void clientManagedStreamUsesBoundExecutorInsteadOfPollingAnotherOne() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler()) {
            @Override
            public AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) {
                throw new AssertionError("不应在 client-stream/bidi 后续帧阶段重新 poll executor");
            }
        };
        P2PRpcClient client = new P2PRpcClient(messageService);

        StreamCollectResponse response = client.streamCollect(List.of("x", "y"), null);

        Assertions.assertEquals(2, response.getCount());
        Assertions.assertEquals("x,y", response.getJoined());
    }

    @Test
    public void streamOpenFramesDoNotMarkEndOfStream() throws Exception {
        RpcTestRpcStreamMessageService rpcStreamMessageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient rpcStreamClient = new P2PRpcClient(rpcStreamMessageService);
        rpcStreamClient.streamCollect(List.of("open-check"), null);
        Assertions.assertNotNull(rpcStreamMessageService.firstOpenFrame);
        Assertions.assertFalse(rpcStreamMessageService.firstOpenFrame.getEndOfStream());

        RpcTestStreamMessageService eventMessageService = new RpcTestStreamMessageService(new RpcEventCommandServerHandler(), new RpcUnaryCommandServerHandler());
        P2PRpcClient eventClient = new P2PRpcClient(eventMessageService);
        RpcStreamSubscription subscription = eventClient.rpcSubscribe("rpc.open.flag", null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        Assertions.assertNotNull(eventMessageService.firstOpenFrame);
        Assertions.assertFalse(eventMessageService.firstOpenFrame.getEndOfStream());
        subscription.cancel();
    }

    @Test
    public void clientRequestStreamEmitsWindowUpdateFramesForUploadBackpressure() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.defaultOptions().withInitialStreamFlowControl(1, 0, 0);

        StreamCollectResponse response = client.streamCollect(List.of("u1", "u2"), options);

        Assertions.assertEquals(2, response.getCount());
        Assertions.assertTrue(messageService.outboundFrames.stream().anyMatch(frame -> frame.getFrameType() == RpcFrameType.WINDOW_UPDATE));
    }

    @Test
    public void clientRequestStreamSendTimesOutWhenWindowUpdateNeverArrives() throws Exception {
        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler()) {
            @Override
            protected boolean shouldDeliverOutboundFrame(int requestId, P2PWrapper<?> outbound, RpcFrame frame) {
                return frame.getFrameType() != RpcFrameType.WINDOW_UPDATE;
            }
        };
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 120)
            .withInitialStreamFlowControl(1, 0, 0);

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, StreamCollectResponse.class, options)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("first").build());
            IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
                handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("second").build()));
            Assertions.assertEquals("RPC request stream permit timeout", exception.getMessage());
        }
    }

    @Test
    public void clientRequestStreamSendStopsWaitingWhenServerReturnsError() throws Exception {
        String service = "test.rpc.v1.ClientStreamErrorService." + System.nanoTime();
        RpcBootstrap.registerClientStream(
            service,
            "Collect",
            "v1",
            false,
            javax.net.p2p.rpc.stream.proto.StreamCollectRequest.class,
            StreamCollectResponse.class,
            context -> {
                class Session implements javax.net.p2p.rpc.api.RpcClientStreamSession<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> {
                    @Override
                    public void onNext(javax.net.p2p.rpc.stream.proto.StreamCollectRequest request) {
                        throw new IllegalStateException("forced-stream-error");
                    }

                    @Override
                    public StreamCollectResponse onCompleted() {
                        return StreamCollectResponse.getDefaultInstance();
                    }
                }
                return new Session();
            },
            null
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 2000)
            .withInitialStreamFlowControl(1, 0, 0);

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(service, "Collect", StreamCollectResponse.class, options)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("first").build());
            long startNanos = System.nanoTime();
            IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
                handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("second").build()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            Assertions.assertEquals("forced-stream-error", exception.getMessage());
            Assertions.assertTrue(elapsedMillis < 500, "远端 ERROR 后 send 不应继续等到 deadline");
        }
    }

    @Test
    public void bidiStreamSendStopsWhenServerClosesNormally() throws Exception {
        String service = "test.rpc.v1.BidiStreamCloseService." + System.nanoTime();
        RpcBootstrap.registerBidiStream(
            service,
            "Chat",
            "v1",
            false,
            javax.net.p2p.rpc.stream.proto.StreamChatRequest.class,
            StreamChatResponse.class,
            (context, observer) -> {
                class Session implements javax.net.p2p.rpc.api.RpcBidiStreamSession<javax.net.p2p.rpc.stream.proto.StreamChatRequest, StreamChatResponse> {
                    private final javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> obs;
                    private boolean done;

                    private Session(javax.net.p2p.rpc.api.RpcServerStreamObserver<StreamChatResponse> obs) {
                        this.obs = obs;
                    }

                    @Override
                    public void onNext(javax.net.p2p.rpc.stream.proto.StreamChatRequest request) throws Exception {
                        if (done) {
                            return;
                        }
                        done = true;
                        obs.onNext(StreamChatResponse.newBuilder().setIndex(1).setMessage("ack:" + request.getMessage()).build());
                        obs.onCompleted();
                    }

                    @Override
                    public void onCompleted() {
                    }
                }
                return new Session(observer);
            }
        );

        RpcTestRpcStreamMessageService messageService = new RpcTestRpcStreamMessageService(new RpcStreamCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 2000)
            .withInitialStreamFlowControl(2, 0, 0);
        List<String> signals = new ArrayList<>();

        try (RpcBidiStreamHandle<javax.net.p2p.rpc.stream.proto.StreamChatRequest> handle =
                 client.openBidiStream(service, "Chat", StreamChatResponse.class, options, new RpcClientStreamObserver<>() {
                     @Override
                     public void onNext(StreamChatResponse response) {
                         signals.add(response.getMessage());
                     }

                     @Override
                     public void onCompleted() {
                         signals.add("completed");
                     }

                     @Override
                     public void onError(Exception exception) {
                         signals.add("error:" + exception.getMessage());
                     }
                 })) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("first").build());
            long startNanos = System.nanoTime();
            IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () ->
                handle.send(javax.net.p2p.rpc.stream.proto.StreamChatRequest.newBuilder().setMessage("second").build()));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            Assertions.assertEquals("RPC request stream closed by server", exception.getMessage());
            Assertions.assertTrue(elapsedMillis < 500, "远端 CLOSE 后 send 不应继续发送或等待");
        }
        Assertions.assertEquals(List.of("ack:first", "completed"), signals);
    }

    @Test
    public void clientRequestStreamSendWaitsForDelayedWindowUpdateAndThenRecovers() throws Exception {
        class DelayedWindowUpdateMessageService extends RpcTestRpcStreamMessageService {
            private final List<BufferedOutbound> delayedFrames = new ArrayList<>();
            private volatile boolean releaseWindowUpdates;

            private DelayedWindowUpdateMessageService() {
                super(new RpcStreamCommandServerHandler());
            }

            @Override
            protected synchronized boolean shouldDeliverOutboundFrame(int requestId, P2PWrapper<?> outbound, RpcFrame frame) {
                if (frame.getFrameType() != RpcFrameType.WINDOW_UPDATE || releaseWindowUpdates) {
                    return true;
                }
                delayedFrames.add(new BufferedOutbound(requestId, outbound));
                return false;
            }

            private synchronized void releaseDelayedWindowUpdates() {
                releaseWindowUpdates = true;
                List<BufferedOutbound> pending = new ArrayList<>(delayedFrames);
                delayedFrames.clear();
                for (BufferedOutbound pendingFrame : pending) {
                    deliverOutboundFrame(pendingFrame.requestId(), pendingFrame.outbound());
                }
            }
        }
        DelayedWindowUpdateMessageService messageService = new DelayedWindowUpdateMessageService();
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 2000)
            .withInitialStreamFlowControl(1, 0, 0);

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, StreamCollectResponse.class, options)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("first").build());

            CountDownLatch sendStarted = new CountDownLatch(1);
            CountDownLatch sendFinished = new CountDownLatch(1);
            AtomicReference<Throwable> sendFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                sendStarted.countDown();
                try {
                    handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("second").build());
                } catch (Throwable throwable) {
                    sendFailure.set(throwable);
                } finally {
                    sendFinished.countDown();
                }
            }, "rpc-delayed-window-update");
            sender.start();

            Assertions.assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS));
            Assertions.assertFalse(sendFinished.await(150, TimeUnit.MILLISECONDS), "延迟补窗前 send 不应提前结束");
            messageService.releaseDelayedWindowUpdates();
            Assertions.assertTrue(sendFinished.await(1000, TimeUnit.MILLISECONDS), "补窗后 send 应恢复");
            if (sendFailure.get() != null) {
                throw new AssertionError(sendFailure.get());
            }

            StreamCollectResponse response = handle.halfCloseAndAwait();
            Assertions.assertEquals(List.of("first", "second"), response.getMessagesList());
            Assertions.assertEquals("first,second", response.getJoined());
        }
    }

    @Test
    public void clientRequestStreamSendTimesOutWhenDelayedWindowUpdateArrivesTooLate() throws Exception {
        class DelayedWindowUpdateMessageService extends RpcTestRpcStreamMessageService {
            private final List<BufferedOutbound> delayedFrames = new ArrayList<>();

            private DelayedWindowUpdateMessageService() {
                super(new RpcStreamCommandServerHandler());
            }

            @Override
            protected synchronized boolean shouldDeliverOutboundFrame(int requestId, P2PWrapper<?> outbound, RpcFrame frame) {
                if (frame.getFrameType() != RpcFrameType.WINDOW_UPDATE) {
                    return true;
                }
                delayedFrames.add(new BufferedOutbound(requestId, outbound));
                return false;
            }

            private synchronized void releaseDelayedWindowUpdates() {
                List<BufferedOutbound> pending = new ArrayList<>(delayedFrames);
                delayedFrames.clear();
                for (BufferedOutbound pendingFrame : pending) {
                    deliverOutboundFrame(pendingFrame.requestId(), pendingFrame.outbound());
                }
            }
        }
        DelayedWindowUpdateMessageService messageService = new DelayedWindowUpdateMessageService();
        P2PRpcClient client = new P2PRpcClient(messageService);
        RpcCallOptions options = RpcCallOptions.withDeadline(System.currentTimeMillis() + 300)
            .withInitialStreamFlowControl(1, 0, 0);

        try (RpcClientStreamHandle<javax.net.p2p.rpc.stream.proto.StreamCollectRequest, StreamCollectResponse> handle =
                 client.openClientStream(RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, StreamCollectResponse.class, options)) {
            handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("first").build());

            CountDownLatch sendStarted = new CountDownLatch(1);
            CountDownLatch sendFinished = new CountDownLatch(1);
            AtomicReference<Throwable> sendFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                sendStarted.countDown();
                try {
                    handle.send(javax.net.p2p.rpc.stream.proto.StreamCollectRequest.newBuilder().setMessage("second").build());
                } catch (Throwable throwable) {
                    sendFailure.set(throwable);
                } finally {
                    sendFinished.countDown();
                }
            }, "rpc-late-window-update-timeout");
            sender.start();

            Assertions.assertTrue(sendStarted.await(500, TimeUnit.MILLISECONDS));
            Assertions.assertTrue(sendFinished.await(1000, TimeUnit.MILLISECONDS), "超过 deadline 后 send 应结束");
            Assertions.assertInstanceOf(IllegalStateException.class, sendFailure.get());
            Assertions.assertEquals("RPC request stream permit timeout", sendFailure.get().getMessage());
            messageService.releaseDelayedWindowUpdates();
        }
    }

    @Test
    public void rpcSubscribeReassemblesChunkedEvents() throws Exception {
        RpcTestStreamMessageService streamingService = new RpcTestStreamMessageService(new RpcEventCommandServerHandler(), new RpcUnaryCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(streamingService);
        RpcCallOptions options = RpcCallOptions.defaultOptions()
            .withInitialStreamFlowControl(4, 0, 1)
            .withWindowUpdateBatch(2);
        List<String> received = new ArrayList<>();

        RpcStreamSubscription subscription = client.rpcSubscribe("rpc.topic.chunked", options, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
                received.add(response.getTopic() + ":" + response.getMessage());
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        client.rpcPublish("rpc.topic.chunked", "m1", null);

        Assertions.assertEquals(List.of("rpc.topic.chunked:m1"), received);
        Assertions.assertNotNull(streamingService.lastControlRequest);
        Assertions.assertEquals(RpcFrameType.WINDOW_UPDATE, RpcFrame.parseFrom(streamingService.lastControlRequest.getData()).getFrameType());

        subscription.cancel();
    }

    @Test
    public void rpcEventSubscribePublishCancelWorks() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        RpcUnaryCommandServerHandler unaryHandler = new RpcUnaryCommandServerHandler();
        TestExecutor executor = new TestExecutor();

        RpcFrame subscribeFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(80L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_SUBSCRIBE)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubSubscribeRequest.newBuilder().setTopic("rpc.topic.1").build().toByteString())
            .setEndOfStream(true)
            .build();

        eventHandler.request(executor, StreamP2PWrapper.buildStream(81, 0, P2PCommand.RPC_EVENT, subscribeFrame.toByteArray(), false));
        Assertions.assertEquals(1, RpcPubSubBroker.subscriberCount("rpc.topic.1"));

        RpcFrame publishFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(82L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_PUBLISH)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.1").setMessage("m1").build().toByteString())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> publishResponseWrapper = unaryHandler.process(P2PWrapper.build(82, P2PCommand.RPC_UNARY, publishFrame.toByteArray()));
        PubSubPublishResponse publishResponse = PubSubPublishResponse.parseFrom(RpcFrame.parseFrom(publishResponseWrapper.getData()).getPayload());
        Assertions.assertTrue(publishResponse.getAccepted());
        Assertions.assertEquals(1, publishResponse.getSubscriberCount());

        RpcFrame eventFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        PubSubEvent event = PubSubEvent.parseFrom(eventFrame.getPayload());
        Assertions.assertEquals("rpc.topic.1", event.getTopic());
        Assertions.assertEquals("m1", event.getMessage());

        eventHandler.cancel(executor, StreamP2PWrapper.buildStream(81, true));
        Assertions.assertEquals(0, RpcPubSubBroker.subscriberCount("rpc.topic.1"));
    }

    @Test
    public void rpcEventWindowUpdateFlushesQueuedPubSubEvents() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        RpcUnaryCommandServerHandler unaryHandler = new RpcUnaryCommandServerHandler();
        TestExecutor executor = new TestExecutor();

        RpcFrame subscribeFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(180L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_SUBSCRIBE)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubSubscribeRequest.newBuilder().setTopic("rpc.topic.window").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .setEndOfStream(true)
            .build();

        eventHandler.request(executor, StreamP2PWrapper.buildStream(181, 0, P2PCommand.RPC_EVENT, subscribeFrame.toByteArray(), false));

        RpcFrame publishFrame1 = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(182L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_PUBLISH)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.window").setMessage("m1").build().toByteString())
            .setEndOfStream(true)
            .build();
        RpcFrame publishFrame2 = publishFrame1.toBuilder()
            .setMeta(publishFrame1.getMeta().toBuilder().setRequestId(183L).build())
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.window").setMessage("m2").build().toByteString())
            .build();

        unaryHandler.process(P2PWrapper.build(182, P2PCommand.RPC_UNARY, publishFrame1.toByteArray()));
        unaryHandler.process(P2PWrapper.build(183, P2PCommand.RPC_UNARY, publishFrame2.toByteArray()));

        Assertions.assertEquals(1, executor.size());
        RpcFrame firstEventFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals("m1", PubSubEvent.parseFrom(firstEventFrame.getPayload()).getMessage());

        RpcFrame windowUpdateFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(181L)
                .setService("rpc.control")
                .setMethod("WindowUpdate")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.WINDOW_UPDATE)
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> controlResponseWrapper = javax.net.p2p.rpc.server.RpcControlSupport.handleControl(
            P2PWrapper.build(184, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        RpcFrame controlResponse = RpcFrame.parseFrom(controlResponseWrapper.getData());
        Assertions.assertEquals(RpcStatusCode.OK, controlResponse.getStatus().getCode());
        Assertions.assertEquals(1, executor.size());
        RpcFrame secondEventFrame = RpcFrame.parseFrom((byte[]) executor.poll().getData());
        Assertions.assertEquals("m2", PubSubEvent.parseFrom(secondEventFrame.getPayload()).getMessage());

        eventHandler.cancel(executor, StreamP2PWrapper.buildStream(181, true));
    }

    @Test
    public void rpcEventMaxInflightFramesCapsFlushPerUpdate() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        RpcUnaryCommandServerHandler unaryHandler = new RpcUnaryCommandServerHandler();
        TestExecutor executor = new TestExecutor();

        RpcFrame subscribeFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(190L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_SUBSCRIBE)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubSubscribeRequest.newBuilder().setTopic("rpc.topic.window.cap").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(1)
                .setMaxInflightFrames(1)
                .build())
            .setEndOfStream(true)
            .build();

        eventHandler.request(executor, StreamP2PWrapper.buildStream(191, 0, P2PCommand.RPC_EVENT, subscribeFrame.toByteArray(), false));

        RpcFrame publishFrame1 = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(192L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_PUBLISH)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.window.cap").setMessage("m1").build().toByteString())
            .setEndOfStream(true)
            .build();
        RpcFrame publishFrame2 = publishFrame1.toBuilder()
            .setMeta(publishFrame1.getMeta().toBuilder().setRequestId(193L).build())
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.window.cap").setMessage("m2").build().toByteString())
            .build();
        RpcFrame publishFrame3 = publishFrame1.toBuilder()
            .setMeta(publishFrame1.getMeta().toBuilder().setRequestId(194L).build())
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.window.cap").setMessage("m3").build().toByteString())
            .build();

        unaryHandler.process(P2PWrapper.build(192, P2PCommand.RPC_UNARY, publishFrame1.toByteArray()));
        unaryHandler.process(P2PWrapper.build(193, P2PCommand.RPC_UNARY, publishFrame2.toByteArray()));
        unaryHandler.process(P2PWrapper.build(194, P2PCommand.RPC_UNARY, publishFrame3.toByteArray()));

        Assertions.assertEquals(1, executor.size());
        Assertions.assertEquals("m1", PubSubEvent.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());

        RpcFrame windowUpdateFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(191L)
                .setService("rpc.control")
                .setMethod("WindowUpdate")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.WINDOW_UPDATE)
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(5)
                .setMaxInflightFrames(1)
                .build())
            .setEndOfStream(true)
            .build();

        RpcControlSupport.handleControl(
            P2PWrapper.build(195, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        Assertions.assertEquals(1, executor.size());
        Assertions.assertEquals("m2", PubSubEvent.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());

        RpcControlSupport.handleControl(
            P2PWrapper.build(196, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        Assertions.assertEquals(1, executor.size());
        Assertions.assertEquals("m3", PubSubEvent.parseFrom(RpcFrame.parseFrom((byte[]) executor.poll().getData()).getPayload()).getMessage());

        eventHandler.cancel(executor, StreamP2PWrapper.buildStream(191, true));
    }

    @Test
    public void rpcEventMaxFrameBytesChunksOversizedPayload() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        RpcUnaryCommandServerHandler unaryHandler = new RpcUnaryCommandServerHandler();
        TestExecutor executor = new TestExecutor();

        RpcFrame subscribeFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(200L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_SUBSCRIBE)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.SERVER_STREAM)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubSubscribeRequest.newBuilder().setTopic("rpc.topic.framebytes").build().toByteString())
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(64)
                .setMaxFrameBytes(1)
                .build())
            .setEndOfStream(true)
            .build();

        eventHandler.request(executor, StreamP2PWrapper.buildStream(201, 0, P2PCommand.RPC_EVENT, subscribeFrame.toByteArray(), false));

        RpcFrame publishFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(202L)
                .setService(RpcPubSubServices.SERVICE)
                .setMethod(RpcPubSubServices.METHOD_PUBLISH)
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.OPEN)
            .setPayload(PubSubPublishRequest.newBuilder().setTopic("rpc.topic.framebytes").setMessage("m1").build().toByteString())
            .setEndOfStream(true)
            .build();

        unaryHandler.process(P2PWrapper.build(202, P2PCommand.RPC_UNARY, publishFrame.toByteArray()));

        Assertions.assertTrue(executor.size() > 1);
        com.google.protobuf.ByteString.Output payload = com.google.protobuf.ByteString.newOutput();
        int expectedChunkIndex = 0;
        boolean sawEndOfMessage = false;
        P2PWrapper<?> outbound;
        while ((outbound = executor.poll()) != null) {
            RpcFrame dataFrame = RpcFrame.parseFrom((byte[]) outbound.getData());
            Assertions.assertEquals(RpcFrameType.DATA, dataFrame.getFrameType());
            Assertions.assertEquals(expectedChunkIndex++, dataFrame.getChunkIndex());
            payload.write(dataFrame.getPayload().toByteArray());
            if (dataFrame.getEndOfMessage()) {
                sawEndOfMessage = true;
            }
        }

        Assertions.assertTrue(sawEndOfMessage);
        PubSubEvent event = PubSubEvent.parseFrom(payload.toByteString().toByteArray());
        Assertions.assertEquals("rpc.topic.framebytes", event.getTopic());
        Assertions.assertEquals("m1", event.getMessage());
        Assertions.assertEquals(1, RpcPubSubBroker.subscriberCount("rpc.topic.framebytes"));
        eventHandler.cancel(executor, StreamP2PWrapper.buildStream(201, true));
        Assertions.assertEquals(0, RpcPubSubBroker.subscriberCount("rpc.topic.framebytes"));
    }

    @Test
    public void rpcSubscribeRejectedTopicReturnsStructuredError() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        P2PRpcClient client = new P2PRpcClient(new RpcTestStreamMessageService(eventHandler, new RpcUnaryCommandServerHandler()));
        List<String> signals = new ArrayList<>();

        client.rpcSubscribe("bad topic", null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
                signals.add("next:" + response.getMessage());
            }

            @Override
            public void onCompleted() {
                signals.add("completed");
            }

            @Override
            public void onError(Exception exception) {
                signals.add("error:" + exception.getMessage());
            }
        });

        Assertions.assertEquals(List.of("error:rpc event subscribe rejected"), signals);
        Assertions.assertEquals(0, RpcPubSubBroker.subscriberCount("bad topic"));
    }

    @Test
    public void rpcControlReturnsNotFoundForUnknownTask() throws Exception {
        RpcFrame cancelFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(999L)
                .setService("rpc.control")
                .setMethod("Cancel")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.CANCEL)
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = RpcControlSupport.handleControl(
            P2PWrapper.build(91, P2PCommand.RPC_CONTROL, cancelFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );

        Assertions.assertEquals(P2PCommand.RPC_CONTROL, responseWrapper.getCommand());
        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        Assertions.assertEquals(RpcStatusCode.NOT_FOUND, responseFrame.getStatus().getCode());
        Assertions.assertEquals("task not found", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlHeartbeatReturnsAlive() throws Exception {
        RpcFrame heartbeatFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(777L)
                .setService("rpc.control")
                .setMethod("Heartbeat")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.HEARTBEAT)
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = RpcControlSupport.handleControl(
            P2PWrapper.build(92, P2PCommand.RPC_CONTROL, heartbeatFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );

        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        Assertions.assertEquals(RpcStatusCode.OK, responseFrame.getStatus().getCode());
        Assertions.assertEquals("alive", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlWindowUpdateReturnsNotFoundForUnknownTask() throws Exception {
        RpcFrame windowUpdateFrame = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(778L)
                .setService("rpc.control")
                .setMethod("WindowUpdate")
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(RpcFrameType.WINDOW_UPDATE)
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder()
                .setPermits(16)
                .setMaxInflightFrames(4)
                .build())
            .setEndOfStream(true)
            .build();

        P2PWrapper<byte[]> responseWrapper = RpcControlSupport.handleControl(
            P2PWrapper.build(93, P2PCommand.RPC_CONTROL, windowUpdateFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );

        RpcFrame responseFrame = RpcFrame.parseFrom(responseWrapper.getData());
        Assertions.assertEquals(RpcStatusCode.NOT_FOUND, responseFrame.getStatus().getCode());
        Assertions.assertEquals("task not found", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlRejectsUnsupportedFrameType() throws Exception {
        RpcFrame responseFrame = handleControl(controlFrame(RpcFrameType.OPEN, 779L, null), 94);

        Assertions.assertEquals(RpcStatusCode.METHOD_NOT_ALLOWED, responseFrame.getStatus().getCode());
        Assertions.assertEquals("only cancel, heartbeat, and window_update are supported", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlRejectsInvalidTargetRequestId() throws Exception {
        RpcFrame responseFrame = handleControl(controlFrame(RpcFrameType.CANCEL, 0L, null), 95);

        Assertions.assertEquals(RpcStatusCode.BAD_REQUEST, responseFrame.getStatus().getCode());
        Assertions.assertEquals("invalid target request id", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlRejectsWindowUpdateWithoutFlowControl() throws Exception {
        RpcFrame responseFrame = handleControl(controlFrame(RpcFrameType.WINDOW_UPDATE, 780L, null), 96);

        Assertions.assertEquals(RpcStatusCode.BAD_REQUEST, responseFrame.getStatus().getCode());
        Assertions.assertEquals("missing flow control", responseFrame.getStatus().getMessage());
    }

    @Test
    public void rpcControlRejectsEmptyWindowUpdateFlowControl() throws Exception {
        RpcFrame responseFrame = handleControl(
            controlFrame(RpcFrameType.WINDOW_UPDATE, 781L, RpcFlowControl.newBuilder().build()),
            97
        );

        Assertions.assertEquals(RpcStatusCode.BAD_REQUEST, responseFrame.getStatus().getCode());
        Assertions.assertEquals("empty flow control", responseFrame.getStatus().getMessage());
    }

    @Test
    public void controlFramesPropagateExtendedMetadata() throws Exception {
        CapturingControlMessageService messageService = new CapturingControlMessageService(new RpcEventCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);

        boolean alive = client.heartbeatStream(782, metadataOptions().withServiceVersion("meta-v2"));

        Assertions.assertTrue(alive);
        Assertions.assertNotNull(messageService.lastControlRequest);
        RpcFrame requestFrame = RpcFrame.parseFrom(messageService.lastControlRequest.getData());
        assertRpcMetaMetadata(requestFrame.getMeta(), "meta-v2", false);
    }

    @Test
    public void rpcSubscribeHandleCanCancelAndStopEvents() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        RpcUnaryCommandServerHandler unaryHandler = new RpcUnaryCommandServerHandler();
        java.util.concurrent.ConcurrentHashMap<Integer, TestExecutor> subscriptions = new java.util.concurrent.ConcurrentHashMap<>();

        P2PMessageService messageService = (P2PMessageService) Proxy.newProxyInstance(
            P2PMessageService.class.getClassLoader(),
            new Class<?>[]{P2PMessageService.class},
            (proxy, method, args) -> {
                if ("streamRequest".equals(method.getName()) && args != null && args.length == 2 && args[0] instanceof StreamP2PWrapper<?> wrapper) {
                    TestExecutor streamExecutor = new TestExecutor();
                    eventHandler.request(streamExecutor, (StreamP2PWrapper) wrapper);
                    subscriptions.put(wrapper.getSeq(), streamExecutor);
                    return P2PWrapper.build(wrapper.getSeq(), P2PCommand.STREAM_ACK, null);
                }
                if ("excute".equals(method.getName()) && args != null && args.length >= 1 && args[0] instanceof P2PWrapper<?> wrapper) {
                    P2PWrapper<?> response = unaryHandler.process((P2PWrapper<byte[]>) wrapper);
                    for (var entry : subscriptions.entrySet()) {
                        TestExecutor streamExecutor = entry.getValue();
                        P2PWrapper<?> outbound;
                        while ((outbound = streamExecutor.poll()) != null) {
                            AbstractStreamResponseAdapter adapter = (AbstractStreamResponseAdapter) args[1];
                        }
                    }
                    return response;
                }
                if ("cancelExcute".equals(method.getName()) && args != null && args.length == 1) {
                    int seq = (Integer) args[0];
                    TestExecutor streamExecutor = subscriptions.remove(seq);
                    if (streamExecutor != null) {
                        eventHandler.cancel(streamExecutor, StreamP2PWrapper.buildStream(seq, true));
                    }
                    return null;
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
        // 上面代理无法直接把 stream 回调到客户端，这里改为专用代理实现。
        P2PMessageService streamingService = new RpcTestStreamMessageService(eventHandler, unaryHandler);
        P2PRpcClient client = new P2PRpcClient(streamingService);

        List<String> received = new ArrayList<>();
        RpcStreamSubscription subscription = client.rpcSubscribe("rpc.topic.2", null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
                received.add(response.getMessage());
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Exception exception) {
                received.add("error:" + exception.getMessage());
            }
        });

        client.rpcPublish("rpc.topic.2", "first", null);
        subscription.cancel();
        client.rpcPublish("rpc.topic.2", "second", null);

        Assertions.assertEquals(List.of("first"), received);
        Assertions.assertEquals(0, RpcPubSubBroker.subscriberCount("rpc.topic.2"));
    }

    @Test
    public void rpcSubscribeAutoWindowUpdateSendsControlAndKeepsEventsFlowing() throws Exception {
        RpcTestStreamMessageService streamingService = new RpcTestStreamMessageService(new RpcEventCommandServerHandler(), new RpcUnaryCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(streamingService);

        List<String> received = new ArrayList<>();
        RpcStreamSubscription subscription = client.rpcSubscribe("rpc.topic.auto", null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
                received.add(response.getMessage());
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Exception exception) {
                received.add("error:" + exception.getMessage());
            }
        });

        client.rpcPublish("rpc.topic.auto", "first", null);
        client.rpcPublish("rpc.topic.auto", "second", null);
        client.rpcPublish("rpc.topic.auto", "third", null);

        Assertions.assertEquals(List.of("first", "second", "third"), received);
        Assertions.assertNotNull(streamingService.lastControlRequest);
        RpcFrame controlFrame = RpcFrame.parseFrom(streamingService.lastControlRequest.getData());
        Assertions.assertEquals(RpcFrameType.WINDOW_UPDATE, controlFrame.getFrameType());
        Assertions.assertEquals(subscription.requestId(), (int) controlFrame.getMeta().getRequestId());
        Assertions.assertEquals(2, controlFrame.getFlowControl().getPermits());

        subscription.cancel();
    }

    @Test
    public void rpcSubscribeCancelSendsRpcControlAndLegacyCancel() throws Exception {
        RpcEventCommandServerHandler eventHandler = new RpcEventCommandServerHandler();
        CapturingControlMessageService messageService = new CapturingControlMessageService(eventHandler);
        P2PRpcClient client = new P2PRpcClient(messageService);

        RpcStreamSubscription subscription = client.rpcSubscribe("rpc.topic.3", null, new RpcClientStreamObserver<>() {
            @Override
            public void onNext(PubSubEvent response) {
            }

            @Override
            public void onCompleted() {
            }

            @Override
            public void onError(Exception exception) {
            }
        });

        int requestId = subscription.requestId();
        subscription.cancel();

        Assertions.assertEquals(requestId, messageService.canceledRequestId);
        Assertions.assertNotNull(messageService.lastControlRequest);
        Assertions.assertEquals(P2PCommand.RPC_CONTROL, messageService.lastControlRequest.getCommand());
        RpcFrame controlFrame = RpcFrame.parseFrom(messageService.lastControlRequest.getData());
        Assertions.assertEquals(RpcFrameType.CANCEL, controlFrame.getFrameType());
        Assertions.assertEquals(requestId, (int) controlFrame.getMeta().getRequestId());
        Assertions.assertEquals("rpc.control", controlFrame.getMeta().getService());
    }

    @Test
    public void heartbeatStreamSendsRpcControlHeartbeat() throws Exception {
        CapturingControlMessageService messageService = new CapturingControlMessageService(new RpcEventCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);

        boolean alive = client.heartbeatStream(1234, null);

        Assertions.assertTrue(alive);
        Assertions.assertNotNull(messageService.lastControlRequest);
        RpcFrame controlFrame = RpcFrame.parseFrom(messageService.lastControlRequest.getData());
        Assertions.assertEquals(RpcFrameType.HEARTBEAT, controlFrame.getFrameType());
        Assertions.assertEquals(1234, (int) controlFrame.getMeta().getRequestId());
        Assertions.assertEquals("Heartbeat", controlFrame.getMeta().getMethod());
    }

    @Test
    public void windowUpdateStreamSendsRpcControlWindowUpdate() throws Exception {
        CapturingControlMessageService messageService = new CapturingControlMessageService(new RpcEventCommandServerHandler());
        P2PRpcClient client = new P2PRpcClient(messageService);

        boolean updated = client.windowUpdateStream(1235, 32, 8, 4096, null);

        Assertions.assertTrue(updated);
        Assertions.assertNotNull(messageService.lastControlRequest);
        RpcFrame controlFrame = RpcFrame.parseFrom(messageService.lastControlRequest.getData());
        Assertions.assertEquals(RpcFrameType.WINDOW_UPDATE, controlFrame.getFrameType());
        Assertions.assertEquals(1235, (int) controlFrame.getMeta().getRequestId());
        Assertions.assertEquals("WindowUpdate", controlFrame.getMeta().getMethod());
        Assertions.assertEquals(32, controlFrame.getFlowControl().getPermits());
        Assertions.assertEquals(8, controlFrame.getFlowControl().getMaxInflightFrames());
        Assertions.assertEquals(4096, controlFrame.getFlowControl().getMaxFrameBytes());
    }

    static class TestExecutor extends AbstractSendMesageExecutor {
        private final java.util.concurrent.ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new java.util.concurrent.ConcurrentLinkedQueue<>();

        TestExecutor() {
            super(16);
            this.connected = true;
            this.channel = new io.netty.channel.embedded.EmbeddedChannel();
        }

        @Override
        public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
        }

        @Override
        public void recycle() {
        }

        @Override
        public void sendResponse(P2PWrapper response) {
            outgoing.add(response);
        }

        int size() {
            return outgoing.size();
        }

        P2PWrapper<?> poll() {
            return outgoing.poll();
        }
    }

    private record BufferedOutbound(int requestId, P2PWrapper<?> outbound) {
    }

    private static final class InMemoryDfsMapBackend implements DfsMapBackend {
        private final java.util.concurrent.ConcurrentHashMap<Long, Long> values = new java.util.concurrent.ConcurrentHashMap<>();

        private InMemoryDfsMapBackend(long value, boolean found) {
            if (found) {
                values.put(88L, value);
                values.put(99L, value);
                values.put(66L, value);
            }
        }

        @Override
        public DfsMapGetResp handleGet(DfsMapGetReq req) {
            DfsMapGetResp response = new DfsMapGetResp();
            Long currentValue = values.get(req.getKey());
            response.setStatus(currentValue != null ? DfsMapStatusCodes.OK : DfsMapStatusCodes.NOT_FOUND);
            response.setEpoch(req.getEpoch());
            response.setKey(req.getKey());
            response.setFound(currentValue != null);
            response.setValue(currentValue == null ? 0L : currentValue);
            return response;
        }

        @Override
        public DfsMapPutResp handlePut(DfsMapPutReq req) {
            Long oldValue = values.put(req.getKey(), req.getValue());
            DfsMapPutResp response = new DfsMapPutResp();
            response.setStatus(DfsMapStatusCodes.OK);
            response.setEpoch(req.getEpoch());
            response.setKey(req.getKey());
            response.setHadOld(oldValue != null);
            response.setOldValue(oldValue == null ? 0L : oldValue);
            return response;
        }

        @Override
        public DfsMapRemoveResp handleRemove(DfsMapRemoveReq req) {
            Long oldValue = values.remove(req.getKey());
            DfsMapRemoveResp response = new DfsMapRemoveResp();
            response.setStatus(oldValue != null ? DfsMapStatusCodes.OK : DfsMapStatusCodes.NOT_FOUND);
            response.setEpoch(req.getEpoch());
            response.setKey(req.getKey());
            response.setRemoved(oldValue != null);
            response.setOldValue(oldValue == null ? 0L : oldValue);
            return response;
        }

        @Override
        public DfsMapRangeResp handleRange(DfsMapRangeReq req) {
            List<Long> orderedKeys = new ArrayList<>(values.keySet());
            orderedKeys.sort(Comparator.naturalOrder());
            List<Long> matchedKeys = new ArrayList<>();
            List<Long> matchedValues = new ArrayList<>();
            for (Long key : orderedKeys) {
                if (key < req.getStart()) {
                    continue;
                }
                matchedKeys.add(key);
                if (!req.isKeysOnly()) {
                    matchedValues.add(values.get(key));
                }
                if (matchedKeys.size() >= Math.max(0, req.getCount())) {
                    break;
                }
            }
            DfsMapRangeResp response = new DfsMapRangeResp();
            response.setStatus(DfsMapStatusCodes.OK);
            response.setEpoch(req.getEpoch());
            response.setStart(req.getStart());
            response.setRequestedCount(req.getCount());
            response.setEmitted(matchedKeys.size());
            response.setKeys(matchedKeys.stream().mapToLong(Long::longValue).toArray());
            response.setValues(req.isKeysOnly() ? new long[0] : matchedValues.stream().mapToLong(Long::longValue).toArray());
            return response;
        }

        @Override
        public DfsMapExecKvResp handleExecKv(DfsMapExecKvReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DfsMapRangeLocalResp handleRangeLocal(DfsMapRangeLocalReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DfsMapPingResp handlePing(DfsMapPingReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DfsMapGetTopologyResp handleGetTopology(DfsMapGetTopologyReq req) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DfsMapTablesEnableResp handleTablesEnable(P2PCommand command, DfsMapTablesEnableReq req) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RpcTestStreamMessageService implements P2PMessageService {
        private final RpcEventCommandServerHandler eventHandler;
        private final RpcUnaryCommandServerHandler unaryHandler;
        private final java.util.concurrent.ConcurrentHashMap<Integer, AbstractStreamResponseAdapter> adapters = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<Integer, TestExecutor> executors = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile RpcFrame firstOpenFrame;
        private volatile P2PWrapper<byte[]> lastControlRequest;
        private int nextRequestId = 2000;

        private RpcTestStreamMessageService(RpcEventCommandServerHandler eventHandler, RpcUnaryCommandServerHandler unaryHandler) {
            this.eventHandler = eventHandler;
            this.unaryHandler = unaryHandler;
        }

        @Override
        public P2PWrapper excute(P2PWrapper request) throws Exception {
            P2PWrapper<?> response;
            if (request.getCommand() == P2PCommand.RPC_CONTROL) {
                lastControlRequest = (P2PWrapper<byte[]>) request;
                response = javax.net.p2p.rpc.server.RpcControlSupport.handleControl(
                    (P2PWrapper<byte[]>) request,
                    new java.util.concurrent.ConcurrentHashMap<>(),
                    new java.util.concurrent.ConcurrentHashMap<>()
                );
            } else {
                response = unaryHandler.process((P2PWrapper<byte[]>) request);
            }
            flushEvents();
            return response;
        }

        @Override
        public void cancelExcute(int requestId) {
            TestExecutor executor = executors.remove(requestId);
            adapters.remove(requestId);
            if (executor != null) {
                eventHandler.cancel(executor, StreamP2PWrapper.buildStream(requestId, true));
            }
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper streamRequest(P2PWrapper request, AbstractStreamResponseAdapter streamMessage) throws Exception {
            StreamP2PWrapper<?> wrapper = (StreamP2PWrapper<?>) request;
            wrapper.setSeq(nextRequestId++);
            firstOpenFrame = RpcFrame.parseFrom((byte[]) wrapper.getData());
            TestExecutor executor = new TestExecutor();
            eventHandler.request(executor, wrapper);
            adapters.put(wrapper.getSeq(), streamMessage);
            executors.put(wrapper.getSeq(), executor);
            flushEvents();
            return P2PWrapper.build(wrapper.getSeq(), P2PCommand.STREAM_ACK, null);
        }

        @Override
        public Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.net.InetSocketAddress getRemote() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeResponseFuture(int requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper exception(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeExceptionally(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(P2PWrapper response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTotalConnects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectSuccess(io.netty.channel.Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectFailed(Exception ex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorQueueEmpty(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorClose(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }

        private void flushEvents() {
            for (var entry : executors.entrySet()) {
                AbstractStreamResponseAdapter adapter = adapters.get(entry.getKey());
                if (adapter == null) {
                    continue;
                }
                P2PWrapper<?> outbound;
                while ((outbound = entry.getValue().poll()) != null) {
                    adapter.response((StreamP2PWrapper) outbound);
                }
            }
        }
    }

    private static class RpcTestRpcStreamMessageService implements P2PMessageService, BoundStreamMessageService {
        private final RpcStreamCommandServerHandler streamHandlerPrototype;
        private final java.util.concurrent.ConcurrentHashMap<Integer, AbstractStreamResponseAdapter> adapters = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<Integer, AbstractStreamRequestAdapter> handlers = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<Integer, TestExecutor> executors = new java.util.concurrent.ConcurrentHashMap<>();
        private final List<RpcFrame> outboundFrames = new ArrayList<>();
        private volatile RpcFrame firstOpenFrame;
        private volatile P2PWrapper<byte[]> lastControlRequest;
        private int nextRequestId = 3000;

        private RpcTestRpcStreamMessageService(RpcStreamCommandServerHandler streamHandler) {
            this.streamHandlerPrototype = streamHandler;
        }

        @Override
        public P2PWrapper excute(P2PWrapper request) throws Exception {
            if (request.getCommand() != P2PCommand.RPC_CONTROL) {
                throw new UnsupportedOperationException();
            }
            lastControlRequest = (P2PWrapper<byte[]>) request;
            P2PWrapper<?> response = javax.net.p2p.rpc.server.RpcControlSupport.handleControl(
                (P2PWrapper<byte[]>) request,
                new java.util.concurrent.ConcurrentHashMap<>(),
                handlers
            );
            flushEvents();
            return response;
        }

        @Override
        public void cancelExcute(int requestId) {
            AbstractStreamRequestAdapter handler = handlers.remove(requestId);
            TestExecutor executor = executors.remove(requestId);
            adapters.remove(requestId);
            if (handler instanceof RpcStreamCommandServerHandler streamHandler && executor != null) {
                streamHandler.cancel(executor, StreamP2PWrapper.buildStream(requestId, true));
            }
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper streamRequest(P2PWrapper request, AbstractStreamResponseAdapter streamMessage) throws Exception {
            return openBoundStreamRequest(request, streamMessage).ack();
        }

        @Override
        public BoundStreamRequest openBoundStreamRequest(P2PWrapper request, AbstractStreamResponseAdapter streamMessage) throws Exception {
            StreamP2PWrapper<?> wrapper = (StreamP2PWrapper<?>) request;
            wrapper.setSeq(nextRequestId++);
            firstOpenFrame = RpcFrame.parseFrom((byte[]) wrapper.getData());
            TestExecutor executor = new TestExecutor();
            LoopbackClientExecutor boundClientExecutor = new LoopbackClientExecutor(this);
            RpcStreamCommandServerHandler handler = new RpcStreamCommandServerHandler();
            adapters.put(wrapper.getSeq(), streamMessage);
            handlers.put(wrapper.getSeq(), handler);
            executors.put(wrapper.getSeq(), executor);
            handler.request(executor, wrapper);
            flushEvents();
            return new BoundStreamRequest(P2PWrapper.build(wrapper.getSeq(), P2PCommand.STREAM_ACK, null), boundClientExecutor);
        }

        @Override
        public Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) {
            return new LoopbackClientExecutor(this);
        }

        @Override
        public java.net.InetSocketAddress getRemote() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeResponseFuture(int requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper exception(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeExceptionally(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(P2PWrapper response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTotalConnects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectSuccess(io.netty.channel.Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectFailed(Exception ex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorQueueEmpty(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorClose(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }

        private void flushEvents() {
            for (var entry : executors.entrySet()) {
                AbstractStreamResponseAdapter adapter = adapters.get(entry.getKey());
                if (adapter == null) {
                    continue;
                }
                P2PWrapper<?> outbound;
                while ((outbound = entry.getValue().poll()) != null) {
                    try {
                        RpcFrame frame = RpcFrame.parseFrom((byte[]) outbound.getData());
                        outboundFrames.add(frame);
                        if (!shouldDeliverOutboundFrame(entry.getKey(), outbound, frame)) {
                            continue;
                        }
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                    deliverOutboundFrame(entry.getKey(), outbound);
                }
            }
        }

        protected boolean shouldDeliverOutboundFrame(int requestId, P2PWrapper<?> outbound, RpcFrame frame) {
            return true;
        }

        protected final void deliverOutboundFrame(int requestId, P2PWrapper<?> outbound) {
            AbstractStreamResponseAdapter adapter = adapters.get(requestId);
            if (adapter == null) {
                return;
            }
            adapter.response((StreamP2PWrapper) outbound);
        }

        private void deliverClientFrame(StreamP2PWrapper<?> wrapper) throws Exception {
            AbstractStreamRequestAdapter handler = handlers.get(wrapper.getSeq());
            TestExecutor executor = executors.get(wrapper.getSeq());
            if (!(handler instanceof RpcStreamCommandServerHandler streamHandler) || executor == null) {
                throw new IllegalStateException("stream handler not found");
            }
            if (wrapper.isCanceled()) {
                streamHandler.cancel(executor, wrapper);
            } else {
                streamHandler.request(executor, wrapper);
            }
            flushEvents();
        }

        private static final class LoopbackClientExecutor extends AbstractSendMesageExecutor {
            private final RpcTestRpcStreamMessageService messageService;

            private LoopbackClientExecutor(RpcTestRpcStreamMessageService messageService) {
                super(16);
                this.messageService = messageService;
                this.connected = true;
                this.channel = new io.netty.channel.embedded.EmbeddedChannel();
            }

            @Override
            public void sendMessage(P2PWrapper message) throws InterruptedException {
                try {
                    messageService.deliverClientFrame((StreamP2PWrapper<?>) message);
                } catch (InterruptedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
            }

            @Override
            public void recycle() {
            }
        }
    }

    private static RpcFrame controlFrame(RpcFrameType frameType, long requestId, RpcFlowControl flowControl) {
        RpcFrame.Builder builder = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(requestId)
                .setService("rpc.control")
                .setMethod(frameType.name())
                .setServiceVersion("v1")
                .setCallType(RpcCallType.UNARY)
                .build())
            .setFrameType(frameType)
            .setEndOfStream(true);
        if (flowControl != null) {
            builder.setFlowControl(flowControl);
        }
        return builder.build();
    }

    private static RpcFrame handleControl(RpcFrame requestFrame, int seq) throws Exception {
        P2PWrapper<byte[]> responseWrapper = RpcControlSupport.handleControl(
            P2PWrapper.build(seq, P2PCommand.RPC_CONTROL, requestFrame.toByteArray()),
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>()
        );
        return RpcFrame.parseFrom(responseWrapper.getData());
    }

    private static RpcCallOptions metadataOptions() {
        return RpcCallOptions.defaultOptions()
            .withTracing("trace-1", "span-1", "parent-1")
            .withCaller("node-1", "user-1")
            .withHeaders(Map.of("tenant", "tenant-a", "scope", "internal"));
    }

    private static void assertRpcContextMetadata(RpcRequestContext context, String version) {
        Assertions.assertNotNull(context);
        Assertions.assertEquals(version, context.version());
        Assertions.assertEquals("trace-1", context.traceId());
        Assertions.assertEquals("span-1", context.spanId());
        Assertions.assertEquals("parent-1", context.parentSpanId());
        Assertions.assertEquals("node-1", context.callerNodeId());
        Assertions.assertEquals("user-1", context.callerUserId());
        Assertions.assertEquals("tenant-a", context.headers().get("tenant"));
        Assertions.assertEquals("internal", context.headers().get("scope"));
    }

    private static void assertRpcMetaMetadata(RpcMeta meta, String version, boolean idempotent) {
        Assertions.assertEquals(version, meta.getServiceVersion());
        Assertions.assertEquals("trace-1", meta.getTraceId());
        Assertions.assertEquals("span-1", meta.getSpanId());
        Assertions.assertEquals("parent-1", meta.getParentSpanId());
        Assertions.assertEquals("node-1", meta.getCallerNodeId());
        Assertions.assertEquals("user-1", meta.getCallerUserId());
        Assertions.assertEquals("tenant-a", meta.getHeadersMap().get("tenant"));
        Assertions.assertEquals("internal", meta.getHeadersMap().get("scope"));
        Assertions.assertEquals(idempotent, meta.getIdempotent());
    }

    private static final class CapturingControlMessageService implements P2PMessageService {
        private final RpcEventCommandServerHandler eventHandler;
        private P2PWrapper<byte[]> lastControlRequest;
        private int canceledRequestId = -1;
        private int nextRequestId = 1000;

        private CapturingControlMessageService(RpcEventCommandServerHandler eventHandler) {
            this.eventHandler = eventHandler;
        }

        @Override
        public P2PWrapper excute(P2PWrapper request) throws Exception {
            if (request.getCommand() != P2PCommand.RPC_CONTROL) {
                throw new UnsupportedOperationException();
            }
            lastControlRequest = (P2PWrapper<byte[]>) request;
            RpcFrame requestFrame = RpcFrame.parseFrom((byte[]) request.getData());
            RpcStatusCode code = requestFrame.getFrameType() == RpcFrameType.CANCEL ? RpcStatusCode.CANCELED : RpcStatusCode.OK;
            String message = switch (requestFrame.getFrameType()) {
                case HEARTBEAT -> "alive";
                case WINDOW_UPDATE -> "window updated";
                default -> "canceled";
            };
            RpcFrame responseFrame = RpcFrame.newBuilder()
                .setMeta(requestFrame.getMeta())
                .setFrameType(RpcFrameType.CLOSE)
                .setStatus(javax.net.p2p.rpc.proto.RpcStatus.newBuilder()
                    .setCode(code)
                    .setMessage(message)
                    .setRetriable(false)
                    .build())
                .setEndOfStream(true)
                .build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_CONTROL, responseFrame.toByteArray());
        }

        @Override
        public void cancelExcute(int requestId) {
            canceledRequestId = requestId;
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper excute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<P2PWrapper> asyncExcute(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper streamRequest(P2PWrapper request, AbstractStreamResponseAdapter streamMessage) {
            StreamP2PWrapper<?> wrapper = (StreamP2PWrapper<?>) request;
            wrapper.setSeq(nextRequestId++);
            eventHandler.request(new TestExecutor(), wrapper);
            return P2PWrapper.build(wrapper.getSeq(), P2PCommand.STREAM_ACK, null);
        }

        @Override
        public Future<P2PWrapper> asyncStreamRequest(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper reTryRequest(ChannelAwaitOnMessage<P2PWrapper> responseFuture, P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AbstractSendMesageExecutor pollMesageExecutor(long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.net.InetSocketAddress getRemote() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConcurrentMap<Integer, ChannelAwaitOnMessage<P2PWrapper>> getResponseFutureMap() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putResponseFuture(int requestId, ChannelAwaitOnMessage<P2PWrapper> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeResponseFuture(int requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage pollChannelAwaitOnMessage(P2PWrapper request, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public P2PWrapper exception(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> removeWithException(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelAwaitOnMessage<P2PWrapper> getChannelAwaitOnMessage(P2PWrapper request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeExceptionally(P2PWrapper request, Exception e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete(P2PWrapper response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getTotalConnects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectSuccess(io.netty.channel.Channel channel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleConnectFailed(Exception ex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorQueueEmpty(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void handleMesageExecutorClose(AbstractSendMesageExecutor executor) {
            throw new UnsupportedOperationException();
        }
    }
}
