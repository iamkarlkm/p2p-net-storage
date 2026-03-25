package javax.net.p2p.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

import javax.net.p2p.config.P2PConfig;

/**
 * 传输层数据包尺寸限制(应用中发现某些中间路由可能有限制,导致连接卡死,超时等)
 *
 * @author karl
 */
@Slf4j
public class TransportLimitEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        int length = msg.readableBytes();
        System.out.println("TransportLimitEncoder:"+P2PConfig.TRANSPORT_LIMIT_SIZE);
        if (length > P2PConfig.TRANSPORT_LIMIT_SIZE) {
            //分包发送,以防止中间网络路由问题导致传输问题(超时),实测数据域映射端口超过64k tcp包经常超时
            int rest = (int) length % P2PConfig.TRANSPORT_LIMIT_SIZE;
            int count = (int) length / P2PConfig.TRANSPORT_LIMIT_SIZE;
            //System.out.println(bytes.length+" -> "+count+" rest "+rest);
            int start = msg.readerIndex();
            for (int i = 0; i < count; i++) {
                out.writeBytes(msg.slice(start + i * P2PConfig.TRANSPORT_LIMIT_SIZE, P2PConfig.TRANSPORT_LIMIT_SIZE));
                ctx.flush();
            }
            if (rest > 0) {
                out.writeBytes(msg.slice(start + count * P2PConfig.TRANSPORT_LIMIT_SIZE, rest));
                ctx.flush();
            }
        } else {
            out.writeBytes(msg);
            ctx.flush();
        }
    }
}
