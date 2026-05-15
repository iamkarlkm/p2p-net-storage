package javax.net.p2p.filesync.sync.transport;

import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PMessageService;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.proto.HealthCheckRequest;
import javax.net.p2p.rpc.proto.HealthCheckResponse;
import javax.net.p2p.rpc.proto.RpcFrame;

public final class P2PTransportProbe {

    private P2PTransportProbe() {
    }

    public static boolean probeHealth(P2PMessageService messageService, long timeoutMillis) {
        try {
            RpcFrame frame = RpcFrame.newBuilder()
                .setPayload(HealthCheckRequest.newBuilder().setService("p2p-sync").build().toByteString())
                .build();
            P2PWrapper<byte[]> request = P2PWrapper.build(P2PCommand.RPC_HEALTH, frame.toByteArray());
            P2PWrapper<byte[]> resp = messageService.excute(request, timeoutMillis, TimeUnit.MILLISECONDS);
            if (resp == null || resp.getCommand() != P2PCommand.RPC_HEALTH) {
                return false;
            }
            RpcFrame rf = RpcFrame.parseFrom(resp.getData());
            HealthCheckResponse health = HealthCheckResponse.parseFrom(rf.getPayload());
            return health.getHealthy() && health.getReady();
        } catch (Exception e) {
            return false;
        }
    }
}

