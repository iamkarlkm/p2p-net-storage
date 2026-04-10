package javax.net.p2p.utils;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class SerializationUtilRefCntTest {

    @Test
    public void testSerializeToByteBuf_HeaderLengthAndRefCnt() {
        ByteBuf buf = null;
        try {
            int magic = 12345678;
            P2PWrapper msg = P2PWrapper.build(1, P2PCommand.ECHO, "hello");
            buf = SerializationUtil.serializeToByteBuf(msg, magic);

            assertNotNull(buf);
            assertEquals(1, buf.refCnt());

            assertTrue(buf.readableBytes() >= 8);
            int base = buf.readerIndex();
            int len = buf.getInt(base);
            int magicNow = buf.getInt(base + 4);
            assertEquals(magic, magicNow);
            int payloadLenByIndex = buf.writerIndex() - base - 8;
            assertEquals(len, payloadLenByIndex);

            buf.skipBytes(8);
            assertEquals(len, buf.readableBytes());

            byte[] payload = new byte[len];
            buf.readBytes(payload);
            P2PWrapper decoded = SerializationUtil.deserialize(P2PWrapper.class, payload);
            assertEquals(msg.getCommand(), decoded.getCommand());
            assertEquals(String.valueOf(msg.getData()), String.valueOf(decoded.getData()));
        } finally {
            if (buf != null) {
                buf.clear();
                ReferenceCountUtil.safeRelease(buf);
            }
        }
    }

    @Test
    public void testSerializeToByteBuf_NoRetainOverloadAlsoWorks() {
        ByteBuf buf = null;
        try {
            int magic = 12345678;
            P2PWrapper msg = P2PWrapper.build(1, P2PCommand.ECHO, "hello");
            buf = SerializationUtil.serializeToByteBuf(msg, magic, 512);

            assertNotNull(buf);
            assertEquals(1, buf.refCnt());
            assertTrue(buf.readableBytes() >= 8);
            int base = buf.readerIndex();
            int len = buf.getInt(base);
            int magicNow = buf.getInt(base + 4);
            assertEquals(magic, magicNow);
            int payloadLenByIndex = buf.writerIndex() - base - 8;
            assertEquals(len, payloadLenByIndex);
            buf.skipBytes(8);
            assertEquals(len, buf.readableBytes());
        } finally {
            if (buf != null) {
                buf.clear();
                ReferenceCountUtil.safeRelease(buf);
            }
        }
    }
}
