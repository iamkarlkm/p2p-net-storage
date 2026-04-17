package p2pws.sdk;

import java.security.PrivateKey;
import p2pws.P2PControl;

public final class Handshake {

    private Handshake() {
    }

    public static P2PControl.HandAckPlain decryptHandAck(PrivateKey clientPrivateKey, byte[] encryptedHandAckData) {
        byte[] plain = RsaOaep.decryptSha256(clientPrivateKey, encryptedHandAckData);
        try {
            return P2PControl.HandAckPlain.parseFrom(plain);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

