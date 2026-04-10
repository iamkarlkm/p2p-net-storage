package javax.net.p2p.client;

import java.net.InetSocketAddress;
import java.util.UUID;
import javax.net.p2p.channel.PipelineInitializer;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.server.P2PServerWebSocket;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class P2PClientWebSocket extends AbstractP2PClient {

    private final String path;
    private final String sessionId;

    public P2PClientWebSocket(InetSocketAddress remoteServer) {
        this(remoteServer, DEFAULT_QUEUESIZE);
    }

    public P2PClientWebSocket(InetSocketAddress remoteServer, Integer queueSize) {
        this(remoteServer, queueSize == null ? DEFAULT_QUEUESIZE : queueSize.intValue());
    }

    public P2PClientWebSocket(InetSocketAddress remoteServer, Integer queueSize, Integer coreSize) {
        this(
            remoteServer,
            queueSize == null ? DEFAULT_QUEUESIZE : queueSize.intValue(),
            coreSize == null ? DEFAULT_CORESIZE : coreSize.intValue()
        );
    }

    public P2PClientWebSocket(InetSocketAddress remoteServer, int queueSize) {
        this(remoteServer, queueSize, DEFAULT_CORESIZE, DEFAULT_MAGIC, P2PServerWebSocket.DEFAULT_PATH);
    }

    public P2PClientWebSocket(InetSocketAddress remoteServer, int queueSize, int coreSize) {
        this(remoteServer, queueSize, coreSize, DEFAULT_MAGIC, P2PServerWebSocket.DEFAULT_PATH);
    }

    public P2PClientWebSocket(InetSocketAddress remoteServer, int queueSize, int coreSize, int magic, String path) {
        super(remoteServer, queueSize, coreSize, magic);
        this.path = (path == null || path.isBlank()) ? P2PServerWebSocket.DEFAULT_PATH : path;
        String sid = System.getProperty("p2p.ws.sessionId");
        this.sessionId = (sid == null || sid.isBlank()) ? UUID.randomUUID().toString() : sid;
        PipelineInitializer.initClientOptions(bootstrap);
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
        ClientSendMesageExecutor executor = ClientSendWebSocketMesageExecutor.build(this, queueSize, remoteServer, magic, path, sessionId);
        executor.connect(io_work_group, bootstrap);
        sendMesageExecutors.add(executor);
        return executor;
    }
}
