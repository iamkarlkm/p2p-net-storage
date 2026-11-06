package javax.net.p2p.model;

public class ExtAttrsWriteAck {
    public int sourceS = -1;
    public int sourceP = -1;
    public int targetS = -1;
    public int targetP = -1;
    public boolean ok;
    public long numericValue;
    public String error;

    public ExtAttrsWriteAck() {}

    public ExtAttrsWriteAck(int sourceS, int sourceP,
                            int targetS, int targetP,
                            boolean ok, long numericValue, String error) {
        this.sourceS = sourceS;
        this.sourceP = sourceP;
        this.targetS = targetS;
        this.targetP = targetP;
        this.ok = ok;
        this.numericValue = numericValue;
        this.error = error;
    }
}
