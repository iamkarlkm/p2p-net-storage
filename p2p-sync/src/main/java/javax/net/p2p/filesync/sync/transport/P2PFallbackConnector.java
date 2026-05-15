package javax.net.p2p.filesync.sync.transport;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.client.P2PClientWebSocket;

public final class P2PFallbackConnector {

    private P2PFallbackConnector() {
    }

    public static P2PTransportClient connect(InetSocketAddress endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");

        List<P2PTransport> order = new ArrayList<>();
        order.add(P2PTransport.QUIC);
        order.add(P2PTransport.TCP);
        order.add(P2PTransport.UDP);
        order.add(P2PTransport.WEBSOCKET);

        RuntimeException last = null;
        for (P2PTransport t : order) {
            AbstractP2PClient client = null;
            try {
                client = buildClient(t, endpoint);
                boolean ok = P2PTransportProbe.probeHealth(client, 1500);
                if (ok) {
                    return new P2PTransportClient(t, client);
                }
                client.close();
            } catch (Exception e) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
                last = new RuntimeException("connect failed: " + t + " -> " + endpoint, e);
            }
        }
        throw last == null ? new RuntimeException("connect failed: " + endpoint) : last;
    }

    private static AbstractP2PClient buildClient(P2PTransport transport, InetSocketAddress endpoint) {
        if (transport == P2PTransport.QUIC) {
            return new P2PClientQuic(endpoint);
        }
        if (transport == P2PTransport.TCP) {
            return new P2PClientTcp(endpoint);
        }
        if (transport == P2PTransport.UDP) {
            return new P2PClientUdp(endpoint);
        }
        return new P2PClientWebSocket(endpoint);
    }
}
