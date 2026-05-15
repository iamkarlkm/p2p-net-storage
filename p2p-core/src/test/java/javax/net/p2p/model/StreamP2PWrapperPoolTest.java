package javax.net.p2p.model;

import javax.net.p2p.api.P2PCommand;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class StreamP2PWrapperPoolTest {

    @Test
    void recycledWrapperDoesNotLeakFlags() {
        StreamP2PWrapper<byte[]> canceled = StreamP2PWrapper.buildStream(1, true);
        Assertions.assertTrue(canceled.isCanceled());
        canceled.recycle();

        StreamP2PWrapper<byte[]> normal = StreamP2PWrapper.buildStream(2, 0, P2PCommand.RPC_EVENT, new byte[0]);
        Assertions.assertFalse(normal.isCanceled());
        Assertions.assertFalse(normal.isCompleted());
        Assertions.assertEquals(0, normal.getIndex());
        normal.recycle();

        StreamP2PWrapper<byte[]> completed = StreamP2PWrapper.buildStream(3, 7, P2PCommand.RPC_EVENT, new byte[0], true);
        Assertions.assertFalse(completed.isCanceled());
        Assertions.assertTrue(completed.isCompleted());
        Assertions.assertEquals(7, completed.getIndex());
        completed.recycle();

        StreamP2PWrapper<byte[]> next = StreamP2PWrapper.buildStream(4, 0, P2PCommand.RPC_EVENT, new byte[0], false);
        Assertions.assertFalse(next.isCanceled());
        Assertions.assertFalse(next.isCompleted());
        Assertions.assertEquals(0, next.getIndex());
        next.recycle();
    }
}

