
package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileSegmentsPutServerHandler extends AbstractLongTimedRequestAdapter implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.PUT_FILE_SEGMENTS;
	}
    
    @Override
	public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.PUT_FILE_SEGMENTS.getValue()) {
                FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
               if(payload.blockMd5!=null && !payload.blockMd5.equals(SecurityUtils.toMD5(payload.blockData))){
				   return P2PWrapper.build(request.getSeq(),P2PCommand.INVALID_DATA, "Md5 check error -> "+payload.blockMd5);
			   }
               File file = FileUtil.getSandboxFileForWrite(payload.storeId, payload.path);				
               FileUtil.storeFile(file,payload.start,payload.blockData.length,payload.blockData);
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_OK, null);
            } else {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.FILE_IO_ERROR, e.toString());
        }

    }

}
