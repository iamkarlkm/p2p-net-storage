package com.q3lives.ds.database;

import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import javax.net.p2p.client.AbstractP2PClient;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DbRowQueryStreamHandleCancelTest {
    @Test
    public void cancelShouldCallCancelExcuteOnlyOnce() throws Exception {
        FakeClient client = new FakeClient();
        Object handle = newHandle(client, 123);

        handle.getClass().getMethod("cancel").invoke(handle);
        handle.getClass().getMethod("cancel").invoke(handle);

        Assertions.assertEquals(1, client.cancelCalls);
        Assertions.assertEquals(123, client.lastCancelSeq);
        Assertions.assertEquals(0, client.excuteCalls);
        client.close();
    }

    private static Object newHandle(AbstractP2PClient client, int seq) throws Exception {
        Class<?> clazz = Class.forName("com.q3lives.ds.database.DsDatabaseServer$DbRowQueryStreamHandle");
        Constructor<?> c = clazz.getDeclaredConstructor(AbstractP2PClient.class, int.class);
        c.setAccessible(true);
        return c.newInstance(client, seq);
    }

    static final class FakeClient extends AbstractP2PClient {
        volatile int cancelCalls;
        volatile int lastCancelSeq;
        volatile int excuteCalls;

        FakeClient() {
            super(new InetSocketAddress("127.0.0.1", 0), 16, 1, 0);
        }

        @Override
        public void cancelExcute(int requestId) {
            cancelCalls++;
            lastCancelSeq = requestId;
        }

        @Override
        public P2PWrapper excute(P2PWrapper request, long timeout, java.util.concurrent.TimeUnit unit) {
            excuteCalls++;
            throw new AssertionError("should not call excute from handle.cancel");
        }

        @Override
        public AbstractSendMesageExecutor newSendMesageExecutorToQueue() {
            return new AbstractSendMesageExecutor(16) {
                @Override
                public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
                }

                @Override
                public void recycle() {
                }
            };
        }
    }
}

