package javax.net.p2p.health;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class HealthHttpServerTest {
    private HealthHttpServer server;
    private final int port = 8099;

    @Before
    public void setUp() throws Exception {
        server = new HealthHttpServer(port);
        server.start();
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void testHealthEndpoint() throws Exception {
        URL url = new URL("http://localhost:" + port + "/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        Assert.assertEquals(200, code);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            String body = sb.toString();
            Assert.assertTrue(body.contains("\"status\":\"UP\""));
        }
    }
}
