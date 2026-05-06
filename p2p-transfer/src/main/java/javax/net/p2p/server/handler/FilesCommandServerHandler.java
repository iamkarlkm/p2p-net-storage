package javax.net.p2p.server.handler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.FilesCommandModel;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.storage.SharedStorage;
import javax.net.p2p.utils.FileUtil;

/**
 *
 * @author Administrator
 */
public class FilesCommandServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FILES_COMMAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        return P2PErrors.stdError(
            request.getSeq(),
            P2PErrorCode.UNSUPPORTED,
            "FILES_COMMAND is deprecated, use explicit commands (FILE_RENAME/FILE_LIST/...)"
        );

    }

}
