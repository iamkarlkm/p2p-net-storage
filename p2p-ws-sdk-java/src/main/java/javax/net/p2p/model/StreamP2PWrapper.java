package javax.net.p2p.model;

import javax.net.p2p.api.P2PCommand;

public class StreamP2PWrapper extends P2PWrapper {
    private int index;
    private boolean completed;
    private boolean canceled;

    public StreamP2PWrapper() {
    }

    public static StreamP2PWrapper buildStream(int seq, int index, P2PCommand command, Object data, boolean completed) {
        StreamP2PWrapper w = new StreamP2PWrapper();
        w.setSeq(seq);
        w.setCommand(command);
        w.setData(data);
        w.index = index;
        w.completed = completed;
        return w;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}

