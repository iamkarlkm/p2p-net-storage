package javax.net.p2p.startup;

import java.util.ArrayList;
import java.util.ServiceLoader;

public final class P2PStartupChecks {
    private P2PStartupChecks() {
    }

    public static void runOrThrow() {
        ServiceLoader<P2PStartupCheck> loader = ServiceLoader.load(P2PStartupCheck.class);
        ArrayList<String> failed = new ArrayList<>();
        for (P2PStartupCheck check : loader) {
            try {
                check.check();
            } catch (Exception e) {
                failed.add(check.getClass().getName() + ": " + (e.getMessage() == null ? e.getClass().getName() : e.getMessage()));
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("startup checks failed: " + failed);
        }
    }
}

