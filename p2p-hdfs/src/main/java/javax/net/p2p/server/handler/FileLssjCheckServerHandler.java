
package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.FileSegmentsDataModel;
import javax.net.p2p.model.LssjCheckModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.storage.SharedStorage;
import javax.net.p2p.utils.FileUtil;
import javax.net.p2p.utils.SecurityUtils;

/**
 *
 * @author Administrator
 */
public class FileLssjCheckServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.CHECK_LSSJ_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			if (request.getCommand().getValue() == P2PCommand.CHECK_LSSJ_FILE.getValue()) {
				LssjCheckModel payload = (LssjCheckModel) request.getData();
				//new File("E:/IMAGES");
				File parent = SharedStorage.getStorageLocation(payload.storeId);
				if (parent == null) {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "存储ID对应目录不存在 -> " + payload.storeId);
				}

				File img = new File(parent, payload.fileName);
				String subPath = null;
				if (!img.exists()) {
					//return P2PWrapper.builder(request.getSeq(),P2PCommand.STD_ERROR, "文件不存在 -> "+payload.path);
					img = new File(parent, payload.fileName2);
					if (!img.exists()) {
						img = new File("d:/images", payload.fileName);
						if (!img.exists()) {
							img = new File("d:/images", payload.fileName2);
							if (img.exists()) {
								subPath = payload.fileName2;
								payload.storeId = 101;
							}
						} else {
							subPath = payload.fileName;
							payload.storeId = 101;
						}
					} else {
						subPath = payload.fileName2;
					}
				} else {
					subPath = payload.fileName;
				}
				if (!img.exists()) {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "文件不存在 -> " + payload.fileName);
				}
					
				String md5 = SecurityUtils.getFileMD5String(img);
				if (md5.equals(payload.md5)) {
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
				}
				if(img.length()>P2PConfig.DATA_BLOCK_SIZE){
					FileSegmentsDataModel segments = new FileSegmentsDataModel(payload.storeId, subPath);
					segments.start = 0;segments.length = img.length();
					segments.blockSize = P2PConfig.DATA_BLOCK_SIZE;
					segments.blockData = FileUtil.loadFile(img, segments.start, segments.blockSize);
					segments.md5 = SecurityUtils.getFileMD5String(img);
					segments.blockMd5 = SecurityUtils.toMD5(segments.blockData);
					 return P2PWrapper.build(request.getSeq(),P2PCommand.R_OK_GET_FILE_SEGMENTS,segments);
				}
				FileDataModel rdata = new FileDataModel(payload.storeId, payload.fileName);
				rdata.data = FileUtil.loadFile(img);
				rdata.length = img.length();
				rdata.md5 = md5;
				return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_GET_FILE, rdata);
			} else {
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令内部校验错误！");
			}
		} catch (Exception e) {
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

	
}
