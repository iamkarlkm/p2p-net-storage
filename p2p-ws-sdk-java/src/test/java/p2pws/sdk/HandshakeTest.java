package p2pws.sdk;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.Test;
import p2pws.P2PControl;
import static org.junit.Assert.*;

public class HandshakeTest {

    @Test
    public void decrypt_hand_ack_plain() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        byte[] sessionId = new byte[16];
        for (int i = 0; i < sessionId.length; i++) {
            sessionId[i] = (byte) i;
        }
        byte[] keyId = new byte[32];
        for (int i = 0; i < keyId.length; i++) {
            keyId[i] = (byte) (255 - i);
        }

        P2PControl.HandAckPlain plain = P2PControl.HandAckPlain.newBuilder()
            .setSessionId(com.google.protobuf.ByteString.copyFrom(sessionId))
            .setSelectedKeyId(com.google.protobuf.ByteString.copyFrom(keyId))
            .setOffset(1234)
            .setMaxFramePayload(4 * 1024 * 1024)
            .setHeaderPolicyId(0)
            .build();

        byte[] encrypted = RsaOaep.encryptSha256(kp.getPublic(), plain.toByteArray());
        P2PControl.HandAckPlain decoded = Handshake.decryptHandAck(kp.getPrivate(), encrypted);
        assertArrayEquals(sessionId, decoded.getSessionId().toByteArray());
        assertArrayEquals(keyId, decoded.getSelectedKeyId().toByteArray());
        assertEquals(1234, decoded.getOffset());
        assertEquals(4 * 1024 * 1024, decoded.getMaxFramePayload());
    }
}

