package p2pws.sdk;

import p2pws.P2PWrapperOuterClass;

public final class P2PWrapperCodec {

    private P2PWrapperCodec() {
    }

    public static byte[] encode(P2PWrapperOuterClass.P2PWrapper msg) {
        return msg.toByteArray();
    }

    public static P2PWrapperOuterClass.P2PWrapper decode(byte[] data) {
        try {
            return P2PWrapperOuterClass.P2PWrapper.parseFrom(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

