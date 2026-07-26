package javax.net.p2p.filesync.sync;

import java.util.Iterator;

public interface PersistentLongQueue extends Iterable<Long> {
    boolean isEmpty();

    int size();

    @Override
    Iterator<Long> iterator();

    void add(long value);

    boolean remove(long value);

    void sync();
}
