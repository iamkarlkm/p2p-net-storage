
package javax.net.p2p.server.handler;

import com.giyo.chdfs.CosUtil;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class CosFileSegmentsPutServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.PUT_COS_FILE_SEGMENTS;
	}
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.PUT_COS_FILE_SEGMENTS.getValue()) {
                //P2PWrapper r = null;
                FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
               CosUtil.writePart(payload.path, payload.blockIndex, payload.blockData,payload.blockMd5);
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_OK, null);
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.toString());
        }

    }

}
