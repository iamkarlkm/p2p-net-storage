package javax.net.p2p.channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import javax.net.p2p.common.pool.ClonePooledObjects;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.common.pool.ClonePooledableAdapter;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.CancelP2PWrapper;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.ServerSendUdpMesageExecutor;
import lombok.extern.slf4j.Slf4j;

/**
 * 流消息处理基类
 *
 * @author karl
 */
@Slf4j
public abstract class AbstractLongTimedRequestAdapter extends ClonePooledableAdapter implements P2PCommandHandler, Runnable {

    /**
     * 流消息序列号对应的Future
     */
    private static final ConcurrentMap<Integer, Future> FUTURE_MAP = new ConcurrentHashMap<>(4096);
    
    private static final ClonePooledObjects<AbstractLongTimedRequestAdapter> TASK_POOL = new ClonePooledObjects(4096);
   
    protected AbstractSendMesageExecutor executor;
    protected P2PWrapper request;

    Future future = null;
    protected volatile boolean canceled = false;
   

    @Override
    public void run() {
        try {
            //log.info("AbstractLongTimedRequestAdapter \n run  -> request:{}", request);
            P2PWrapper response = process(request);
//            log.info("AbstractLongTimedRequestAdapter  \n return  -> response:{}", response);
            if (!canceled) {
                executor.sendResponse(response);
            }
        } catch (InterruptedException ex) {
            try {
                canceled = true;
                if (executor != null && request != null) {
                    executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_CANCEL, "canceled"));
                }
            } catch (Exception ignored) {
            }
        }finally{
            release();//回归对象池
        }
        

    }

    @Override
    public void clear() {
        this.executor = null;
        future = FUTURE_MAP.remove(request.getSeq());
        this.request = null;
        canceled = false;
    }
   
    @Override
    public void loadParams(Object... params) {
        this.executor = (AbstractSendMesageExecutor) params[0];
        this.request = (P2PWrapper) params[1];
        if(future!=null && !future.isDone()){
            try{
                log.error("unexpected future state,loadParams() future.isDone {}",future.isDone());
                //可能线程死锁,取消之
               future.cancel(true);
            }catch(Exception e){
                log.error(e.getMessage());
            }
        }
        future = ExecutorServicePool.P2P_REFERENCED_SERVER_ASYNC_POOLS.submit(this);
        FUTURE_MAP.put(request.getSeq(),future);
        
        
    }
    
  

    public AbstractLongTimedRequestAdapter asyncProcess(AbstractSendMesageExecutor executor, AbstractLongTimedRequestAdapter handler, P2PWrapper request) {
        try {
            //一次性的单向/有限流处理
            //带参数重用对象AbstractLongTimedRequestAdapter类必须@Override loadParams(executor,request)
            return TASK_POOL.pollParamsOrClone(handler,executor,request);
        } catch (Exception ex) {
            log.error("request:{}  -> exception:{}", request, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }
    
    public void asyncProcess(P2PWrapper request) {
        try {
//            log.info("AbstractLongTimedRequestAdapter:{} \n -> request:{}", handler, request);
            boolean isCancel = request instanceof CancelP2PWrapper;
            if (!isCancel) {
                P2PCommand cmd = request.getCommand();
                isCancel = cmd == P2PCommand.STD_CANCEL || cmd == P2PCommand.STD_STOP;
            }
            if (isCancel) {
                canceled = true;
                Future f = FUTURE_MAP.remove(request.getSeq());
                if (f != null) {
                    f.cancel(true);
                }
                if (executor != null) {
                    executor.sendResponse(P2PWrapper.build(request.getSeq(), P2PCommand.STD_CANCEL, "canceled"));
                }
            }
            
        } catch (Exception ex) {
            log.error("request:{}  -> exception:{}", request, ex.getMessage());
            throw new RuntimeException(ex);
        }
    }



}
