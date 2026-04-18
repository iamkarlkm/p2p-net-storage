package javax.net.p2p.model;

public class P2PPubSubMessage {
    public String topic;
    public String message;

    public P2PPubSubMessage() {
    }

    public P2PPubSubMessage(String topic, String message) {
        this.topic = topic;
        this.message = message;
    }
}

