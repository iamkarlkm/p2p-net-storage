
package javax.net.p2p.server.handler;

import java.io.File;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FileDataModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.FileUtil;

/**
 *
 * @author Administrator
 */
public class FileRemoveServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.REMOVE_FILE;
	}

	@Override
	public P2PWrapper process(P2PWrapper request) {
		try {
			if (request.getCommand().getValue() == P2PCommand.REMOVE_FILE.getValue()) {
				FileDataModel payload = (FileDataModel) request.getData();
				File file = FileUtil.getAndCheckExistsSandboxFile(payload.storeId, payload.path);
				file.delete();
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, payload);
			} else {
				return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
			}
		} catch (Exception e) {
			return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
		}

	}

}
