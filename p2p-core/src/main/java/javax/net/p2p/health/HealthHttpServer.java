package javax.net.p2p.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
/**
 * HealthHttpServer。
 */

public class HealthHttpServer {
    private final int port;
    private HttpServer server;

    public HealthHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"Method Not Allowed\"}", "application/json");
                return;
            }
            Map<String, Object> body = new HashMap<>();
            body.put("status", "UP");
            body.put("timestamp", System.currentTimeMillis());
            String json = "{\"status\":\"UP\",\"timestamp\":" + body.get("timestamp") + "}";
            send(exchange, 200, json, "application/json");
        }

        private void send(HttpExchange exchange, int statusCode, String content, String contentType) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
