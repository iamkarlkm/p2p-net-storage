package javax.net.p2p.server.handler;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.auth.model.HandshakeRequest;
import javax.net.p2p.auth.model.HandshakeResponse;
import javax.net.p2p.auth.utils.AuthCrypto;
import javax.net.p2p.auth.utils.HandshakePayloads;
import javax.net.p2p.channel.ChannelUtils;
import javax.net.p2p.interfaces.P2PChannelAwareCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class HandServerHandler implements P2PChannelAwareCommandHandler {

    private static volatile AuthConfig CONFIG;

    public HandServerHandler() {
    }

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.HAND;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, "no ctx");
    }

    @Override
    public P2PWrapper process(io.netty.channel.ChannelHandlerContext ctx, P2PWrapper request) {
        HandshakeResponse resp = new HandshakeResponse();
        try {
            boolean plaintextResp = ctx.channel().attr(ChannelUtils.XOR_KEY).get() == null;
            Object data = request.getData();
            if (!(data instanceof byte[])) {
                resp.setOk(false);
                resp.setError("invalid request type");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            HandshakeRequest req = javax.net.p2p.utils.SerializationUtil.deserialize(HandshakeRequest.class, (byte[]) data);
            AuthConfig cfg = loadConfig();
            if (!cfg.isEnabled() || cfg.getServer() == null) {
                resp.setOk(false);
                resp.setError("auth disabled");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            if (req.getUserId() == null || req.getUserId().isBlank()) {
                resp.setOk(false);
                resp.setError("missing userId");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            String clientPubKey = cfg.getServer().getClientPublicKeys().get(req.getUserId());
            if (clientPubKey == null || clientPubKey.isBlank()) {
                resp.setOk(false);
                resp.setError("unknown userId");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            byte[] reqSigPayload = HandshakePayloads.requestSigPayload(req);
            if (!AuthCrypto.verifySha256Rsa(reqSigPayload, clientPubKey, req.getSignature())) {
                resp.setOk(false);
                resp.setError("bad signature");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            int keyLen = req.getXorKeyLength() > 0 ? req.getXorKeyLength() : cfg.getXorKeyLength();
            if (keyLen <= 0) {
                keyLen = 4096;
            }

            if (req.getEncryptedXorKey() == null || req.getEncryptedXorKey().length == 0) {
                resp.setOk(false);
                resp.setError("missing encryptedXorKey");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }
            byte[] xorKey = AuthCrypto.rsaDecryptLargeWithPublic(req.getEncryptedXorKey(), clientPubKey);
            if (xorKey.length != keyLen) {
                resp.setOk(false);
                resp.setError("xorKeyLength mismatch");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            resp.setOk(true);
            resp.setUserId(req.getUserId());
            resp.setServerTime(System.currentTimeMillis());
            resp.setNonce(req.getNonce());
            resp.setXorKeyLength(keyLen);

            byte[] respSigPayload = HandshakePayloads.responseSigPayload(resp);
            if (cfg.getServer().getPrivateKey() != null && !cfg.getServer().getPrivateKey().isBlank()) {
                resp.setSignature(AuthCrypto.signSha256Rsa(respSigPayload, cfg.getServer().getPrivateKey()));
            }

            ctx.channel().attr(ChannelUtils.AUTH_USER_ID).set(req.getUserId());
            ctx.channel().attr(ChannelUtils.XOR_KEY).set(xorKey);
            ctx.channel().attr(ChannelUtils.AUTH_LOGGED_IN).set(false);
            if (plaintextResp) {
                ctx.channel().attr(ChannelUtils.HANDSHAKE_PLAINTEXT_RESP).set(true);
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
        synchronized (HandServerHandler.class) {
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
