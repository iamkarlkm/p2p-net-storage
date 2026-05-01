package javax.net.p2p.rpc.api;

import com.google.protobuf.Message;
import java.util.concurrent.CompletableFuture;
import javax.net.p2p.rpc.model.RpcCallOptions;

/**
 * RPC 客户端统一入口。
 */
public interface RpcClient {

    <Req extends Message, Resp extends Message> Resp unary(
        String service,
        String method,
        Req request,
        Class<Resp> responseType,
        RpcCallOptions options
    ) throws Exception;

    <Req extends Message, Resp extends Message> CompletableFuture<Resp> unaryAsync(
        String service,
        String method,
        Req request,
        Class<Resp> responseType,
        RpcCallOptions options
    );
}
