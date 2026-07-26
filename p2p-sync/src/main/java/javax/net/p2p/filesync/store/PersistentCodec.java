package javax.net.p2p.filesync.store;

public interface PersistentCodec<T> {
    String encode(T value);

    T decode(String encoded);

    static PersistentCodec<String> stringCodec() {
        return new PersistentCodec<String>() {
            @Override
            public String encode(String value) {
                return value == null ? "" : value;
            }

            @Override
            public String decode(String encoded) {
                return encoded;
            }
        };
    }
}
