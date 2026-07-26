package javax.net.p2p.filesync.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DsPersistentQueueTest {

    @Test
    public void shouldPersistAndIterateInOrder() throws Exception {
        Path home = Files.createTempDirectory("p2p_sync_ds_queue_");

        long id1;
        long id2;
        try (DsPersistentQueue<String> q = new DsPersistentQueue<>(home, "q1", PersistentCodec.stringCodec())) {
            id1 = q.enqueue("a");
            id2 = q.enqueue("b");
            q.sync();

            Assert.assertEquals(2, q.size());
            Assert.assertEquals("a", q.peek().getValue());

            List<String> values = new ArrayList<>();
            for (PersistentQueue.Entry<String> e : q) {
                values.add(e.getValue());
            }
            Assert.assertEquals(java.util.Arrays.asList("a", "b"), values);
        }

        try (DsPersistentQueue<String> q = new DsPersistentQueue<>(home, "q1", PersistentCodec.stringCodec())) {
            Assert.assertEquals(2, q.size());
            Assert.assertEquals("a", q.get(id1).getValue());
            Assert.assertEquals("b", q.get(id2).getValue());

            Assert.assertTrue(q.remove(id1));
            Assert.assertEquals(1, q.size());

            long id3 = q.enqueue("c");
            Assert.assertTrue(id3 > id2);
            q.sync();
        }

        try (DsPersistentQueue<String> q = new DsPersistentQueue<>(home, "q1", PersistentCodec.stringCodec())) {
            List<String> values = new ArrayList<>();
            for (PersistentQueue.Entry<String> e : q) {
                values.add(e.getValue());
            }
            Assert.assertEquals(java.util.Arrays.asList("b", "c"), values);
        }
    }

    @Test
    public void shouldHandleDuplicatePayloadRefCounts() throws Exception {
        Path home = Files.createTempDirectory("p2p_sync_ds_queue_dup_");

        try (DsPersistentQueue<String> q = new DsPersistentQueue<>(home, "qdup", PersistentCodec.stringCodec())) {
            long id1 = q.enqueue("x");
            long id2 = q.enqueue("x");
            q.sync();

            Assert.assertEquals(2, q.size());
            Assert.assertTrue(q.remove(id1));
            Assert.assertEquals("x", q.get(id2).getValue());
        }
    }
}
