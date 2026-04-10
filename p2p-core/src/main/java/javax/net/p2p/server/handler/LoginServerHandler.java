package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.auth.model.LoginRequest;
import javax.net.p2p.auth.model.LoginResponse;
import javax.net.p2p.auth.utils.AuthCrypto;
import javax.net.p2p.auth.utils.LoginPayloads;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class LoginServerHandler implements P2PChannelAwareCommandHandler {

    private static volatile AuthConfig CONFIG;

    public LoginServerHandler() {
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.LOGIN;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "no ctx");
    }

    @Override
    public P2PWrapper process(io.netty.channel.ChannelHandlerContext ctx, P2PWrapper request) {
        LoginResponse resp = new LoginResponse();
        try {
            Object data = request.getData();
            if (!(data instanceof byte[])) {
                resp.setOk(false);
                resp.setError("invalid request type");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            LoginRequest req = javax.net.p2p.utils.SerializationUtil.deserialize(LoginRequest.class, (byte[]) data);
            AuthConfig cfg = loadConfig();
            if (!cfg.isEnabled() || cfg.getServer() == null) {
                resp.setOk(false);
                resp.setError("auth disabled");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            if (ctx.channel().attr(ChannelUtils.XOR_KEY).get() == null) {
                resp.setOk(false);
                resp.setError("handshake required");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            String userId = req.getUserId();
            if (userId == null || userId.isBlank()) {
                resp.setOk(false);
                resp.setError("missing userId");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            String boundUserId = ctx.channel().attr(ChannelUtils.AUTH_USER_ID).get();
            if (boundUserId == null || !boundUserId.equals(userId)) {
                resp.setOk(false);
                resp.setError("userId mismatch");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            String clientPubKey = cfg.getServer().getClientPublicKeys().get(userId);
            if (clientPubKey == null || clientPubKey.isBlank()) {
                resp.setOk(false);
                resp.setError("unknown userId");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            boolean ok = AuthCrypto.verifySha256Rsa(LoginPayloads.requestSigPayload(req), clientPubKey, req.getSignature());
            if (!ok) {
                resp.setOk(false);
                resp.setError("bad signature");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            ctx.channel().attr(ChannelUtils.AUTH_LOGGED_IN).set(true);
            resp.setOk(true);
            resp.setUserId(userId);
            resp.setServerTime(System.currentTimeMillis());
            if (cfg.getServer().getPrivateKey() != null && !cfg.getServer().getPrivateKey().isBlank()) {
                resp.setSignature(AuthCrypto.signSha256Rsa(LoginPayloads.responseSigPayload(resp), cfg.getServer().getPrivateKey()));
            }
            byte[] respBytes = javax.net.p2p.utils.SerializationUtil.serialize(resp);
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, respBytes);
        } catch (Exception e) {
            resp.setOk(false);
            resp.setError(e.getMessage());
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
        }
    }

    private static AuthConfig loadConfig() {
        AuthConfig local = CONFIG;
        if (local != null) {
            return local;
        }
        synchronized (LoginServerHandler.class) {
            local = CONFIG;
            if (local != null) {
                return local;
            }
            local = AuthConfig.load();
            CONFIG = local;
            return local;
        }
    }
}
