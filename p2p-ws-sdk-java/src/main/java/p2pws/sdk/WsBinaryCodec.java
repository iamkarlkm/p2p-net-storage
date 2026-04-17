package p2pws.sdk;

import p2pws.P2PWrapperOuterClass;

public final class WsBinaryCodec {

    private WsBinaryCodec() {
    }

    public static byte[] encode(WireHeader header, P2PWrapperOuterClass.P2PWrapper wrapper, KeyFileProvider provider, byte[] keyId32, long offset) {
        byte[] plain = P2PWrapperCodec.encode(wrapper);
        byte[] cipher = XorCipher.xorWithKeyFile(plain, provider, keyId32, offset);
        WireHeader h = new WireHeader(cipher.length, header.magic(), header.version(), header.flags());
        return FrameCodec.encode(h, cipher);
    }

    public static P2PWrapperOuterClass.P2PWrapper decode(byte[] wsBinaryPayload, KeyFileProvider provider, byte[] keyId32, long offset) {
        WireFrame f = FrameCodec.decode(wsBinaryPayload);
        byte[] plain = XorCipher.xorWithKeyFile(f.cipherPayload(), provider, keyId32, offset);
        return P2PWrapperCodec.decode(plain);
    }
}
