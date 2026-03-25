
package javax.net.p2p.server.handler;

import com.giyo.chdfs.CosUtil;
import com.qcloud.cos.model.ObjectMetadata;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Administrator
 */
@Slf4j
public class CosFileGetSegmentsServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.GET_COS_FILE_SEGMENTS;
	}
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
       try {
				if (request.getCommand().getValue() == P2PCommand.GET_COS_FILE_SEGMENTS.getValue()) {
					FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
					log.info("CosFileGetSegments: {} -> {}",payload.path,request.getCommand());
					payload.blockData = CosUtil.readPart(payload.path, payload.start, payload.blockSize);
					payload.blockMd5 = SecurityUtils.toMD5(payload.blockData);
					log.info("CosFileGetSegments success: {} -> payload.blockMd5:{}",payload.path,payload.blockMd5);
					if (payload.md5 == null) {
						ObjectMetadata info = CosUtil.getObjectInfo(payload.path);
						payload.md5 = info.getETag();
					}
					return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, payload);
				} else {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
				}
			} catch (Exception e) {
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
			}

    }

}
