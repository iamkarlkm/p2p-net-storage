package javax.net.p2p.channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.client.ClientSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.StreamRequest;
import javax.net.p2p.interfaces.StreamResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 流消息处理基类
 *
 * @author karl
 */
@Slf4j
public abstract class AbstractStreamResponseAdapter implements StreamResponse, Runnable {

    /**
     * 流消息序列号对应的回调消息处理器
     */
    private static final ConcurrentMap<Integer, StreamResponse> STREAM_RESPONSE_HANDLER_MAP = new ConcurrentHashMap<>(4096);
    protected static final ReentrantLock streamRequestLock = new ReentrantLock();

    //protected static final ThreadLocal<Boolean> canceled = new ThreadLocal();
    protected boolean canceled;
    //protected ClientSendMesageExecutor executor;
    //private StreamP2PWrapper response;

    private StreamResponse streamResponse;

    private StreamP2PWrapper streamMessage;

    private Condition awaitCondition;

    private ReentrantLock lock;

    @Override
    public void run() {
        if (streamResponse != null) {
            lock = new ReentrantLock();
            lock.lock();
            try {
                if (streamMessage != null) {//首次调用执行
                    streamResponse.response(streamMessage);
                    streamMessage = null;
                }
            } finally {
                lock.unlock();
            }

            awaitCondition = lock.newCondition();
            while (true) {
                lock.lock();
                try {
                    //等待新消息到来
                    awaitCondition.await();
                    if (canceled) {
                        streamResponse.cancel(streamMessage);
                        streamMessage = null;
                        break;
                    } else {
                        streamResponse.response(streamMessage);
                        streamMessage = null;
                    }
                } catch (InterruptedException ex) {
                    log.error("response:{}  -> exception:{}", streamMessage, ex.getMessage());
                } finally {
                    lock.unlock();
                }

            }

        }

    }

    /**
     * 新消息到来
     *
     * @param streamMessage
     */
    public void onMessage(StreamP2PWrapper streamMessage) {
        lock.lock();
        try {
            this.streamMessage = streamMessage;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 新消息到来
     *
     * @param streamMessage
     */
    public void onCancel(StreamP2PWrapper streamMessage) {
        lock.lock();
        try {
            this.canceled = true;
            this.streamMessage = streamMessage;
            awaitCondition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void asyncProcess(AbstractStreamResponseAdapter handler, StreamP2PWrapper response) {
        try {
            if (handler instanceof StreamResponse) {//(文件上传/发布)流消息请求处理,异步回调
                StreamResponse sr = STREAM_RESPONSE_HANDLER_MAP.get(response.getSeq());
                if (sr == null) {//首次调用,生成异步线程单独对应处理
                    streamRequestLock.lock();
                    try {
                        sr = STREAM_RESPONSE_HANDLER_MAP.get(response.getSeq());
                        if (sr == null) {
                            sr = (StreamResponse) handler;
                            STREAM_RESPONSE_HANDLER_MAP.put(response.getSeq(), sr);
                            //提交异步任务处理
                            ExecutorServicePool.CLIENT_ASYNC_POOLS.submit(handler);
                            return;
                        }
                    } finally {
                        streamRequestLock.unlock();
                    }
                }
                if (response.isCanceled()) {
                    handler.onCancel(response);
                } else {
                    handler.onMessage(response);
                }
                if (response.isCompleted() || response.isCanceled()) {
                    STREAM_RESPONSE_HANDLER_MAP.remove(response.getSeq());
                }
                return;
            }
            if (response.isCanceled()) {
                canceled = true;
                return;
            }
            //一次性的单向/有限流处理
            AbstractStreamResponseAdapter newThreadHandler = (AbstractStreamResponseAdapter) handler.clone();
            ExecutorServicePool.CLIENT_ASYNC_POOLS.submit(newThreadHandler);

        } catch (Exception ex) {
            log.error("response:{}  -> exception:{}", response, ex.getMessage());
        }
    }

    //public abstract void processStream(ClientSendMesageExecutor executor, P2PWrapper response) throws InterruptedException;
}
