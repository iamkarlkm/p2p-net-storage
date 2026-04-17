
package javax.net.p2p.model;

/**
 * 应用层封包,解包,流控。
 * @author Administrator
 */
public class FrameSegmentModel {
    public int nextSeed;
    public int nextIndex;

    public FrameSegmentModel(int nextSeed, int nextIndex) {
        this.nextSeed = nextSeed;
        this.nextIndex = nextIndex;
    }
    
    
}
