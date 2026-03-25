
package javax.net.p2p.server.handler;

import com.giyo.chdfs.CosUtil;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.HdfsFileDataModel;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Administrator
 */
@Slf4j
public class CosFilePutServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.PUT_COS_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			//StopWatch stopWatch = new StopWatch();
			//System.out.println(request.getSeq() + "-执行开始...");
			//stopWatch.start();
			if (request.getCommand().getValue() == P2PCommand.PUT_COS_FILE.getValue()) {
				HdfsFileDataModel payload = (HdfsFileDataModel) request.getData();
				log.info("CosFilePut: {} -> {}",payload.path,request.getCommand());
				boolean success = CosUtil.write(payload.path, payload.data);
				//stopWatch.stop();
				// 统计执行时间（毫秒）
				//System.out.println("执行时长：" + stopWatch.now(TimeUnit.MILLISECONDS) + " 毫秒.");
				if (success) {
					log.info("CosFilePut success: {}",payload.path);
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, null);
				} else {
					log.info("CosFilePut fialed: {}",payload.path);
					return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, null);
				}

			} else {
				//stopWatch.stop();
				// 统计执行时间（秒）
				// 统计执行时间（毫秒）
				//System.out.println("执行时长：" + stopWatch.now(TimeUnit.MILLISECONDS) + " 毫秒.");
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
			}

		} catch (Exception e) {
			log.info("CosFilePut Exception:{}",e.getMessage());
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

}
