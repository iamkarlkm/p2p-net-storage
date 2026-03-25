package javax.net.p2p.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;

/**
 * xxhash 是一种非加密、速度极快、质量极高（SMHasher 得分为 10 分）的哈希函数。
 *
 * @author karl
 */
public class XXHashUtil {

    private static final XXHashFactory FACTORY = XXHashFactory.fastestInstance();
    private static final int SEED32 = 0x9747b28c;
    private static final long SEED64 = 0x9747b28c9747b28cL;

    public static void main(String[] args) throws UnsupportedEncodingException, IOException {

        byte[] data = "12345345234572".getBytes("UTF-8");
        ByteArrayInputStream in = new ByteArrayInputStream(data);

//int seed = 0x9747b28c; // used to initialize the hash value, use whatever
        // value you want, but always the same
        //StreamingXXHash32 hash32 = FACTORY.newStreamingHash32(SEED32);
        StreamingXXHash32 hash32 = ConcurrentStreamingXXHash32.get();
        byte[] buf = new byte[8]; // for real-world usage, use a larger buffer, like 8192 bytes
        for (;;) {
            int read = in.read(buf);
            if (read == -1) {
                break;
            }
            hash32.update(buf, 0, read);
        }
        int hash = hash32.getValue();
        System.out.println("12345345234572".hashCode() + ":" + hash);

        StreamingXXHash64 hash64 = FACTORY.newStreamingHash64(SEED64);

    }

    public static long hash64(byte[] data) {
        StreamingXXHash64 hash64 = ConcurrentStreamingXXHash64.get();
        hash64.update(data, 0, data.length);
        return hash64.getValue();
    }

    public static long hash64(byte[] data, int offset, int length) {
        StreamingXXHash64 hash64 = ConcurrentStreamingXXHash64.get();
        hash64.update(data, offset, length);
        return hash64.getValue();
    }

    public static int hash32(byte[] data) {
        StreamingXXHash32 hash32 = ConcurrentStreamingXXHash32.get();
        hash32.update(data, 0, data.length);
        return hash32.getValue();
    }

    public static int hash32(byte[] data, int offset, int length) {
        StreamingXXHash32 hash32 = ConcurrentStreamingXXHash32.get();
        hash32.update(data, offset, length);
        return hash32.getValue();
    }
    
    
    static class ConcurrentStreamingXXHash32 {

        private static final ThreadLocal<StreamingXXHash32> LOCAL_HASH = new ThreadLocal<>();

        private static StreamingXXHash32 get() {
            StreamingXXHash32 hash = LOCAL_HASH.get();
            if (hash == null) {
                hash = FACTORY.newStreamingHash32(SEED32);
                LOCAL_HASH.set(hash);
            }
            hash.reset();
            return hash;
        }

        private static void remove() throws IOException {
            LOCAL_HASH.remove();
        }
    }
    
    static class ConcurrentStreamingXXHash64 {

        private static final ThreadLocal<StreamingXXHash64> LOCAL_HASH = new ThreadLocal<>();

        private static StreamingXXHash64 get() {
            StreamingXXHash64 hash = LOCAL_HASH.get();
            if (hash == null) {
                hash = FACTORY.newStreamingHash64(SEED64);
                LOCAL_HASH.set(hash);
            }
            hash.reset();
            return hash;
        }

        private static void remove() throws IOException {
            LOCAL_HASH.remove();
        }
    }

 
}
