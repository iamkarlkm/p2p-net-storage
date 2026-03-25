
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.StreamingXXHash64;
import net.jpountz.xxhash.XXHashFactory;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *xxhash 是一种非加密、速度极快、质量极高（SMHasher 得分为 10 分）的哈希函数。
 * @author karl
 */
public class XXHash {
    
    public static void main(String[] args) throws UnsupportedEncodingException, IOException {
        XXHashFactory factory = XXHashFactory.fastestInstance();

byte[] data = "12345345234572".getBytes("UTF-8");
ByteArrayInputStream in = new ByteArrayInputStream(data);

//int seed = 0x9747b28c; // used to initialize the hash value, use whatever
                       // value you want, but always the same
                       int seed = "12345345234572".hashCode();
StreamingXXHash32 hash32 = factory.newStreamingHash32(seed);
byte[] buf = new byte[8]; // for real-world usage, use a larger buffer, like 8192 bytes
for (;;) {
  int read = in.read(buf);
  if (read == -1) {
    break;
  }
  hash32.update(buf, 0, read);
}
int hash = hash32.getValue();
        System.out.println("12345345234572".hashCode()+":"+hash);
        
        StreamingXXHash64 hash64 = factory.newStreamingHash64(seed);
        
    }
    
}
