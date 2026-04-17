package p2pws.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameVectorTest {

    @Test
    public void frame_vector_001() throws Exception {
        Path p = Path.of("..", "p2p-ws-protocol", "test-vectors", "frame_vector_001.json").normalize();
        JsonNode v = new ObjectMapper().readTree(Files.readString(p));

        byte[] ws = hex(v.get("ws_binary_payload_hex").asText());
        WireFrame f = FrameCodec.decode(ws);

        JsonNode h = v.get("header");
        assertEquals(h.get("length_u32").asLong(), f.header().length());
        assertEquals(h.get("magic_u16").asInt(), f.header().magic());
        assertEquals(h.get("version_u8").asInt(), f.header().version());
        assertEquals(h.get("flags_u8").asInt(), f.header().flags());
        assertArrayEquals(hex(v.get("cipher_payload_hex").asText()), f.cipherPayload());
    }

    private static byte[] hex(String s) {
        int n = s.length();
        if ((n & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}

