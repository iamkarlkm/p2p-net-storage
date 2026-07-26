package p2pws.sdk.core_compat;

import org.junit.Test;

import static org.junit.Assert.*;

public class CoreRpcHandlesTest {

    @Test
    public void streamHandleCancelAlwaysCloses() {
        boolean[] closed = {false};
        CoreRpcStreamHandle handle = new CoreRpcStreamHandle(
            () -> {
                throw new RuntimeException("cancel failed");
            },
            () -> closed[0] = true
        );

        try {
            handle.cancel();
        } catch (Exception ignored) {
        }

        assertTrue(closed[0]);
    }

    @Test
    public void eventSubscriptionCancelAlwaysCloses() {
        boolean[] closed = {false};
        CoreRpcEventSubscription sub = new CoreRpcEventSubscription(
            1,
            () -> {
                throw new RuntimeException("cancel failed");
            },
            () -> closed[0] = true
        );

        try {
            sub.cancel();
        } catch (Exception ignored) {
        }

        assertTrue(closed[0]);
    }
}

