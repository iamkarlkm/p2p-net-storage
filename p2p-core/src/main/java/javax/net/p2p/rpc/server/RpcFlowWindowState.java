package javax.net.p2p.rpc.server;

import javax.net.p2p.rpc.proto.RpcFlowControl;
import javax.net.p2p.rpc.proto.RpcFrame;

/**
 * RPC 流窗口状态，统一维护 permits / inflight 上限 / 单帧大小上限。
 */
final class RpcFlowWindowState {
    private final boolean enabled;
    private int permits;
    private int maxInflightFrames;
    private int maxFrameBytes;

    private RpcFlowWindowState(boolean enabled, int permits, int maxInflightFrames, int maxFrameBytes) {
        this.enabled = enabled;
        this.permits = enabled ? permits : Integer.MAX_VALUE;
        this.maxInflightFrames = enabled ? maxInflightFrames : Integer.MAX_VALUE;
        this.maxFrameBytes = enabled ? maxFrameBytes : Integer.MAX_VALUE;
        clampPermits();
    }

    static RpcFlowWindowState fromRequest(RpcFrame requestFrame) {
        boolean enabled = requestFrame != null && requestFrame.hasFlowControl();
        RpcFlowControl flowControl = enabled ? requestFrame.getFlowControl() : null;
        return new RpcFlowWindowState(
            enabled,
            flowControl == null ? 0 : Math.max(0, flowControl.getPermits()),
            flowControl == null ? 0 : Math.max(0, flowControl.getMaxInflightFrames()),
            flowControl == null ? 0 : Math.max(0, flowControl.getMaxFrameBytes())
        );
    }

    boolean enabled() {
        return enabled;
    }

    int maxFrameBytes() {
        return maxFrameBytes;
    }

    boolean hasAvailablePermit() {
        return permits > 0;
    }

    boolean tryConsumePermit() {
        if (permits <= 0) {
            return false;
        }
        permits--;
        return true;
    }

    void consumePermit() {
        permits--;
    }

    void applyWindowUpdate(RpcFlowControl flowControl) {
        if (!enabled || flowControl == null) {
            return;
        }
        int updatedMaxInflightFrames = Math.max(0, flowControl.getMaxInflightFrames());
        if (updatedMaxInflightFrames > 0) {
            maxInflightFrames = updatedMaxInflightFrames;
        }
        int updatedMaxFrameBytes = Math.max(0, flowControl.getMaxFrameBytes());
        if (updatedMaxFrameBytes > 0) {
            maxFrameBytes = updatedMaxFrameBytes;
        }
        permits += Math.max(0, flowControl.getPermits());
        clampPermits();
    }

    private void clampPermits() {
        if (maxInflightFrames > 0) {
            permits = Math.min(permits, maxInflightFrames);
        }
    }
}
