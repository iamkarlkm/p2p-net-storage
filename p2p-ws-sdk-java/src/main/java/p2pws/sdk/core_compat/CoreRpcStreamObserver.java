package p2pws.sdk.core_compat;

public interface CoreRpcStreamObserver<T> {
    void onNext(T value);

    default void onResponseContext(CoreRpcResponseContext context) {
    }

    default void onCompleted() {
    }

    default void onError(Throwable error) {
    }
}
