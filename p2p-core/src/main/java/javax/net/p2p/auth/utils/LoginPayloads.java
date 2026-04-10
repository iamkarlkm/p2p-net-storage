package javax.net.p2p.auth.utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.net.p2p.auth.model.LoginRequest;
import javax.net.p2p.auth.model.LoginResponse;

public final class LoginPayloads {

    private LoginPayloads() {
    }

    public static byte[] requestSigPayload(LoginRequest req) {
        byte[] user = req.getUserId() == null ? new byte[0] : req.getUserId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + user.length);
        buf.putLong(req.getTimestamp());
        buf.put(user);
        return buf.array();
    }

    public static byte[] responseSigPayload(LoginResponse resp) {
        byte[] user = resp.getUserId() == null ? new byte[0] : resp.getUserId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + user.length);
        buf.putLong(resp.getServerTime());
        buf.put(user);
        return buf.array();
    }
}

