
package javax.net.p2p.server.handler;

import com.q3lives.chdfs.CosUtil;
import com.qcloud.cos.model.ObjectMetadata;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class CosFileSegmentsCompleteServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.PUT_COS_FILE_SEGMENTS_COMPLETE;
	}
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.PUT_FILE_SEGMENTS_COMPLETE.getValue()) {
                //P2PWrapper r = null;
                FileSegmentsDataModel payload = (FileSegmentsDataModel) request.getData();
               	ObjectMetadata info = CosUtil.completePart(payload.path,payload.md5,payload.length);
			   
			   if (info == null) {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "对象不存在 -> " + payload.path);
				}
				if (info.getContentLength() != payload.length) {
						return P2PWrapper.build(request.getSeq(), P2PCommand.INVALID_DATA, String.format("文件长度不一致 %s <> %s", payload.length, info.getContentLength()));
					} else if (payload.md5 != null) {
						if (!payload.md5.equals(info.getETag())) {
							return P2PWrapper.build(request.getSeq(), P2PCommand.INVALID_DATA, String.format("MD5校验错误 %s <> %s", payload.md5, info.getETag()));
						}
					}

                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_OK, null);
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.toString());
        }

    }

}
