package p2pws.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class XorVectorTest {

    @Test
    public void xor_vector_001() throws Exception {
        Path p = Path.of("..", "p2p-ws-protocol", "test-vectors", "xor_vector_001.json").normalize();
        String s = Files.readString(p);
        ObjectMapper om = new ObjectMapper();
        JsonNode v = om.readTree(s);
        byte[] key = hex(v.get("keyfile_bytes_hex").asText());
        int offset = v.get("offset").asInt();
        byte[] plain = hex(v.get("plain_hex").asText());
        byte[] expected = hex(v.get("cipher_hex").asText());

        byte[] slice = new byte[plain.length];
        System.arraycopy(key, offset, slice, 0, plain.length);
        byte[] got = XorCipher.xor(plain, slice);
        assertArrayEquals(expected, got);
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

