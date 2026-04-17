package javax.net.p2p.model;

public class P2PServiceControlRequest {
    public String action;
    public String categories;

    public P2PServiceControlRequest() {
    }

    public P2PServiceControlRequest(String action, String categories) {
        this.action = action;
        this.categories = categories;
    }
}

