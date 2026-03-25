package javax.net.p2p.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Attribute;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractUdpMessageProcessor;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.common.pool.PooledableAdapter;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * udp数据帧接收缓冲类,用于udp流控,粘包,帧同步(重置),超时重发等
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class UdpFrameInbound extends PooledableAdapter implements Closeable,Runnable{

    protected int headerSize;
    protected int frameLengthInt = -1;

    protected int frameReaded = 0;

    protected int lastRest = 0;
    
    
    protected int frameLastSeq;//上一个成功接受数据帧的序列号
    
    protected long frameLastTransportSpeed;//上一个成功接受数据帧的传输速率,字节/毫秒(mill),用于流控/帧超时重发

    protected Integer frameLastSeed;//udp数据包简单校验(上一个成功接受数据帧->随机数/hash/checksum) 默认随机数以优化性能

    protected boolean frameReseted = false;//udp frame reset

    protected long frameResetedTime = 0;//udp frame reset timestame 
    
    protected long frameStartTime = 0;//udp frame start timestame 

    protected static int FRAME_RESETED_TIME_DURATION = 1000;//默认1秒

    protected static int FRAME_ERROR_COUNT_LIMIT = 3;//默认3
    
    protected int frameErrorCount;//无效数据帧或乱序/非法(伪造)数据包计数
    
    protected int frameSuccessCount;//从上次启用数据帧分段校验之后,成功接受数据帧计数,用于自动禁用数据帧分段校验以以优化性能

    protected boolean frameSegmentEnabled = false;//udp数据帧分段传输是否启用,默认后,当数据帧错误超过FRAME_ERROR_COUNT_LIMIT时自动启用

    protected UdpFrameInternalChecksum frameInternalChecksum;

    protected int magic;
    
    protected Integer queueSize;
    
     private final Condition awaitCondition;

    private final ReentrantLock lock;
    
    private boolean continued = true;
    
    private ByteBuf inBuffer;//DatagramPacket缓冲区尺寸最大2048,必须copy收集足够一数据帧
    
    protected int nextSeed = 0;///udp数据帧数据帧头检验,初始包为0,远程端点将预先传入下一个包的检验数,依次顺序变更,以防止乱序/非法(伪造)数据包
    
    private InetSocketAddress remoteAddess;
    
    private AbstractUdpMessageProcessor messageProcessor;
    
    private Channel channel;
    private Future<?> future;

    protected UdpFrameInbound() {
        this.lock = new ReentrantLock();
        this.awaitCondition = lock.newCondition();
        this.headerSize = 12;
    }
//
//    public final static <T> UdpFrameInbound build() {
//        return ConcurrentObjectPool.get().poll();
//    }
//
//    public final static <T> UdpFrameInbound build(int headerSize) {
//        UdpFrameInbound t = ConcurrentObjectPool.get().poll();
//        t.headerSize = headerSize;
//        return t;
//    }
    
    public final static <T> UdpFrameInbound build(AbstractUdpMessageProcessor messageProcessor,Channel channel,InetSocketAddress remoteAddess,int magic,Integer queueSize) {
        UdpFrameInbound t = ConcurrentObjectPool.get().poll();
        t.messageProcessor = messageProcessor;
        t.channel = channel;
        t.remoteAddess = remoteAddess;
        t.magic = magic;
        t.queueSize = queueSize;
        t.continued = true;
        t.start();
        return t;
    }
    
    public final static <T> UdpFrameInbound build(AbstractUdpMessageProcessor messageProcessor,Channel channel,InetSocketAddress remoteAddess,int headerSize,int magic,Integer queueSize) {
        UdpFrameInbound t = ConcurrentObjectPool.get().poll();
        t.messageProcessor = messageProcessor;
        t.channel = channel;
        t.remoteAddess = remoteAddess;
        t.headerSize = headerSize;
        t.magic = magic;
        t.queueSize = queueSize;
        t.continued = true;
        t.start();
        return t;
    }
    
    @Override
    public void run(){
        while (continued) {
            lock.lock();
            try {
                awaitCondition.await();
                if(continued){
                    resetUdpFrame(channel, remoteAddess);
                }
            } catch (InterruptedException ex) {
                log.error("run InterruptedException -> {}", ex.getMessage());
            } finally {
                lock.unlock();
            }
        }
        release();//回归对象池

    }

    @Override
    public void clear() {
        frameLengthInt = -1;
        frameReaded = 0;
        lastRest = 0;
        frameLastSeed = null;
        frameReseted = false;
        frameResetedTime = 0;
        messageProcessor = null;
        channel = null;
        remoteAddess = null;
        future = null;
    }

    @Override
    public boolean release() {
        return ConcurrentObjectPool.get().offer(this);
    }
    
    public void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagramPacket) throws Exception{
        ByteBuf in = datagramPacket.content();
        if(frameReseted){
            System.out.println("frameReseted skip readableBytes:"+in.readableBytes());
            in.skipBytes(in.readableBytes());
            return;
        }
        //System.out.println("readableBytes:"+in.readableBytes());
        if (lastRest >0 ){
             if (lastRest < 8 && in.readableBytes()>headerSize) {//头长度
                inBuffer.writeBytes(in, headerSize);
            }else{
                 inBuffer.writeBytes(in);
             }
             if (inBuffer.readableBytes() < headerSize) {//头长度
                //new PooledByteBufAllocator(false);
                return;
            }
            frameLengthInt = inBuffer.readInt();//保存frame字节数
            magic = inBuffer.readInt();
            int hash = inBuffer.readInt();
            System.out.printf("lastRest=%d,magic=%s,hash=%s,frameLengthInt=%d\n",lastRest,Integer.toHexString(magic),hash,frameLengthInt);
            lastRest = 0;
        } else if(frameLengthInt == -1) { // new frame
            if (in.readableBytes() < headerSize) {//头长度
                //new PooledByteBufAllocator(false);
                return;
            }
            frameStartTime = System.currentTimeMillis();
            frameLengthInt = in.readInt();//保存frame字节数
            magic = in.readInt();
            int hash = in.readInt();
            System.out.printf("magic=%s,hash=%s,frameLengthInt=%d\n",Integer.toHexString(magic),hash,frameLengthInt);

            if(inBuffer==null){
                inBuffer = SerializationUtil.tryGetDirectBuffer(frameLengthInt);
            }else if(inBuffer.capacity()<frameLengthInt){
               inBuffer.capacity(frameLengthInt);
            }
                        
            //System.out.println("after readableBytes:"+in.readableBytes());
            //ctx.fireChannelReadComplete();
        }
        //等待frame传输完成
        if (inBuffer.readableBytes() < frameLengthInt) {
            int rest = frameLengthInt-frameReaded;
            if (in.readableBytes() <= rest) {
               frameReaded += in.readableBytes();
               inBuffer.writeBytes(in); 
            }else{
                frameReaded += rest;
                inBuffer.writeBytes(in, rest);
            }
            
            if (inBuffer.readableBytes() < frameLengthInt) {
                //System.out.println("after readableBytes:"+in.readableBytes());
                //ctx.fireChannelReadComplete();
                if (log.isDebugEnabled()) {
                    log.debug("WrapperDecoder -> expected " + frameLengthInt + ",actual:" + in.readableBytes());
                }
                return;
            }
        }

        Attribute<Integer> attrMagic = ctx.channel().attr(ChannelUtils.MAGIC);
        if (magic == attrMagic.get()) {
            frameLastTransportSpeed = frameLengthInt/(System.currentTimeMillis()-frameStartTime);
//            System.out.println("frameLengthInt:"+frameLengthInt);
//            System.out.println("data rix:"+inBuffer.readerIndex());
//            System.out.println("readableBytes:"+inBuffer.readableBytes());
//            System.out.println(Integer.toHexString(magic)+" datagramPacket.sender() ->"+datagramPacket.sender()); 
//            System.out.println(Integer.toHexString(magic)+" datagramPacket.recipient() ->"+datagramPacket.recipient());  
            try {//先获取可读字节数
                byte[] data = new byte[frameLengthInt];
                inBuffer.readBytes(data);
                int hashIn = XXHashUtil.hash32(data);
                System.out.println("hashIn:"+hashIn);
                P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, data);
                frameLastSeq = request.getSeq();
                frameLastSeed = hashIn;
                //P2PWrapper request = SerializationUtil.deserialize(P2PWrapper.class, inBuffer, magic, frameLengthInt);
//                System.out.println("Object result = "+request.getData().toString().length());
//                System.out.println(Arrays.toString(request.getData().toString().getBytes()));
//                System.out.println("Server echo -> test udp-0".length());
                messageProcessor.processMessage(ctx, datagramPacket, request);
            } catch (Exception ex) {
                log.error("skip {} bytes -> {} \n {} resolve error:{}, \n resetUdpFrame:{}",in.readableBytes(), inBuffer, ex, datagramPacket);
                resetUdpFrame(ctx, datagramPacket);

            } finally {
                frameLengthInt = -1; // start processing the next frame
                frameReaded = 0;
                inBuffer.clear();
                lastRest = in.readableBytes();
                inBuffer.writeBytes(in);
            }
        } else {
            log.error("skip {} bytes -> expected magic number {},actual:{}", frameLengthInt, attrMagic.get(), magic);
            resetUdpFrame(ctx, datagramPacket);
        }
    }
    
    public void resetUdpFrame(ChannelHandlerContext ctx, DatagramPacket datagramPacket) {
        frameErrorCount++;
        ByteBuf in = datagramPacket.content();
        in.skipBytes(in.readableBytes());
        resetUdpFrame(ctx.channel(), datagramPacket.sender());
    }
    
    public void resetUdpFrame(Channel channel, InetSocketAddress remoteAddess) {
        
        inBuffer.skipBytes(inBuffer.readableBytes());

        P2PWrapper resetUdpFrame = P2PWrapper.build(frameLastSeq, P2PCommand.UDP_FRAME_RESET);
        messageProcessor.completeLastResponse(remoteAddess);
        messageProcessor.sendResponse(channel, remoteAddess, resetUdpFrame, magic);
        frameLengthInt = -1; // start processing the next frame
        frameReaded = 0;
        lastRest = 0;
    }
    
    public void start()  {
       this.future =  ExecutorServicePool.P2P_REFERENCED_SERVER_ASYNC_POOLS.submit(this);
    }
    
    /**
     * 等待指定时间唤醒
     * @param timeout 
     */
    public void signalResetAfter(long timeout) {
        frameReseted = true;
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException ex) {
            log.error("signalTimeout() InterruptedException -> {}", ex.getMessage());
        }
        lock.lock();
        try {
            frameReseted = false;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 等待指定时间唤醒
     * @param timeout 
     */
    public void signalRetrieveAfter(long timeout) {
        frameReseted = true;
        try {
            Thread.sleep(timeout);
        } catch (InterruptedException ex) {
            log.error("signalTimeout() InterruptedException -> {}", ex.getMessage());
        }
        lock.lock();
        try {
            frameReseted = false;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        continued = false;
        lock.lock();
        try {
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
        if(future!=null && !future.isDone()){
            try{
            future.cancel(true);
            }catch(Exception ex){
                log.error("close() Exception -> {}", ex.getMessage());
            }
        }
        release();
    }

    static class ConcurrentObjectPool {

        private static final ThreadLocal<PooledObjects<UdpFrameInbound>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<UdpFrameInbound> get() {
            PooledObjects pool = LOCAL_POOL.get();
            if (pool == null) {
                pool = new PooledObjects(4096, new PooledObjectFactory<UdpFrameInbound>() {
                    @Override
                    public UdpFrameInbound newInstance() {
                        return new UdpFrameInbound();
                    }
                });
                LOCAL_POOL.set(pool);
            }
            return pool;
        }

    }

}
