package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.server.RpcBootstrap;

/**
 * RPC unary 请求入口。
 */
public class RpcUnaryCommandServerHandler implements P2PCommandHandler<byte[]> {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_UNARY;
    }

    @Override
    public P2PWrapper process(P2PWrapper<byte[]> request) {
        try {
            if (request.getCommand() != P2PCommand.RPC_UNARY) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
            RpcFrame frame = RpcFrame.parseFrom(request.getData());
            RpcRequestContext context = RpcRequestContext.from(request, frame, null);
            RpcFrame response = RpcBootstrap.dispatcher().dispatchUnary(context, frame);
            return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_UNARY, response.toByteArray());
        } catch (Exception ex) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, ex.toString());
        }
    }
}
