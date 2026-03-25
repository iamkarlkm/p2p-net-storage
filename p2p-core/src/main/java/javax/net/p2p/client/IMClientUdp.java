package javax.net.p2p.client;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;

/**
 * IM客户端UDP实现
 * 继承自AbstractP2PClient，利用连接池管理连接
 */
@Slf4j
public class IMClientUdp extends AbstractP2PClient {

    public IMClientUdp(InetSocketAddress remoteServer) {
        this(remoteServer, DEFAULT_QUEUESIZE);
    }

    public IMClientUdp(InetSocketAddress remoteServer, int queueSize) {
        this(remoteServer, queueSize, DEFAULT_CORESIZE);
    }
    
    public IMClientUdp(InetSocketAddress remoteServer, int queueSize, int coreSize) {
        this(remoteServer, queueSize, coreSize, DEFAULT_MAGIC);
    }
    
    public IMClientUdp(InetSocketAddress remoteServer, int queueSize, int coreSize, int magic) {
        super(remoteServer, queueSize, coreSize, magic);
        // 初始化客户端UDP选项
        PipelineInitializer.initClientOptionsUdp(bootstrap);
    }

    @Override
    public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
        // 建立一个可用连接
        ClientSendMesageExecutor executor = ClientSendUdpMesageExecutor.build(this, queueSize, remoteServer, magic);
        executor.connect(io_work_group, bootstrap);
        sendMesageExecutors.add(executor);
        return executor;
    }
    
    @Override
    public void singletonCreated(Object instance) {
        // 可以在这里进行额外的初始化
        // super.singletonCreated(instance);
        log.info("IMClientUdp singleton created: {}", instance);
        
        // 自动建立初始连接
        if (sendMesageExecutors.isEmpty()) {
             newSendMesageExecutorToQueue();
        }
    }

    @Override
    public void singletonFinalized() {
        super.singletonFinalized();
        log.info("IMClientUdp singleton finalized");
    }
}
