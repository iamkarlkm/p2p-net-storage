package p2pws.sdk.core_compat;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoreRpcEventSubscription implements AutoCloseable {
    private final int requestId;
    private final Runnable cancelAction;
    private final Runnable closeAction;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    CoreRpcEventSubscription(int requestId, Runnable cancelAction, Runnable closeAction) {
        this.requestId = requestId;
        this.cancelAction = Objects.requireNonNull(cancelAction, "cancelAction");
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
    }

    public int requestId() {
        return requestId;
    }

    public void cancel() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            cancelAction.run();
        } finally {
            closeAction.run();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeAction.run();
    }
}
