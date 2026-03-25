package javax.net.p2p.health;

public class HealthRunner {
    public static void main(String[] args) throws Exception {
        int port = 18080;
        if (args != null && args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (Exception ignored) {
            }
        }
        HealthHttpServer s = new HealthHttpServer(port);
        s.start();
        Thread.sleep(Long.MAX_VALUE);
    }
}
