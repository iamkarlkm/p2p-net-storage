
package javax.net.p2p.server.handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class HeartPongServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.HEART_PING;
	}

    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.HEART_PING.getValue()) {
                System.out.println("收到心跳指令");
                return P2PWrapper.build(request.getSeq(),P2PCommand.HEART_PONG, null);
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
			//e.printStackTrace();
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.toString());
        }

    }

}
