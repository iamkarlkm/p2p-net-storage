
package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

/**
 *
 * @author Administrator
 */
public class EchoServerHandler implements P2PCommandHandler {

	@Override
	public P2PCommand getCommand() {
		return P2PCommand.ECHO;
	}
    
    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() == P2PCommand.ECHO.getValue()) {
                System.out.println("request:"+request.toString());
                return P2PWrapper.build(request.getSeq(),P2PCommand.ECHO, "Server echo -> "+request.getData());
            } else {
                return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, "指令内部校验错误！");
            }
        } catch (Exception e) {
			//e.printStackTrace();
            return P2PWrapper.build(request.getSeq(),P2PCommand.STD_ERROR, e.toString());
        }

    }

}
