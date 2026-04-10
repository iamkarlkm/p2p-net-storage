package javax.net.p2p.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.Attribute;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.utils.AuthCrypto;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public class P2PWrapperSecureDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 8) {
            return;
        }
        in.markReaderIndex();
        int length = in.readInt();
        int magic = in.readInt();
        Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        Integer expected = attrMagic.get();
        if (expected == null || magic != expected) {
            in.skipBytes(in.readableBytes());
            P2PWrapper response = P2PWrapper.build(0, P2PCommand.INVALID_PROTOCOL, 0);
            ctx.writeAndFlush(response);
            return;
        }

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        byte[] payload = new byte[length];
        in.readBytes(payload);
        byte[] key = ctx.channel().attr(ChannelUtils.XOR_KEY).get();
        if (key != null && key.length > 0) {
            AuthCrypto.xorInPlace(payload, key);
        }
        P2PWrapper result = SerializationUtil.deserialize(P2PWrapper.class, payload);
        out.add(result);
    }
}

