package javax.net.p2p.test;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

public final class ProtostuffWireDumpMain {
    private ProtostuffWireDumpMain() {
    }

    public static void main(String[] args) {
        byte[] data = new byte[] {1, 2, 3, 4};
        StreamP2PWrapper<byte[]> w = StreamP2PWrapper.buildStream(1, 0, P2PCommand.HAND, data, false);
        byte[] payload = SerializationUtil.serialize(w);
        System.out.println(toHex(payload));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(String.format("%02x", v));
        }
        return sb.toString();
    }
}

