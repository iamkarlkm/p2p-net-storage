package p2pws.sdk.demo;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import p2pws.sdk.core_compat.CoreRpcClient;
import p2pws.sdk.core_compat.CoreWsClient;

public final class CoreCompatWsClientMain {
    private CoreCompatWsClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length >= 1 ? args[0] : "ws://127.0.0.1:18089/p2p";
        int magic = args.length >= 2 ? Integer.decode(args[1]) : -252702961;
        String privPemPath = args.length >= 3 ? args[2] : null;
        String userId = args.length >= 4 ? args[3] : "example-user-id";
        String msg = args.length >= 5 ? args[4] : "hello from java core_compat";

        if (privPemPath == null) {
            throw new IllegalArgumentException("need client private key pem path arg2");
        }

        String pem = Files.readString(Path.of(privPemPath));
        CoreWsClient c = new CoreWsClient(URI.create(wsUrl), magic, 4096);
        c.connect().join();
        try {
            c.handshakeAndLogin(userId, pem).join();
            CoreRpcClient rpc = new CoreRpcClient(c);
            var disc = rpc.discover("", true).join();
            System.out.println("discover.services=" + disc.getServicesCount());
            for (int i = 0; i < Math.min(10, disc.getServicesCount()); i++) {
                var s = disc.getServices(i);
                System.out.println("  - " + s.getService() + " version=" + s.getVersion() + " methods=" + s.getMethodsCount());
            }
            var health = rpc.health("p2p.rpc.echo.v1.EchoService").join();
            System.out.println("health.healthy=" + health.getHealthy() + " ready=" + health.getReady() + " message=" + health.getMessage());
            var echo = rpc.echo(msg).join();
            System.out.println("echo.message=" + echo.getMessage() + " server_time=" + echo.getServerTime());
        } finally {
            c.close();
        }
    }
}

