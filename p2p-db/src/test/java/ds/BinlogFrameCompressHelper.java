package ds;

import com.q3lives.ds.database.columnar.ColumnCompression;

public final class BinlogFrameCompressHelper {

    public static final int LZ4_THRESHOLD = 65536;

    private BinlogFrameCompressHelper() {
    }

    public static byte[] maybeCompress(byte[] raw, int threshold) {
        if (raw == null || raw.length == 0) return raw;
        byte[] c = ColumnCompression.compress(raw, threshold);
        return c != null ? c : raw;
    }

    public static byte[][] maybeCompressAll(byte[] fixed, byte[] dyn, byte[] bytes, int threshold) {
        return new byte[][]{
                maybeCompress(fixed, threshold),
                maybeCompress(dyn, threshold),
                maybeCompress(bytes, threshold)
        };
    }
}
