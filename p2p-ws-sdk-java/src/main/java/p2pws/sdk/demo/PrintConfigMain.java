package p2pws.sdk.demo;

import java.nio.file.Path;

public final class PrintConfigMain {

    public static void main(String[] args) {
        Path cfgPath = args.length >= 1 ? Path.of(args[0]) : Path.of("..", "p2p-ws-protocol", "examples", "server.yaml").normalize();
        ServerConfig cfg = YamlConfig.loadServer(cfgPath);
        System.out.println("cfgPath=" + cfgPath.toAbsolutePath().normalize());
        System.out.println("keyfilePath=" + cfg.keyfilePath());
    }
}

