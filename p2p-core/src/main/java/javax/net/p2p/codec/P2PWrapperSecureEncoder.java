package javax.net.p2p.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.util.Attribute;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

@ChannelHandler.Sharable
public class P2PWrapperSecureEncoder extends MessageToByteEncoder<P2PWrapper> {

    @Override
    protected void encode(ChannelHandlerContext ctx, P2PWrapper msg, ByteBuf out) throws Exception {
        Attribute<Integer> attributeMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        int start = out.writerIndex();
        SerializationUtil.serialize(msg, attributeMagic.get(), out);

        byte[] key = ctx.channel().attr(ChannelUtils.XOR_KEY).get();
        if (key == null || key.length == 0) {
            return;
        }
        if (msg.getCommand() == P2PCommand.HAND) {
            return;
        }
        Boolean plain = ctx.channel().attr(ChannelUtils.HANDSHAKE_PLAINTEXT_RESP).get();
        if (plain != null && plain) {
            ctx.channel().attr(ChannelUtils.HANDSHAKE_PLAINTEXT_RESP).set(false);
            return;
        }

        int payloadStart = start + 8;
        int end = out.writerIndex();
        int keyLen = key.length;
        int j = 0;
        for (int i = payloadStart; i < end; i++) {
            out.setByte(i, out.getByte(i) ^ key[j]);
            j++;
            if (j >= keyLen) {
                j = 0;
            }
        }
    }
}
