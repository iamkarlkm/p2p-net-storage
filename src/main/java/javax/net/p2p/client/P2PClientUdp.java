package javax.net.p2p.client;

import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.AbstractSendMesageExecutor;

/**
 * 单一客户端多路(channel池)连接单一服务器
 * @author karl
 */
@Slf4j
public final class P2PClientUdp extends AbstractP2PClient {

    public P2PClientUdp(InetSocketAddress remoteServer) {
        this(remoteServer,DEFAULT_QUEUESIZE);
    }

    public P2PClientUdp(InetSocketAddress remoteServer, int queueSize) {
        this(remoteServer,DEFAULT_QUEUESIZE,DEFAULT_CORESIZE);
    }
    
    public P2PClientUdp(InetSocketAddress remoteServer, int queueSize,int coreSize) {
        this(remoteServer,DEFAULT_QUEUESIZE,DEFAULT_CORESIZE,DEFAULT_MAGIC);
    }
    
    public P2PClientUdp(InetSocketAddress remoteServer, int queueSize,int coreSize, int magic) {
        super(remoteServer,queueSize, coreSize, magic);
        
        //立即建立一个可用连接
        //ClientSendMesageExecutor executor = ClientSendUdpMesageExecutor.build(this, queueSize, remoteServer,magic);
        //配置tcp连接选项
        PipelineInitializer.initOptionsUdp(bootstrap);
        //executor.connect(io_work_group, bootstrap);
        //clientSendMesageExecutors.add(executor);
    }

    @Override
    public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
        //建立一个可用连接
        ClientSendMesageExecutor executor = ClientSendUdpMesageExecutor.build(this, queueSize, remoteServer,magic);
        executor.connect(io_work_group, bootstrap);
        sendMesageExecutors.add(executor);
        return executor;
    }

   
}
