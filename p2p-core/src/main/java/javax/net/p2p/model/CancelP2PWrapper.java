package javax.net.p2p.model;

/**
 * <p>
 * 取消对应seq序列号的长耗时操作
 * </p>
 *
 * @author iamkarl@163.com
 */
public class CancelP2PWrapper extends P2PWrapper {
    
    
    private final boolean canceled = true;

    public CancelP2PWrapper(int seq) {
        this.seq = seq;
    }
    
   
    @Override
    public String toString() {
        return "seq:" + seq  + ",index:" +  ",canceled:" + canceled+ ",command:" + command + ",data:" + data;
    }

}
