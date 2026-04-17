package p2pws.sdk;

public final class XorCipher {

    private XorCipher() {
    }

    public static byte[] xor(byte[] input, byte[] keySlice) {
        if (input == null) {
            throw new IllegalArgumentException("input required");
        }
        if (keySlice == null) {
            throw new IllegalArgumentException("keySlice required");
        }
        if (keySlice.length < input.length) {
            throw new IllegalArgumentException("keySlice shorter than input");
        }
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = (byte) (input[i] ^ keySlice[i]);
        }
        return out;
    }

    public static byte[] xorWithKeyFile(byte[] input, KeyFileProvider provider, byte[] keyId32, long offset) {
        if (input == null) {
            throw new IllegalArgumentException("input required");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider required");
        }
        if (keyId32 == null || keyId32.length != 32) {
            throw new IllegalArgumentException("keyId must be 32 bytes");
        }
        byte[] keySlice = provider.read(keyId32, offset, input.length);
        return xor(input, keySlice);
    }
}

