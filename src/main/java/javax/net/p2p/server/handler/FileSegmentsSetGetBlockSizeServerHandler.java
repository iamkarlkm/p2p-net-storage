
package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.config.P2PConfig;
/**
 *
 * @author Administrator
 */
public class FileSegmentsSetGetBlockSizeServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.SET_FILE_SEGMENTS_GET_BLOCK_SIZE;
	}
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.SET_FILE_SEGMENTS_GET_BLOCK_SIZE.getValue()) {
							FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
               P2PConfig.DATA_GET_BLOCK_SIZE = payload.blockSize;
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_OK, null);
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.toString());
        }

    }

}
