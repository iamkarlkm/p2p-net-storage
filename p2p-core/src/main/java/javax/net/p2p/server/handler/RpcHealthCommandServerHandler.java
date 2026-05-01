package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.proto.HealthCheckRequest;
import javax.net.p2p.rpc.proto.HealthCheckResponse;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.server.RpcFrames;

/**
 * RPC 健康检查入口。
 */
public class RpcHealthCommandServerHandler implements P2PCommandHandler<byte[]> {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_HEALTH;
    }

    @Override
    public P2PWrapper process(P2PWrapper<byte[]> request) {
        try {
            if (request.getCommand() != P2PCommand.RPC_HEALTH) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
            RpcFrame frame = RpcFrame.parseFrom(request.getData());
            HealthCheckRequest healthRequest = HealthCheckRequest.parseFrom(frame.getPayload());
            HealthCheckResponse response = HealthCheckResponse.newBuilder()
                .setHealthy(true)
                .setReady(true)
                .setMessage(healthRequest.getService().isBlank() ? "ok" : "ok:" + healthRequest.getService())
                .build();
            RpcFrame rpcResponse = RpcFrames.ok(frame, response.toByteArray(), true);
            return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_HEALTH, rpcResponse.toByteArray());
        } catch (Exception ex) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, ex.toString());
        }
    }
}
