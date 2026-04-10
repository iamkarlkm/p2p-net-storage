package javax.net.p2p.client;

import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import javax.net.p2p.common.AbstractSendMesageExecutor;

/**
 * 
 * @author karl
 */
@Slf4j
public final class P2PClientQuic extends AbstractP2PClient {

    public P2PClientQuic(InetSocketAddress remoteServer) {
        this(remoteServer, DEFAULT_QUEUESIZE);
    }

    public P2PClientQuic(InetSocketAddress remoteServer, Integer queueSize) {
        this(remoteServer, queueSize != null ? queueSize : DEFAULT_QUEUESIZE, DEFAULT_CORESIZE);
    }

    public P2PClientQuic(InetSocketAddress remoteServer, Integer queueSize, Integer coreSize) {
        this(remoteServer, queueSize != null ? queueSize : DEFAULT_QUEUESIZE, coreSize != null ? coreSize : DEFAULT_CORESIZE, DEFAULT_MAGIC);
    }

    public P2PClientQuic(InetSocketAddress remoteServer, Integer queueSize, Integer coreSize, Integer magic) {
        super(remoteServer, queueSize != null ? queueSize : DEFAULT_QUEUESIZE, coreSize != null ? coreSize : DEFAULT_CORESIZE, magic != null ? magic : DEFAULT_MAGIC);
    }

    @Override
    public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
        ClientSendQuicMesageExecutor executor = ClientSendQuicMesageExecutor.build(this, queueSize, remoteServer, magic);
        executor.connect(io_work_group, bootstrap);
        sendMesageExecutors.add(executor);
        return executor;
    }
}