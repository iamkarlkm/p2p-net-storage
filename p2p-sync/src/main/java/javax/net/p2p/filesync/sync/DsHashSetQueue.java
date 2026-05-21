package javax.net.p2p.filesync.sync;

import com.q3lives.ds.collections.DsHashSet;
import java.util.Iterator;
import java.util.Objects;

final class DsHashSetQueue implements PersistentLongQueue {

    private final DsHashSet set;

    DsHashSetQueue(DsHashSet set) {
        this.set = Objects.requireNonNull(set, "set");
    }

    @Override
    public boolean isEmpty() {
        return set.isEmpty();
    }

    @Override
    public int size() {
        return set.size();
    }

    @Override
    public Iterator<Long> iterator() {
        return set.iterator();
    }

    @Override
    public void add(long value) {
        set.add(Long.valueOf(value));
    }

    @Override
    public boolean remove(long value) {
        return set.remove(Long.valueOf(value));
    }

    @Override
    public void sync() {
        set.sync();
    }
}

