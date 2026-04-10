package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.Attribute;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.FrameSegmentModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ServerSendUdpMesageExecutor extends AbstractSendMesageExecutor {

    private InetSocketAddress remote;//udp 対端地址可能变动,非tcp一一对应,发送消息前校验

    protected long delayTimes = 0;
    
    protected ReentrantLock lock;

    private final Condition awaitCondition;//建立一个异步等待、唤醒条件

    private final Condition awaitSegmentCondition;//建立一个异步等待、唤醒条件


    protected boolean frameSegmentEnabled = false;//udp数据帧分段传输是否启用,默认后,当数据帧错误超过FRAME_ERROR_COUNT_LIMIT时自动启用

    protected boolean frameSegmentChecksumEnabled = false;//udp数据帧分段校验是否启用

    protected boolean frameSegmentContinued = false;

    protected int frameSegmentNextIndex;

    protected int frameLastSeq;

    protected int nextFrameSeed;

    protected Random nextFrameSeedRandom;

    protected boolean frameReset = false;

    //最近发送消息缓存
    protected ByteBuf lastMessageByteBuf;
    
    protected AbstractP2PServer server;//


//    protected final Map<Integer, ByteBuf> lastMessageMap = new ConcurrentHashMap<>();
//    //最近发送消息缓存
//    protected final Map<Integer, Map<Integer, ByteBuf>> lastMessageSegmentsMap = new ConcurrentHashMap<>();
    protected ServerSendUdpMesageExecutor(int queueSize) {
        super(queueSize);
        awaitCondition = lock.newCondition();
        awaitSegmentCondition = lock.newCondition();
        nextFrameSeedRandom = new Random(System.currentTimeMillis());
    }

    /**
     * udp消息特殊处理:严格顺序发送(send-> ack)/流控/消息重发/数据帧校验等
     */
    @Override
    public void run() {
        try {
            while (connected && isActive()) {
                lock.lock();
                try {
                    awaitCondition.await();

                    System.out.println("AbstractSendMesageExecutor wait messege ...");
                    P2PWrapper response = responseQueue.take(); //if empty await
                    System.out.println("writeMessage(response):" + response);
                    writeMessage(response);

                    if (frameSegmentEnabled) {
                        while (frameSegmentContinued) {
                            awaitSegmentCondition.await();
                            if (frameReset) {//消息重发
                                if (delayTimes > 0) {//(流控)延时发送消息
                                    Thread.sleep(delayTimes);
                                    delayTimes = 0;
                                }
                                retrieveLastMessageSegment(frameLastSeq);
                            }
                        }
                        frameSegmentNextIndex = 0;
                    } else {
                        awaitCondition.await();
                        if (frameReset) {//消息重发
                            if (delayTimes > 0) {//(流控)延时发送消息
                                Thread.sleep(delayTimes);
                                delayTimes = 0;
                            }
                            if (frameSegmentEnabled) {//错误帧计数超过限制,远程端点请求分段传输
                                while (frameSegmentContinued) {
                                    if (frameReset) {//消息重发
                                        if (delayTimes > 0) {//(流控)延时发送消息
                                            Thread.sleep(delayTimes);
                                            delayTimes = 0;
                                        }
                                        retrieveLastMessageSegment(frameLastSeq);
                                    }
                                    awaitSegmentCondition.await();
                                }
                                frameSegmentNextIndex = 0;
                            } else {
                                retrieveLastMessage(frameLastSeq);
                                awaitCondition.await();
                            }
                        }
                    }

                    System.out.println(" after writeMessage(response):" + response);
                } catch (InterruptedException ex) {
                    log.error(ex.getMessage());
                } finally {
                    frameReset = false;
                    lock.unlock();
                }

            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        } finally {
            connected = false;
            channel.close();
            notifyClosed();
            recycle();//回归对象池
        }
    }
    
     @Override
    public void clear() {
        super.clear();
        remote = null;
        lastMessageByteBuf = null;
    }

    @Override
    public boolean isActive() {//udp channel 状态无用
        return true;
    }
   

    public void reconnect() {
        try {
            channel = channel.connect(remote).sync().channel();
        } catch (InterruptedException ex) {
            channel.close();
            notifyClosed();
            log.error("reconnect to {} fialed.", remote);
            throw new RuntimeException(ex.getMessage());
        }
    }

    @Override
    public void sendMessage(P2PWrapper message) throws InterruptedException {
        lock.lock();
        try {
            responseQueue.put(message);
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeMessage(P2PWrapper message) throws InterruptedException {
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        Integer magicChannel = attrMagic.get();
        byte[] data = SerializationUtil.serialize(message);
        int hash = XXHashUtil.hash32(data);
        System.out.println(message.getSeq() + " data:" + data.length + " hash:" + hash);

//        ByteBuf buffer = SerializationUtil.serializeToByteBuf(request, magicChannel);
        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magicChannel);
        buffer.writeInt(hash);
        buffer.writeBytes(data);

        if (log.isDebugEnabled()) {
            log.debug("request:{}" + message);
        }
        frameLastSeq = message.getSeq();
        writeMessage(message.getSeq(), buffer);

    }

    public void writeMessage(int seq, ByteBuf buffer) throws InterruptedException {
        buffer.retain();
//        lastMessageMap.put(seq, buffer);
        if (lastMessageByteBuf != null) {
            lastMessageByteBuf.release();
        }
        lastMessageByteBuf = buffer;
        if (log.isDebugEnabled()) {
            log.debug("writeMessage ByteBuf seq=", seq);
        }
        int length = buffer.readableBytes();
        if (length > P2PConfig.UDP_TRANSPORT_LIMIT_SIZE) {
            System.out.println("分包发送");
            //分包发送,以防止中间网络路由问题导致传输问题(超时),实测数据域映射端口超过64k tcp包经常超时
            int rest = (int) length % P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            int count = (int) length / P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            //System.out.println(bytes.length+" -> "+count+" rest "+rest);

            int start = buffer.readerIndex();
            for (int i = 0; i < count; i++) {
                buffer.retain();
                //cf = channel.writeAndFlush(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE));
                lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE),
                    this.remote));
                lastMessageFuture.sync();
            }
            if (rest > 0) {

//                cf = channel.writeAndFlush(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest));
                lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest), this.remote));
                lastMessageFuture.sync();
            }
        } else {
            System.out.println("sennding:" + this.remote + "buffer:" + buffer.readableBytes());
            lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer, this.remote));
            lastMessageFuture.sync();
            System.out.println("cf.sync() after");
        }

    }

    public void writeMessageSegment(int seq, int index, ByteBuf buffer) throws InterruptedException {
        buffer.retain();
        //lastMessageMap.put(seq, buffer);
        if (lastMessageByteBuf != null) {
            lastMessageByteBuf.release();
        }
        lastMessageByteBuf = buffer;
        if (log.isDebugEnabled()) {
            log.debug("writeMessage ByteBuf seq=", seq);
        }

        int length = buffer.readableBytes();
        if (length > P2PConfig.UDP_TRANSPORT_LIMIT_SIZE) {
            System.out.println("分包发送");
            //分包发送,以防止中间网络路由问题导致传输问题(超时),实测数据域映射端口超过64k tcp包经常超时
            int rest = (int) length % P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            int count = (int) length / P2PConfig.UDP_TRANSPORT_LIMIT_SIZE;
            //System.out.println(bytes.length+" -> "+count+" rest "+rest);

            int start = buffer.readerIndex();
            for (int i = index; i < count; i++) {
                buffer.retain();
                //cf = channel.writeAndFlush(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE));
                lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + i * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, P2PConfig.UDP_TRANSPORT_LIMIT_SIZE),
                    this.remote));
                lastMessageFuture.sync();
            }
            if (rest > 0) {

//                cf = channel.writeAndFlush(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest));
                lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer.slice(start + count * P2PConfig.UDP_TRANSPORT_LIMIT_SIZE, rest), this.remote));
                lastMessageFuture.sync();
            }
        } else {
            System.out.println("sennding:" + this.remote + "buffer:" + buffer.readableBytes());
            lastMessageFuture = channel.writeAndFlush(new DatagramPacket(buffer, this.remote));
            lastMessageFuture.sync();
            System.out.println("cf.sync() after");
        }

    }

    public boolean retrieveLastMessage(int seq, int nextSeed) {
        if (seq != frameLastSeq || nextSeed != this.nextFrameSeed) {
            return false;
        }
        if (lastMessageFuture != null && !lastMessageFuture.isDone()) {
            lastMessageFuture.cancel(true);
        }
        if (lastMessageByteBuf != null) {
            lock.lock();
            try {
                frameReset = true;
                awaitCondition.signal();
            } catch (Exception ex) {
                log.error("rewriteLastMessage() to {} fialed,Exception {},seq=", remote, ex.getMessage(), seq);
            } finally {
                lock.unlock();
            }
            return true;
        }
        return false;
    }

    private void retrieveLastMessage(int seq) {
        if (lastMessageByteBuf != null) {
            try {
                writeMessage(seq, lastMessageByteBuf);
            } catch (InterruptedException ex) {
                log.error("rewriteLastMessage() to {} fialed,InterruptedException {},seq=", remote, ex.getMessage(), seq);
            }
        }
    }

    public boolean retrieveLastMessageSegment(int seq, FrameSegmentModel model) {
        if (seq != frameLastSeq || model.nextSeed != this.nextFrameSeed) {
            return false;
        }
        if (lastMessageFuture != null && !lastMessageFuture.isDone()) {
            lastMessageFuture.cancel(true);
        }
        if (lastMessageByteBuf != null) {
            lock.lock();
            try {
                frameSegmentNextIndex = model.nextIndex;
                frameReset = true;
                awaitSegmentCondition.signal();
            } catch (Exception ex) {
                log.error("rewriteLastMessage() to {} fialed,Exception {},seq=", remote, ex.getMessage(), seq);
            } finally {
                lock.unlock();
            }
            return true;
        }
        return false;
    }

    private void retrieveLastMessageSegment(int seq) {

        if (lastMessageByteBuf != null) {
            try {
                writeMessageSegment(seq, frameSegmentNextIndex, lastMessageByteBuf);
            } catch (InterruptedException ex) {
                log.error("rewriteLastMessage() to {} fialed,InterruptedException {},seq=", remote, ex.getMessage(), seq);
            }
        }
    }

    public boolean complete(int seq, int nextSeed) {
        lock.lock();
        try {
            if (seq != frameLastSeq || nextSeed != this.nextFrameSeed) {
                return false;
            }
//        ByteBuf buffer = lastMessageMap.remove(seq);
            if (lastMessageByteBuf != null) {
                lastMessageByteBuf.clear();
                ReferenceCountUtil.safeRelease(lastMessageByteBuf);
                lastMessageByteBuf = null;
            } else {
                return false;
            }
//        Map<Integer, ByteBuf> segments = lastMessageSegmentsMap.remove(seq);
//        if (segments != null) {
//            for (Map.Entry<Integer, ByteBuf> entry : segments.entrySet()) {
//                entry.getValue().clear();
//                ReferenceCountUtil.safeRelease(entry.getValue());
//            }
//            segments.clear();
//        }
            if (frameSegmentEnabled) {
                frameSegmentContinued = false;
                awaitSegmentCondition.signal();
            } else {
                awaitCondition.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean completeSegment(int seq, FrameSegmentModel model) {
        if (seq != frameLastSeq || model.nextSeed != this.nextFrameSeed) {
            return false;
        }
        frameSegmentNextIndex = model.nextIndex;
        return true;
    }

    /**
     * 启用分段传输
     *
     * @param seq
     * @param model
     * @return
     */
    public boolean enableFrameSegment(int seq, FrameSegmentModel model) {
        if (seq != frameLastSeq || model.nextSeed != this.nextFrameSeed) {
            return false;
        }
        frameSegmentNextIndex = model.nextIndex;
        lock.lock();
        try {
            frameSegmentEnabled = true;
            frameSegmentContinued = true;
            frameReset = true;
            awaitCondition.signal();
        } catch (Exception ex) {
            log.error("enableFrameSegment() to {} fialed,Exception {},seq=", remote, ex.getMessage(), seq);
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * 禁用用分段传输
     *
     * @param seq
     * @param model
     * @return
     */
    public boolean disableFrameSegment(int seq, FrameSegmentModel model) {
        if (seq != frameLastSeq || model.nextSeed != this.nextFrameSeed) {
            return false;
        }
        frameSegmentNextIndex = model.nextIndex;
        lock.lock();
        try {
            frameSegmentEnabled = false;
            frameSegmentContinued = false;
            awaitSegmentCondition.signal();
        } catch (Exception ex) {
            log.error("enableFrameSegment() to {} fialed,Exception {},seq=", remote, ex.getMessage(), seq);
        } finally {
            lock.unlock();
        }
        return true;
    }

    public InetSocketAddress getRemote() {
        return remote;
    }

    /**
     *
     * @param obj
     * @return
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ServerSendUdpMesageExecutor other = (ServerSendUdpMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }



    public final static <T> ServerSendUdpMesageExecutor build(AbstractP2PServer server, int queueSize, InetSocketAddress remote, Channel channel) {
        ServerSendUdpMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        t.server = server;
        t.remote = remote;
        t.channel = channel;
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        t.magic = attrMagic.get();
        //启动异步任务
        t.start(channel);
        return t;
    }

    @Override
    public void recycle() {
        ConcurrentObjectPool.get(queueSize).offer(this);
    }

    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        //TODO
        //server
    }

    static class ConcurrentObjectPool {

        private static final ThreadLocal<Map<Integer, PooledObjects<ServerSendUdpMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ServerSendUdpMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ServerSendUdpMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ServerSendUdpMesageExecutor> pool;
            if (map == null) {
                map = new HashMap();
                LOCAL_POOL.set(map);
                pool = new PooledObjects(4096, new PooledObjectFactory<ServerSendUdpMesageExecutor>() {
                    @Override
                    public ServerSendUdpMesageExecutor newInstance() {
                        return new ServerSendUdpMesageExecutor(queueSize);
                    }
                });
                map.put(queueSize, pool);
            } else {
                pool = map.get(queueSize);
            }
            return pool;
        }

    }

}
