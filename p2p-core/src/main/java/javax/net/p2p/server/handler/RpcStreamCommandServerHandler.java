package javax.net.p2p.server.handler;

import com.google.protobuf.Message;
import java.lang.reflect.Method;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.interfaces.StreamRequest;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.api.RpcBidiStreamInvoker;
import javax.net.p2p.rpc.api.RpcBidiStreamSession;
import javax.net.p2p.rpc.api.RpcClientStreamInvoker;
import javax.net.p2p.rpc.api.RpcClientStreamSession;
import javax.net.p2p.rpc.model.RpcMethodDescriptor;
import javax.net.p2p.rpc.model.RpcMethodKey;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.server.RpcBootstrap;
import javax.net.p2p.rpc.server.RpcFrames;
import javax.net.p2p.rpc.server.RpcQueuedFrameSender;
import javax.net.p2p.rpc.server.RpcServerResponseObserver;
import javax.net.p2p.rpc.server.RpcServerStreamHandler;

/**
 * RPC_STREAM 统一入口，按 callType 分发到 server/client/bidi stream。
 */
public class RpcStreamCommandServerHandler extends AbstractStreamRequestAdapter implements StreamRequest {
    private StreamSession session;

    @Override
    public void clear() {
        super.clear();
        // 处理器实例会经对象池复用，必须清掉上一次流会话，避免跨 seq 串状态。
        this.session = null;
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_STREAM;
    }

    @Override
    public StreamP2PWrapper request(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        RpcFrame frame = null;
        try {
            frame = RpcFrame.parseFrom((byte[]) message.getData());
            if (session == null) {
                session = createSession(executor, message, frame);
                if (session == null) {
                    return null;
                }
            }
            session.onFrame(message, frame);
            return null;
        } catch (Exception ex) {
            continued = false;
            try {
                sendError(executor, message, frame, RpcStatusCode.INTERNAL_ERROR, ex.getMessage());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    @Override
    public void cancel(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        continued = false;
        if (session != null) {
            session.onCancel();
        }
    }

    @Override
    public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) {
    }

    private StreamSession createSession(AbstractSendMesageExecutor executor, StreamP2PWrapper message, RpcFrame frame) throws Exception {
        RpcRequestContext context = RpcRequestContext.from(message, frame, executor.getChannel());
        if (context.isDeadlineExceeded(System.currentTimeMillis())) {
            continued = false;
            sendError(executor, message, frame, RpcStatusCode.DEADLINE_EXCEEDED, "deadline exceeded");
            return null;
        }
        if (frame.getMeta().getCallType() == RpcCallType.SERVER_STREAM) {
            continued = false;
            new RpcServerStreamHandler().processStream(executor, message);
            return null;
        }
        RpcMethodDescriptor descriptor = RpcBootstrap.registry().find(new RpcMethodKey(
            context.service(),
            context.method(),
            context.version()
        ));
        if (descriptor == null) {
            continued = false;
            sendError(executor, message, frame, RpcStatusCode.NOT_FOUND, "RPC 方法不存在");
            return null;
        }
        if (descriptor.callType() == RpcCallType.CLIENT_STREAM) {
            return new ClientStreamSession(executor, message, frame, descriptor, context);
        }
        if (descriptor.callType() == RpcCallType.BIDI_STREAM) {
            return new BidiStreamSession(executor, message, frame, descriptor, context);
        }
        continued = false;
        sendError(executor, message, frame, RpcStatusCode.METHOD_NOT_ALLOWED, "unsupported rpc stream call type");
        return null;
    }

    private void sendError(
        AbstractSendMesageExecutor executor,
        StreamP2PWrapper<?> message,
        RpcFrame requestFrame,
        RpcStatusCode code,
        String errorMessage
    ) throws InterruptedException {
        RpcFrame frame = RpcFrames.error(requestFrame == null ? RpcFrame.getDefaultInstance() : requestFrame, code, errorMessage, false);
        executor.sendResponse(StreamP2PWrapper.buildStream(
            message.getSeq(),
            message.getIndex(),
            P2PCommand.RPC_STREAM,
            frame.toByteArray(),
            true
        ));
    }

    private static Message parseMessage(Class<? extends Message> messageType, byte[] payload) throws Exception {
        Method parseFrom = messageType.getMethod("parseFrom", byte[].class);
        return (Message) parseFrom.invoke(null, payload == null ? new byte[0] : payload);
    }

    private static RpcStatus resolveResponseStatus(RpcMethodDescriptor descriptor, Message response) {
        @SuppressWarnings("unchecked")
        javax.net.p2p.rpc.api.RpcResponseStatusResolver<Message> resolver =
            (javax.net.p2p.rpc.api.RpcResponseStatusResolver<Message>) descriptor.responseStatusResolver();
        if (resolver == null) {
            return RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build();
        }
        RpcStatus status = resolver.resolve(response);
        return status == null ? RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build() : status;
    }

    private static boolean isPayloadFrame(RpcFrame frame) {
        return frame.getFrameType() == RpcFrameType.OPEN || frame.getFrameType() == RpcFrameType.DATA;
    }

    private static boolean isInputCompleted(StreamP2PWrapper<?> message, RpcFrame frame) {
        return message.isCompleted() || frame.getFrameType() == RpcFrameType.CLOSE || frame.getEndOfStream();
    }

    private interface StreamSession {
        void onFrame(StreamP2PWrapper<?> message, RpcFrame frame) throws Exception;

        void onCancel();
    }

    private final class ClientStreamSession implements StreamSession {
        private final RpcMethodDescriptor descriptor;
        private final RpcFrame requestFrame;
        private final RpcQueuedFrameSender frameSender;
        private final RpcClientStreamSession<Message, Message> invokerSession;
        private final boolean requestFlowControlEnabled;
        private final int requestWindowBatch;
        private int remainingRequestPermits;
        private int consumedSinceWindowUpdate;
        private boolean completed;

        private ClientStreamSession(
            AbstractSendMesageExecutor executor,
            StreamP2PWrapper<?> message,
            RpcFrame requestFrame,
            RpcMethodDescriptor descriptor,
            RpcRequestContext context
        ) throws Exception {
            this.descriptor = descriptor;
            this.requestFrame = requestFrame;
            this.frameSender = new RpcQueuedFrameSender(executor, message.getSeq(), P2PCommand.RPC_STREAM, requestFrame, null);
            this.requestFlowControlEnabled = requestFrame.hasFlowControl() && requestFrame.getFlowControl().getPermits() > 0;
            this.requestWindowBatch = requestFlowControlEnabled ? requestFrame.getFlowControl().getPermits() : 0;
            this.remainingRequestPermits = requestFlowControlEnabled ? requestFrame.getFlowControl().getPermits() : Integer.MAX_VALUE;
            @SuppressWarnings("unchecked")
            RpcClientStreamInvoker<Message, Message> invoker = (RpcClientStreamInvoker<Message, Message>) descriptor.invoker();
            this.invokerSession = invoker.open(context);
        }

        @Override
        public void onFrame(StreamP2PWrapper<?> message, RpcFrame frame) throws Exception {
            if (completed) {
                return;
            }
            if (isPayloadFrame(frame) && !frame.getPayload().isEmpty()) {
                if (!tryConsumeRequestPermit()) {
                    completeWithRequestBackpressureError();
                    return;
                }
                invokerSession.onNext(parseMessage(descriptor.requestType(), frame.getPayload().toByteArray()));
                maybeSendRequestWindowUpdate();
            }
            if (!isInputCompleted(message, frame)) {
                return;
            }
            completed = true;
            continued = false;
            Message response = invokerSession.onCompleted();
            frameSender.send(
                RpcFrames.complete(requestFrame, response == null ? new byte[0] : response.toByteArray(), resolveResponseStatus(descriptor, response), true)
                    .toBuilder()
                    .setChunkIndex(0)
                    .setEndOfMessage(true)
                    .build(),
                true
            );
        }

        @Override
        public void onCancel() {
            try {
                invokerSession.onCancel();
            } catch (Exception ignored) {
            } finally {
                frameSender.close();
            }
        }

        private boolean tryConsumeRequestPermit() {
            if (!requestFlowControlEnabled) {
                return true;
            }
            if (remainingRequestPermits <= 0) {
                return false;
            }
            remainingRequestPermits--;
            consumedSinceWindowUpdate++;
            return true;
        }

        private void maybeSendRequestWindowUpdate() throws Exception {
            if (!requestFlowControlEnabled || consumedSinceWindowUpdate < requestWindowBatch) {
                return;
            }
            int permits = consumedSinceWindowUpdate;
            consumedSinceWindowUpdate = 0;
            remainingRequestPermits += permits;
            frameSender.sendBypassWindow(RpcFrame.newBuilder()
                .setMeta(requestFrame.getMeta())
                .setFrameType(RpcFrameType.WINDOW_UPDATE)
                .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(permits).build())
                .setEndOfStream(false)
                .build(), false);
        }

        private void completeWithRequestBackpressureError() throws Exception {
            completed = true;
            continued = false;
            frameSender.sendBypassWindow(
                RpcFrames.error(requestFrame, RpcStatusCode.TOO_MANY_REQUESTS, "request stream window exhausted", false),
                true
            );
        }
    }

    private final class BidiStreamSession implements StreamSession {
        private final RpcMethodDescriptor descriptor;
        private final RpcFrame requestFrame;
        private final RpcQueuedFrameSender frameSender;
        private final RpcBidiStreamSession<Message, Message> invokerSession;
        private final boolean requestFlowControlEnabled;
        private final int requestWindowBatch;
        private int remainingRequestPermits;
        private int consumedSinceWindowUpdate;
        private boolean completed;

        private BidiStreamSession(
            AbstractSendMesageExecutor executor,
            StreamP2PWrapper<?> message,
            RpcFrame requestFrame,
            RpcMethodDescriptor descriptor,
            RpcRequestContext context
        ) throws Exception {
            this.descriptor = descriptor;
            this.requestFrame = requestFrame;
            this.frameSender = new RpcQueuedFrameSender(executor, message.getSeq(), P2PCommand.RPC_STREAM, requestFrame, null);
            this.requestFlowControlEnabled = requestFrame.hasFlowControl() && requestFrame.getFlowControl().getPermits() > 0;
            this.requestWindowBatch = requestFlowControlEnabled ? requestFrame.getFlowControl().getPermits() : 0;
            this.remainingRequestPermits = requestFlowControlEnabled ? requestFrame.getFlowControl().getPermits() : Integer.MAX_VALUE;
            @SuppressWarnings("unchecked")
            RpcBidiStreamInvoker<Message, Message> invoker = (RpcBidiStreamInvoker<Message, Message>) descriptor.invoker();
            this.invokerSession = invoker.open(context, new RpcServerResponseObserver(frameSender, requestFrame));
        }

        @Override
        public void onFrame(StreamP2PWrapper<?> message, RpcFrame frame) throws Exception {
            if (completed) {
                return;
            }
            if (isPayloadFrame(frame) && !frame.getPayload().isEmpty()) {
                if (!tryConsumeRequestPermit()) {
                    completeWithRequestBackpressureError();
                    return;
                }
                invokerSession.onNext(parseMessage(descriptor.requestType(), frame.getPayload().toByteArray()));
                maybeSendRequestWindowUpdate();
            }
            if (!isInputCompleted(message, frame)) {
                return;
            }
            completed = true;
            continued = false;
            invokerSession.onCompleted();
        }

        @Override
        public void onCancel() {
            try {
                invokerSession.onCancel();
            } catch (Exception ignored) {
            } finally {
                frameSender.close();
            }
        }

        private boolean tryConsumeRequestPermit() {
            if (!requestFlowControlEnabled) {
                return true;
            }
            if (remainingRequestPermits <= 0) {
                return false;
            }
            remainingRequestPermits--;
            consumedSinceWindowUpdate++;
            return true;
        }

        private void maybeSendRequestWindowUpdate() throws Exception {
            if (!requestFlowControlEnabled || consumedSinceWindowUpdate < requestWindowBatch) {
                return;
            }
            int permits = consumedSinceWindowUpdate;
            consumedSinceWindowUpdate = 0;
            remainingRequestPermits += permits;
            frameSender.sendBypassWindow(RpcFrame.newBuilder()
                .setMeta(requestFrame.getMeta())
                .setFrameType(RpcFrameType.WINDOW_UPDATE)
                .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(permits).build())
                .setEndOfStream(false)
                .build(), false);
        }

        private void completeWithRequestBackpressureError() throws Exception {
            completed = true;
            continued = false;
            frameSender.sendBypassWindow(
                RpcFrames.error(requestFrame, RpcStatusCode.TOO_MANY_REQUESTS, "request stream window exhausted", false),
                true
            );
        }
    }
}
