package javax.net.p2p.common;

import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MessageServiceSeqPreserveTest {
    @Test
    public void excuteShouldPreserveNonZeroSeq() throws Exception {
        TestMessageService svc = new TestMessageService();
        P2PWrapper req = P2PWrapper.build(123, P2PCommand.STD_CANCEL, null);
        P2PWrapper resp = svc.excute(req, 1, TimeUnit.SECONDS);

        Assertions.assertEquals(123, req.getSeq());
        Assertions.assertEquals(123, svc.lastSeenSeq);
        Assertions.assertEquals(123, resp.getSeq());
    }

    @Test
    public void cancelExcuteShouldSendStdCancelWithGivenSeq() {
        TestMessageService svc = new TestMessageService();
        svc.cancelExcute(456);

        Assertions.assertNotNull(svc.lastSent);
        Assertions.assertEquals(456, svc.lastSent.getSeq());
        Assertions.assertEquals(P2PCommand.STD_CANCEL, svc.lastSent.getCommand());
    }

    static final class TestMessageService extends AbstractP2PMessageServiceAdapter {
        volatile int lastSeenSeq;
        volatile P2PWrapper lastSent;

        TestMessageService() {
            super(16, 1, 0);
        }

        @Override
        public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
            FakeExecutor ex = new FakeExecutor(this);
            sendMesageExecutors.add(ex);
            return ex;
        }
    }

    static final class FakeExecutor extends AbstractSendMesageExecutor {
        private final TestMessageService svc;

        FakeExecutor(TestMessageService svc) {
            super(16);
            this.svc = svc;
            this.connected = true;
        }

        @Override
        public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
        }

        @Override
        public P2PWrapper syncExcute(P2PWrapper request, long timeout, TimeUnit unit) {
            svc.lastSeenSeq = request.getSeq();
            return P2PWrapper.build(request.getSeq(), request.getCommand(), "ok");
        }

        @Override
        public void sendMessage(P2PWrapper message) {
            svc.lastSent = message;
        }

        @Override
        public void recycle() {
        }
    }
}
