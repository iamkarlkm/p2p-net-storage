
package javax.net.p2p.server.handler;

import com.giyo.chdfs.CosUtil;
import com.qcloud.cos.model.ObjectMetadata;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.HdfsFileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SecurityUtils;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Administrator
 */
@Slf4j
public class CosFileGetServerHandler implements P2PCommandHandler{

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.GET_COS_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			if (request.getCommand().getValue() == P2PCommand.GET_COS_FILE.getValue()) {
				HdfsFileDataModel payload = (HdfsFileDataModel) request.getData();
				log.info("CosFileGet: {} -> {}",payload.path,request.getCommand());
				ObjectMetadata info = CosUtil.getObjectInfo(payload.path);
				if(info.getContentLength()<=P2PConfig.DATA_GET_BLOCK_SIZE){//云出数据可能限制了长度
					payload.data = CosUtil.read(payload.path);
					payload.length = payload.data.length;
					log.info("CosFileGet success: {} -> {}",payload.path,P2PCommand.R_OK_GET_COS_FILE);
					return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_COS_FILE, payload);
				}else{
					FileSegmentsDataModel segments = new FileSegmentsDataModel(0, payload.path);
					segments.start = 0;
					segments.blockIndex  = 0;
					segments.length = info.getContentLength();
					segments.blockSize = P2PConfig.DATA_GET_BLOCK_SIZE;
					segments.blockData = CosUtil.readPart(payload.path, 0, P2PConfig.DATA_GET_BLOCK_SIZE);
					
					segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
					String md5 = info.getContentMD5();
					if(md5==null){
						md5 = (String) info.getRawMetadata().get("md5");
//						if (md5 == null) {
//							md5 = (String) info.getETag();
//						}
					}
					segments.md5 = md5;
					log.info("CosFileGet need segments: {}",payload.path);
					return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE_SEGMENTS, segments);
				}
				
			} else {
				log.info("CosFilePut fialed.");
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
			}
		} catch (Exception e) {
			log.info("CosFileGet Exception:{}",e.getMessage());
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

}
