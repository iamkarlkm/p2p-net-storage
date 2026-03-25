
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4SafeDecompressor;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author karl
 */
public class LZ4 {
    public static void main(String[] args) throws Exception {
        LZ4Factory factory = LZ4Factory.fastestInstance();

byte[] data = "12345345234572".getBytes("UTF-8");
final int decompressedLength = data.length;

// compress data
LZ4Compressor compressor = factory.fastCompressor();
int maxCompressedLength = compressor.maxCompressedLength(decompressedLength);
byte[] compressed = new byte[maxCompressedLength];
int compressedLength = compressor.compress(data, 0, decompressedLength, compressed, 0, maxCompressedLength);

// decompress data
// - method 1: when the decompressed length is known
LZ4FastDecompressor decompressor = factory.fastDecompressor();
byte[] restored = new byte[decompressedLength];
int compressedLength2 = decompressor.decompress(compressed, 0, restored, 0, decompressedLength);
// compressedLength == compressedLength2
System.out.println(compressedLength+"="+compressedLength2);
// - method 2: when the compressed length is known (a little slower)
// the destination buffer needs to be over-sized
LZ4SafeDecompressor decompressor2 = factory.safeDecompressor();
int decompressedLength2 = decompressor2.decompress(compressed, 0, compressedLength, restored, 0);
// decompressedLength == decompressedLength2
System.out.println(decompressedLength+"="+decompressedLength2);
//byte[] data = "12345345234572".getBytes("UTF-8");
//final int decompressedLength = data.length;

//LZ4FrameOutputStream outStream = new LZ4FrameOutputStream(new FileOutputStream(new File("test.lz4")));
//outStream.write(data);
//outStream.close();
//
////byte[] restored = new byte[decompressedLength];
//LZ4FrameInputStream inStream = new LZ4FrameInputStream(new FileInputStream(new File("test.lz4")));
//inStream.read(restored);
//inStream.close();
    }
}
