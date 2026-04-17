
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Paths;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author Administrator
 */
public class ReadByteMappedFile {

    public final static byte readByteMappedFile(MappedByteBuffer buffer,int offset){
        return buffer.get(offset);
    }
    
    public final static short readShortMappedFile(MappedByteBuffer buffer,int offset){
        return buffer.getShort(offset);
    }
    
    public final static int readIntMappedFile(MappedByteBuffer buffer,int offset){
        return buffer.getInt(offset);
    }
    
    public final static long readInt64MappedFile(MappedByteBuffer buffer,int offset){
        return buffer.getLong(offset);
    }
    
    public final static void writeByteMappedFile(MappedByteBuffer buffer,int offset,byte b){
        buffer.put(offset,b);
    }
    
    public final static void writeShortMappedFile(MappedByteBuffer buffer,int offset,short b){
        buffer.putShort(offset,b);
    }
    
    public final static void writeIntMappedFile(MappedByteBuffer buffer,int offset,int b){
        buffer.putInt(offset,b);
    }
    
    public final static void writeInt64MappedFile(MappedByteBuffer buffer,int offset,long b){
        buffer.putLong(offset,b);
    }
    
    public final static void writeHead32MappedFile(MappedByteBuffer buffer,int count){
        //buffer.putLong(offset,b);
    }
    
    public static void main(String[] args) throws InterruptedException {
        //System.out.println(0%255);
        //System.out.println(255%255);
        MappedByteBuffer buffer = null;
        try {
            File file = new File("c:/temp/test.txt");
            if(!file.exists()){
                Files.createFile(Paths.get(file.getAbsolutePath()));
            }
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            
//            buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
//            raf.setLength(8);
            FileChannel channel = raf.getChannel();
            
            // creating object of ByteBuffer 
            // and allocating size capacity 
            ByteBuffer bb = ByteBuffer.allocate(4096); 
            long size = channel.size();
            channel.position(size);
            while(true){
                Thread.sleep(5000l);
                bb.clear();
                long newSize = channel.size();
                if(newSize>size){
                    int length =channel.read(bb);
                    if(length>0){
                        bb.position(0);
                        byte[] buf = new byte[length];
                        System.out.println(channel.position()+","+length);
                    bb.get(buf,0,length);
                    System.out.print(new String(buf));
                    }
                    //System.out.println(channel.position()+","+length);
                    size = newSize;
                }
                
            }
            //buffer.
            //writeByteMappedFile(buffer, 0, (byte)32);
            //System.out.println("sum:" + sum + " time:" + t);
        } catch (FileNotFoundException e) {
// TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
// TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    public static void main2(String[] args) {
        //System.out.println(0%255);
        //System.out.println(255%255);
        MappedByteBuffer buffer = null;
        try {
            File file = new File("c:/temp/hashmap.bin");
            if(!file.exists()){
                Files.createFile(Paths.get(file.getAbsolutePath()));
            }
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            
            buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, raf.length());
            raf.setLength(8);
            
            //buffer.
            //writeByteMappedFile(buffer, 0, (byte)32);
            //System.out.println("sum:" + sum + " time:" + t);
        } catch (FileNotFoundException e) {
// TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
// TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
