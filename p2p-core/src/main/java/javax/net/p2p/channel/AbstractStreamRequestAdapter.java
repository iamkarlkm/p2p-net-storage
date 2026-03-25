package javax.net.p2p.channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.ClonePooledObjects;
import javax.net.p2p.common.pool.ClonePooledableAdapter;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.interfaces.StreamRequest;
import javax.net.p2p.model.CancelP2PWrapper;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * 流消息处理基类
 *
 * @author karl
 */
@Slf4j
public abstract class AbstractStreamRequestAdapter extends ClonePooledableAdapter implements P2PCommandHandler, Runnable {

    /**
     * 流消息序列号对应的Future
     */
    private static final ConcurrentMap<Integer, Future> FUTURE_MAP = new ConcurrentHashMap<>(4096);

    private static final ClonePooledObjects<AbstractStreamRequestAdapter> TASK_POOL = new ClonePooledObjects(4096);

    //protected static final ThreadLocal<Boolean> canceled = new ThreadLocal();
    protected boolean continued;
    protected ServerSendUdpMesageExecutor executor;

    private P2PWrapper request;

    private StreamRequest streamRequest;

    private StreamP2PWrapper streamMessage;

    private Integer requestId;

    private Condition awaitCondition;

    private ReentrantLock lock;

    @Override
    public void run() {
        try {
            if (request != null) {//单次/有限流处理
                try {
                    processStream(executor, request);
                } catch (InterruptedException ex) {
                    log.error("request:{}  -> exception:{}", request, ex.getMessage());
                }
            } else if (streamRequest != null) {
                lock = new ReentrantLock();
                if (executor.isActive()) {
                    lock.lock();
                    try {
                        if (streamMessage != null) {//首次调用执行
                            StreamP2PWrapper streamResponse = streamRequest.request(executor, streamMessage);
                            if (streamResponse != null) {
                                executor.sendResponse(streamResponse);
                            }
                            streamMessage = null;
                        }
                    } catch (InterruptedException ex) {
                        log.error("request:{}  -> exception:{}", streamMessage, ex.getMessage());
                    } finally {
                        lock.unlock();
                    }

                } else {
                    executor.reconnect();//尝试重新连接
                }
                awaitCondition = lock.newCondition();
                while (continued) {
                    if (executor.isActive()) {
                        lock.lock();
                        try {
                            //等待新消息到来
                            awaitCondition.await();
                            if (streamMessage.isCompleted()) {
                                StreamP2PWrapper streamResponse = streamRequest.request(executor, streamMessage);
                                if (streamResponse != null) {
                                    executor.sendResponse(streamResponse);
                                }
                                streamMessage = null;
                                break;
                            } else if (streamMessage.isCanceled()) {
                                continued = false;
                                streamRequest.cancel(executor, streamMessage);
                                streamMessage = null;

                                break;
                            } else {
                                StreamP2PWrapper streamResponse = streamRequest.request(executor, streamMessage);
                                if (streamResponse != null) {
                                    executor.sendResponse(streamResponse);
                                }
                                streamMessage = null;
                            }
                        } catch (InterruptedException ex) {
                            log.error("request:{}  -> exception:{}", streamMessage, ex.getMessage());
                        } finally {
                            lock.unlock();
                        }

                    } else {
                        executor.reconnect();//尝试重新连接
                    }

                }

            }
        } finally {
            release();//回归对象池
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

    public AbstractStreamRequestAdapter asyncProcess(ServerSendUdpMesageExecutor executor, AbstractStreamRequestAdapter handler, P2PWrapper request) {
        try {
            if (handler instanceof StreamRequest) {//(文件上传/发布)流消息请求处理,异步回调
                StreamP2PWrapper streamMessage0 = (StreamP2PWrapper) request;

                AbstractStreamRequestAdapter handlerNew = TASK_POOL.pollParamsOrClone(handler, request.getSeq(), executor, streamMessage0);

                handlerNew.onMessage(streamMessage0);

                return handlerNew;
            }

            //一次性的单向/有限流处理
            //带参数重用对象AbstractLongTimedRequestAdapter类必须@Override loadParams(executor,request)
            return TASK_POOL.pollParamsOrClone(handler, executor, request);

        } catch (Exception ex) {
            log.error("request:{}  -> exception:{}", request, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }

    public void asyncProcess(AbstractStreamRequestAdapter handler, P2PWrapper request) {
        try {
            if (handler instanceof StreamRequest) {//(文件上传/发布)流消息请求处理,异步回调
                StreamP2PWrapper streamMessage0 = (StreamP2PWrapper) request;
                handler.onMessage(streamMessage0);
                return;
            }
//            log.info("AbstractLongTimedRequestAdapter:{} \n -> request:{}", handler, request);
            if (request instanceof CancelP2PWrapper) {
                this.continued = false;
                Future f = FUTURE_MAP.remove(request.getSeq());
                if (f != null && !f.isDone()) {
                    f.cancel(true);
                }
            }

        } catch (Exception ex) {
            log.error("request:{}  -> exception:{}", request, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void clear() {
        this.executor = null;

        if (request != null) {//单向/有限流处理
            this.request = null;
            FUTURE_MAP.remove(request.getSeq());
        } else if (requestId != null) {
            FUTURE_MAP.remove(requestId);
            this.requestId = null;
            awaitCondition = null;
        }
    }

    @Override
    public void loadParams(Object... params) {
        switch (params.length) {
            case 2: {
                this.executor = (ServerSendUdpMesageExecutor) params[0];
                this.request = (P2PWrapper) params[1];
                this.continued = true;
                Future f = ExecutorServicePool.P2P_REFERENCED_SERVER_ASYNC_POOLS.submit(this);
                FUTURE_MAP.put(request.getSeq(), f);
                break;
            }
            case 3: {
                this.continued = true;
                this.requestId = (Integer) params[0];
                this.streamRequest = (StreamRequest) this;
                this.executor = (ServerSendUdpMesageExecutor) params[1];
                this.streamMessage = (StreamP2PWrapper) params[2];
                Future f = ExecutorServicePool.P2P_REFERENCED_SERVER_ASYNC_POOLS.submit(this);
                FUTURE_MAP.put(requestId, f);
                break;
            }
            default:
                throw new IllegalArgumentException("expected params {ServerSendUdpMesageExecutor,P2PWrapper} or {Integer,ServerSendUdpMesageExecutor,StreamP2PWrapper}");
        }

    }

    @Override
    public P2PWrapper process(P2PWrapper request) {

        throw new RuntimeException(new IllegalAccessException());

    }

    public abstract void processStream(ServerSendUdpMesageExecutor executor, P2PWrapper request) throws InterruptedException;

}
