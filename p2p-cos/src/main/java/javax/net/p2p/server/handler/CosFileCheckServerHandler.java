
package javax.net.p2p.server.handler;

import com.q3lives.chdfs.CosUtil;
import com.qcloud.cos.model.ObjectMetadata;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Administrator
 */
@Slf4j
public class CosFileCheckServerHandler implements P2PCommandHandler {
	
	@Override
	public P2PCommand getCommand() {
		return P2PCommand.CHECK_COS_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			if (request.getCommand().getValue() == P2PCommand.CHECK_COS_FILE.getValue()) {
				FileDataModel payload = (FileDataModel) request.getData();
				log.info("CosFileCheck: {} -> {}",payload.path,request.getCommand());
				ObjectMetadata info = CosUtil.getObjectInfo(payload.path);
				log.info("CosFileCheck success -> {} -> {}",payload.path,info.getRawMetadata());
				if (info==null||info.getContentLength()<=0) {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "文件不存在 -> " + payload.path);
				} else {
					
					if (info.getContentLength() != payload.length) {
						return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.format("文件长度不一致 %s <> %s", payload.length, info.getContentLength()));
					} else if (payload.md5 != null) {
						String md5 = info.getContentMD5();
						if (md5 == null) {
							md5 = (String) info.getRawMetadata().get("md5");
							if (md5 == null) {
								md5 = (String) info.getETag();
							}
						}
						if(md5 == null){
							byte[] data = CosUtil.read(payload.path);
							md5 = SecurityUtils.toMD5(data);
						}
						
						if (!md5.equals(payload.md5)) {
							return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, String.format("MD5校验错误 %s <> %s", payload.md5, md5));
						}
					}
				}

				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
			} else {
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
			}
		} catch (Exception e) {
			log.info("CosFileCheck Exception:{}",e.getMessage());
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

	

}
