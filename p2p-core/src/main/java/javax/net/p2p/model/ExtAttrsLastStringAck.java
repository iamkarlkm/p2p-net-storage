package javax.net.p2p.model;

public class ExtAttrsLastStringAck {
    public int sourceS = -1;
    public int sourceP = -1;
    public int targetS = -1;
    public int targetP = -1;
    public boolean present;
    public String stringValue;
    public String error;

    public ExtAttrsLastStringAck() {}

    public ExtAttrsLastStringAck(int sourceS, int sourceP,
                                 int targetS, int targetP,
                                 boolean present, String stringValue, String error) {
        this.sourceS = sourceS;
        this.sourceP = sourceP;
        this.targetS = targetS;
        this.targetP = targetP;
        this.present = present;
        this.stringValue = stringValue;
        this.error = error;
    }
}
