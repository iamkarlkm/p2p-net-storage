package javax.net.p2p.server.handler;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.server.RpcBootstrap;

/**
 * RPC unary 请求入口。
 */
public class RpcUnaryCommandServerHandler implements P2PChannelAwareCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.RPC_UNARY;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        return process((P2PWrapper<byte[]>) request, null);
    }

    @Override
    public P2PWrapper process(ChannelHandlerContext ctx, P2PWrapper request) {
        return process((P2PWrapper<byte[]>) request, ctx == null ? null : ctx.channel());
    }

    private P2PWrapper process(P2PWrapper<byte[]> request, Channel channel) {
        try {
            if (request.getCommand() != P2PCommand.RPC_UNARY) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH, "指令分发内部校验错误！");
            }
            RpcFrame frame = RpcFrame.parseFrom(request.getData());
            RpcRequestContext context = RpcRequestContext.from(request, frame, channel);
            RpcFrame response = RpcBootstrap.dispatcher().dispatchUnary(context, frame);
            return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_UNARY, response.toByteArray());
        } catch (Exception ex) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, ex.toString());
        }
    }
}
