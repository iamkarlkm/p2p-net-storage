
import java.io.BufferedOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *$java PingPong
13212 ms

// 开启Wisp2
$java -XX:+UnlockExperimentalVMOptions -XX:+UseWisp2 -XX:ActiveProcessorCount=1 PingPong
882 ms
 * @author karl
 */
//@Slf4j
public class Wisp2 {
    
    public static final ExecutorService THREAD_POOL = Executors.newVirtualThreadPerTaskExecutor();
    
//    public static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(2);

public static void main(String[] args) throws Exception {
   System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
    //System.setProperty("file.encoding", "utf-8");
    //BlockingQueue<Byte> q1 = new LinkedBlockingQueue<>(), q2 = new LinkedBlockingQueue<>();
//    ArrayBlockingQueue<Byte> q1 = new ArrayBlockingQueue<Byte>(4096), q2 = new ArrayBlockingQueue<Byte>(4096);
//    THREAD_POOL.submit(() -> pingpong(q2, q1)); // thread A
//    Future<?> f = THREAD_POOL.submit(() -> pingpong(q1, q2)); // thread B
//    q1.put((byte) 1);
    System.out.println(Charset.defaultCharset());
    System.out.println(System.out.getClass());
    System.out.println( " ms中文");
   // System.out.println(f.get() + " ms中文");
}

private static long pingpong(BlockingQueue<Byte> in, BlockingQueue<Byte> out) throws Exception {
    long start = System.currentTimeMillis();
    for (int i = 0; i < 1_000_000; i++) out.put(in.take());
    return System.currentTimeMillis() - start;
}
    
}
