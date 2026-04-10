package javax.net.p2p.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.util.Attribute;
import io.netty.util.ReferenceCountUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.pool.PooledObjectFactory;
import javax.net.p2p.common.pool.PooledObjects;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author karl
 */
@Slf4j
public class ServerSendQuicMesageExecutor extends AbstractSendMesageExecutor {

    protected ReentrantLock lock;
    private final Condition awaitCondition;

    protected AbstractP2PServer server;
    protected ByteBuf lastMessageByteBuf;

    protected ServerSendQuicMesageExecutor(int queueSize) {
        super(queueSize);
        lock = new ReentrantLock();
        awaitCondition = lock.newCondition();
    }

    @Override
    public void run() {
        try {
            while (connected && isActive()) {
                lock.lock();
                try {
                    awaitCondition.await();
                    P2PWrapper response = responseQueue.take();
                    writeMessage(response);
                } catch (InterruptedException ex) {
                    log.error(ex.getMessage());
                } finally {
                    lock.unlock();
                }
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        } finally {
            connected = false;
            channel.close();
            notifyClosed();
            recycle();
        }
    }

    @Override
    public void clear() {
        super.clear();
        lastMessageByteBuf = null;
    }

    @Override
    public boolean isActive() {
        return channel != null && channel.isActive();
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
        if (magicChannel == null) magicChannel = magic;
        byte[] data = SerializationUtil.serialize(message);
        int hash = XXHashUtil.hash32(data);

        ByteBuf buffer = SerializationUtil.tryGetDirectBuffer(data.length + 12);
        buffer.writeInt(data.length);
        buffer.writeInt(magicChannel);
        buffer.writeInt(hash);
        buffer.writeBytes(data);

        if (lastMessageByteBuf != null) {
            lastMessageByteBuf.release();
        }
        lastMessageByteBuf = buffer;
        
        lastMessageFuture = channel.writeAndFlush(buffer);
        lastMessageFuture.sync();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final ServerSendQuicMesageExecutor other = (ServerSendQuicMesageExecutor) obj;
        return Objects.equals(this.channel, other.channel);
    }

    public final static ServerSendQuicMesageExecutor build(AbstractP2PServer server, int queueSize, Channel channel) {
        ServerSendQuicMesageExecutor t = ConcurrentObjectPool.get(queueSize).poll();
        t.server = server;
        t.channel = channel;
        Attribute<Integer> attrMagic = channel.attr(ChannelUtils.MAGIC);
        if (attrMagic != null && attrMagic.get() != null) {
            t.magic = attrMagic.get();
        }
        t.start(channel);
        return t;
    }

    @Override
    public void recycle() {
        ConcurrentObjectPool.get(queueSize).offer(this);
    }

    @Override
    public void connect(EventLoopGroup io_work_group, Bootstrap bootstrap) {
        // server-side executor doesn't connect
    }

    static class ConcurrentObjectPool {
        private static final ThreadLocal<Map<Integer, PooledObjects<ServerSendQuicMesageExecutor>>> LOCAL_POOL = new ThreadLocal<>();

        private static PooledObjects<ServerSendQuicMesageExecutor> get(Integer queueSize) {
            Map<Integer, PooledObjects<ServerSendQuicMesageExecutor>> map = LOCAL_POOL.get();
            PooledObjects<ServerSendQuicMesageExecutor> pool;
            if (map == null) {
                map = new HashMap<>();
                LOCAL_POOL.set(map);
                pool = new PooledObjects<>(4096, new PooledObjectFactory<ServerSendQuicMesageExecutor>() {
                    @Override
                    public ServerSendQuicMesageExecutor newInstance() {
                        return new ServerSendQuicMesageExecutor(queueSize);
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