package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

public class ExtAttrsListStringAck {
    public int sourceS = -1;
    public int sourceP = -1;
    public int targetS = -1;
    public int targetP = -1;
    public List<String> values = new ArrayList<>();
    public String error;

    public ExtAttrsListStringAck() {}

    public ExtAttrsListStringAck(int sourceS, int sourceP,
                                 int targetS, int targetP,
                                 List<String> values, String error) {
        this.sourceS = sourceS;
        this.sourceP = sourceP;
        this.targetS = targetS;
        this.targetP = targetP;
        this.values = values;
        this.error = error;
    }
}
