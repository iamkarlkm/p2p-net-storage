package p2pws.sdk.demo;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.rpc.pubsub.proto.PubSubEvent;
import p2pws.sdk.core_compat.CoreRpcEventClient;
import p2pws.sdk.core_compat.CoreRpcEventSubscription;
import p2pws.sdk.core_compat.CoreRpcStreamObserver;
import p2pws.sdk.core_compat.CoreWsClient;

public final class CoreCompatPubSubMain {
    private CoreCompatPubSubMain() {
    }

    public static void main(String[] args) throws Exception {
        String wsUrl = args.length >= 1 ? args[0] : "ws://127.0.0.1:18089/p2p";
        int magic = args.length >= 2 ? Integer.decode(args[1]) : -252702961;
        String privPemPath = args.length >= 3 ? args[2] : null;
        String userId = args.length >= 4 ? args[3] : null;
        String topic = args.length >= 5 ? args[4] : "demo-topic";
        String message = args.length >= 6 ? args[5] : "hello";

        if (privPemPath == null || privPemPath.isEmpty()) {
            throw new IllegalArgumentException("need client private key pem path arg2");
        }
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("need userId arg3");
        }

        String pem = Files.readString(Path.of(privPemPath));
        CoreWsClient c = new CoreWsClient(URI.create(wsUrl), magic, 4096);
        c.connect().join();
        try {
            c.handshakeAndLogin(userId, pem).join();

            CoreRpcEventClient pubsub = new CoreRpcEventClient(c);
            AtomicInteger received = new AtomicInteger(0);
            CountDownLatch firstEvent = new CountDownLatch(1);
            CoreRpcStreamObserver<PubSubEvent> observer = new CoreRpcStreamObserver<>() {
                @Override
                public void onNext(PubSubEvent value) {
                    received.incrementAndGet();
                    System.out.println("event.topic=" + value.getTopic() + " message=" + value.getMessage() + " index=" + value.getIndex());
                    firstEvent.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("subscribe.error=" + error);
                }
            };

            CoreRpcEventSubscription sub = pubsub.subscribe(
                topic,
                2,
                2,
                0,
                0,
                observer,
                Duration.ofSeconds(5)
            ).join();

            boolean published = false;
            for (int i = 0; i < 200; i++) {
                var resp = pubsub.publish(topic, message, Duration.ofSeconds(5)).join();
                System.out.println("publish.accepted=" + resp.getAccepted() + " subscriber_count=" + resp.getSubscriberCount());
                if (resp.getSubscriberCount() > 0) {
                    published = true;
                    break;
                }
                Thread.sleep(100);
            }
            if (!published) {
                throw new IllegalStateException("publish delivered to 0 subscribers");
            }

            boolean got = firstEvent.await(5, TimeUnit.SECONDS);
            if (!got) {
                throw new IllegalStateException("did not receive event in time");
            }

            int beforeCancel = received.get();
            sub.cancel();
            var after = pubsub.publish(topic, message + "-after-cancel", Duration.ofSeconds(5)).join();
            System.out.println("publish_after_cancel.subscriber_count=" + after.getSubscriberCount());
            Thread.sleep(600);
            int afterCancel = received.get();
            if (afterCancel != beforeCancel) {
                throw new IllegalStateException("received events after cancel: before=" + beforeCancel + " after=" + afterCancel);
            }

            System.out.println("OK");
        } finally {
            c.close();
        }
    }
}
