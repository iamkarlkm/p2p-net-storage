package javax.net.p2p.model;

public class P2PServiceControlResponse {
    public String action;
    public String[] disabled;

    public P2PServiceControlResponse() {
    }

    public P2PServiceControlResponse(String action, String[] disabled) {
        this.action = action;
        this.disabled = disabled;
    }
}

