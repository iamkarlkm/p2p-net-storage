package javax.net.p2p.startup;

import org.junit.jupiter.api.Test;

public class P2PStartupChecksTest {
    @Test
    public void runOrThrowShouldPassWhenNoChecksPresent() {
        P2PStartupChecks.runOrThrow();
    }
}

