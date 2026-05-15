package javax.net.p2p.filesync.sync.transport;

import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.interfaces.P2PMessageService;

public final class P2PTransportClient implements AutoCloseable {

    private final P2PTransport transport;
    private final AbstractP2PClient client;

    public P2PTransportClient(P2PTransport transport, AbstractP2PClient client) {
        this.transport = transport;
        this.client = client;
    }

    public P2PTransport getTransport() {
        return transport;
    }

    public P2PMessageService getMessageService() {
        return client;
    }

    @Override
    public void close() {
        client.close();
    }
}

