package javax.net.p2p.filesync.store;

public interface PersistentQueue<T> extends Iterable<PersistentQueue.Entry<T>> {
    boolean isEmpty();

    int size();

    long enqueue(T value);

    Entry<T> peek();

    Entry<T> get(long entryId);

    boolean remove(long entryId);

    void sync();

    final class Entry<T> {
        private final long id;
        private final T value;

        public Entry(long id, T value) {
            this.id = id;
            this.value = value;
        }

        public long getId() {
            return id;
        }

        public T getValue() {
            return value;
        }
    }
}
