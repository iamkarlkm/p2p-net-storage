package javax.net.p2p.model;

import javax.net.p2p.api.P2PCommand;

public class P2PWrapper {
    private int seq;
    private P2PCommand command;
    private Object data;

    public P2PWrapper() {
    }

    public static P2PWrapper build(P2PCommand command, Object data) {
        P2PWrapper w = new P2PWrapper();
        w.command = command;
        w.data = data;
        return w;
    }

    public static P2PWrapper build(int seq, P2PCommand command, Object data) {
        P2PWrapper w = new P2PWrapper();
        w.seq = seq;
        w.command = command;
        w.data = data;
        return w;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public P2PCommand getCommand() {
        return command;
    }

    public void setCommand(P2PCommand command) {
        this.command = command;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
