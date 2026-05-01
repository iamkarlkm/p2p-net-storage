package javax.net.p2p.rpc.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcFlowControl;
import javax.net.p2p.rpc.proto.RpcFrame;

/**
 * 统一封装 RPC 流帧的发送、排队与窗口控制。
 */
public final class RpcQueuedFrameSender implements RpcServerStreamControlRegistry.WindowController {
    private final AbstractSendMesageExecutor executor;
    private final int seq;
    private final P2PCommand command;
    private final Deque<PendingFrame> pendingFrames = new ArrayDeque<>();
    private final Object flowLock = new Object();
    private final RpcFlowWindowState flowWindowState;
    private final Runnable cleanupHook;
    private int index;
    private boolean cleaned;

    public RpcQueuedFrameSender(
        AbstractSendMesageExecutor executor,
        int seq,
        P2PCommand command,
        RpcFrame requestFrame,
        Runnable cleanupHook
    ) {
        this.executor = executor;
        this.seq = seq;
        this.command = command;
        this.flowWindowState = RpcFlowWindowState.fromRequest(requestFrame);
        this.cleanupHook = cleanupHook;
        if (flowWindowState.enabled()) {
            RpcServerStreamControlRegistry.register(seq, this);
        }
    }

    public int maxFrameBytes() {
        return flowWindowState.maxFrameBytes();
    }

    public void sendChunkedPayload(RpcFrame requestFrame, byte[] payload) throws Exception {
        sendFrames(RpcFrames.chunkDataFrames(requestFrame, payload, maxFrameBytes()), false);
    }

    public void sendFrames(List<RpcFrame> frames, boolean terminal) throws Exception {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        int lastIndex = frames.size() - 1;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            send(frames.get(frameIndex), terminal && frameIndex == lastIndex);
        }
    }

    public void send(RpcFrame frame, boolean terminal) throws Exception {
        synchronized (flowLock) {
            int currentIndex = index++;
            if (!flowWindowState.enabled()) {
                sendFrame(frame, currentIndex, terminal);
                if (terminal) {
                    cleanupLocked();
                }
                return;
            }
            if (!terminal && pendingFrames.isEmpty() && flowWindowState.tryConsumePermit()) {
                sendFrame(frame, currentIndex, false);
                return;
            }
            if (terminal && pendingFrames.isEmpty() && flowWindowState.hasAvailablePermit()) {
                sendFrame(frame, currentIndex, true);
                cleanupLocked();
                return;
            }
            pendingFrames.addLast(new PendingFrame(frame, currentIndex, terminal));
            flushPendingLocked();
        }
    }

    public void sendBypassWindow(RpcFrame frame, boolean terminal) throws Exception {
        synchronized (flowLock) {
            sendFrame(frame, index++, terminal);
            if (terminal) {
                cleanupLocked();
            }
        }
    }

    @Override
    public void applyWindowUpdate(RpcFlowControl flowControl) throws Exception {
        synchronized (flowLock) {
            flowWindowState.applyWindowUpdate(flowControl);
            flushPendingLocked();
        }
    }

    public void close() {
        synchronized (flowLock) {
            pendingFrames.clear();
            cleanupLocked();
        }
    }

    private void flushPendingLocked() throws Exception {
        while (!pendingFrames.isEmpty()) {
            PendingFrame pending = pendingFrames.peekFirst();
            if (!pending.terminal && !flowWindowState.hasAvailablePermit()) {
                return;
            }
            pendingFrames.removeFirst();
            if (!pending.terminal && !flowWindowState.tryConsumePermit()) {
                return;
            }
            sendFrame(pending.frame, pending.index, pending.terminal);
            if (pending.terminal) {
                cleanupLocked();
                return;
            }
        }
    }

    private void sendFrame(RpcFrame frame, int frameIndex, boolean terminal) throws Exception {
        executor.sendResponse(StreamP2PWrapper.buildStream(
            seq,
            frameIndex,
            command,
            frame.toByteArray(),
            terminal
        ));
    }

    private void cleanupLocked() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        if (flowWindowState.enabled()) {
            RpcServerStreamControlRegistry.remove(seq, this);
        }
        if (cleanupHook != null) {
            cleanupHook.run();
        }
    }

    private static final class PendingFrame {
        final RpcFrame frame;
        final int index;
        final boolean terminal;

        private PendingFrame(RpcFrame frame, int index, boolean terminal) {
            this.frame = frame;
            this.index = index;
            this.terminal = terminal;
        }
    }
}
