package javax.net.p2p.server.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.api.P2PServiceCategory;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PServiceControlRequest;
import javax.net.p2p.model.P2PServiceControlResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServiceManager;

public class ServiceControlServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.SERVICE_CONTROL;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand().getValue() != P2PCommand.SERVICE_CONTROL.getValue()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "指令分发内部校验错误！");
            }
            P2PServiceControlRequest payload = (P2PServiceControlRequest) request.getData();
            if (payload == null || payload.action == null || payload.action.isBlank()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "missing action");
            }
            String action = payload.action.trim().toLowerCase();
            if ("list".equals(action)) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, snapshot("list"));
            }
            if (!"enable".equals(action) && !"disable".equals(action)) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "unknown action: " + payload.action);
            }
            String cats = payload.categories == null ? "" : payload.categories.trim();
            if (cats.isEmpty()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "missing categories");
            }
            List<P2PServiceCategory> parsed = parseCategories(cats);
            if (parsed.isEmpty()) {
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "no valid categories");
            }
            for (P2PServiceCategory c : parsed) {
                if ("disable".equals(action)) {
                    if (c == P2PServiceCategory.CORE) {
                        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "cannot disable CORE");
                    }
                    P2PServiceManager.disable(c);
                } else {
                    P2PServiceManager.enable(c);
                }
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, snapshot(action));
        } catch (Exception e) {
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, e.toString());
        }
    }

    private static P2PServiceControlResponse snapshot(String action) {
        Set<P2PServiceCategory> disabled = P2PServiceManager.disabledSnapshot();
        String[] arr = new String[disabled.size()];
        int i = 0;
        for (P2PServiceCategory c : disabled) {
            arr[i++] = c.name();
        }
        return new P2PServiceControlResponse(action, arr);
    }

    private static List<P2PServiceCategory> parseCategories(String cats) {
        String[] parts = cats.split(",");
        List<P2PServiceCategory> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String s = p.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(P2PServiceCategory.valueOf(s));
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
