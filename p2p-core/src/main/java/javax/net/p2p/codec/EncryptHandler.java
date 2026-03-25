
package javax.net.p2p.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class EncryptHandler extends MessageToMessageEncoder<ByteBuf> {
//    private static SecretKeySpec secretKey;
//    private static byte[] key = Constants.SK.getBytes();
//    private static Cipher cipher;

//    static {
//        MessageDigest sha = null;
//        try {
//            sha = MessageDigest.getInstance(&quot;SHA-1&quot;);
//            key = sha.digest(key);
//            key = Arrays.copyOf(key, 16);
//            secretKey = new SecretKeySpec(key, &quot;AES&quot;);
//            cipher = Cipher.getInstance(&quot;AES/ECB/PKCS5Padding&quot;);
//            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
//        } catch (Exception e) {
//            log.error(&quot;&quot;, e);
//        }
//    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        //byte[] array = new byte[msg.readableBytes()];
        //msg.getBytes(msg.readerIndex(), array);
        // 调用cipher的api对数据完成加密
        //out.add(Unpooled.copiedBuffer(cipher.doFinal(array)));
    }
}
