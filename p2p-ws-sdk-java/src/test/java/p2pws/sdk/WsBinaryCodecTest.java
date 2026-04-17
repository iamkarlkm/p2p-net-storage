package p2pws.sdk;

import java.util.Arrays;
import org.junit.Test;
import p2pws.P2PWrapperOuterClass;
import static org.junit.Assert.*;

public class WsBinaryCodecTest {

    @Test
    public void encode_decode_roundtrip() {
        byte[] keyfile = new byte[1024];
        for (int i = 0; i < keyfile.length; i++) {
            keyfile[i] = (byte) (i & 0xFF);
        }
        byte[] keyId = new byte[32];
        Arrays.fill(keyId, (byte) 7);
        InMemoryKeyFileProvider provider = new InMemoryKeyFileProvider();
        provider.put(keyId, keyfile);

        P2PWrapperOuterClass.P2PWrapper w = P2PWrapperOuterClass.P2PWrapper.newBuilder()
            .setSeq(1)
            .setCommand(123)
            .setData(com.google.protobuf.ByteString.copyFrom(new byte[] {1, 2, 3}))
            .build();

        WireHeader header = new WireHeader(0, 0x1234, 1, 1);
        byte[] ws = WsBinaryCodec.encode(header, w, provider, keyId, 10);
        P2PWrapperOuterClass.P2PWrapper back = WsBinaryCodec.decode(ws, provider, keyId, 10);

        assertEquals(1, back.getSeq());
        assertEquals(123, back.getCommand());
        assertArrayEquals(new byte[] {1, 2, 3}, back.getData().toByteArray());
    }
}

