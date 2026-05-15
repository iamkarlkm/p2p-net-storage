package javax.net.p2p.server.handler;

import java.util.concurrent.ThreadLocalRandom;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.AuthClientPublicKeyResolver;
import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.auth.model.HandshakeCryptoMode;
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
            String clientPubKey = AuthClientPublicKeyResolver.resolve(cfg.getServer(), req.getUserId());
            if (clientPubKey == null || clientPubKey.isBlank()) {
                resp.setOk(false);
                resp.setError("unknown userId");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            boolean v2 = req.getSigVersion() >= 2;
            int mode = req.getCryptoMode();
            if (!v2 && mode != HandshakeCryptoMode.CLIENT_RANDOM) {
                v2 = true;
            }
            byte[] reqSigPayload = v2 ? HandshakePayloads.requestSigPayloadV2(req) : HandshakePayloads.requestSigPayload(req);
            if (!AuthCrypto.verifySha256Rsa(reqSigPayload, clientPubKey, req.getSignature())) {
                resp.setOk(false);
                resp.setError("bad signature");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            int keyLen = req.getXorKeyLength() > 0 ? req.getXorKeyLength() : cfg.getXorKeyLength();
            if (keyLen <= 0) {
                keyLen = 4096;
            }
            Integer xorOffset = null;
            byte[] xorKey = null;
            if (mode == HandshakeCryptoMode.CLIENT_RANDOM) {
                if (req.getEncryptedXorKey() == null || req.getEncryptedXorKey().length == 0) {
                    resp.setOk(false);
                    resp.setError("missing encryptedXorKey");
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
                }
                xorKey = AuthCrypto.rsaDecryptLargeWithPublic(req.getEncryptedXorKey(), clientPubKey);
                if (xorKey.length != keyLen) {
                    resp.setOk(false);
                    resp.setError("xorKeyLength mismatch");
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
                }
            } else if (mode == HandshakeCryptoMode.SERVER_RANDOM) {
                if (cfg.getServer().getPrivateKey() == null || cfg.getServer().getPrivateKey().isBlank()) {
                    resp.setOk(false);
                    resp.setError("missing server privateKey");
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
                }
                xorKey = AuthCrypto.randomBytes(keyLen);
                resp.setEncryptedSeed(AuthCrypto.rsaEncryptLargeWithPrivate(xorKey, cfg.getServer().getPrivateKey()));
            } else if (mode == HandshakeCryptoMode.KEYFILE) {
                if (cfg.getXorKeyFile() == null || cfg.getXorKeyFile().isBlank()) {
                    resp.setOk(false);
                    resp.setError("missing xorKeyFile");
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
                }
                byte[] fileBytes = AuthCrypto.readKeyFileBytes(cfg.getXorKeyFile());
                String fileId = AuthCrypto.sha256Hex(fileBytes);
                if (req.getKeyFileId() != null && !req.getKeyFileId().isBlank() && !req.getKeyFileId().equals(fileId)) {
                    resp.setOk(false);
                    resp.setError("keyFileId mismatch");
                    return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
                }
                int off = ThreadLocalRandom.current().nextInt(fileBytes.length);
                xorKey = fileBytes;
                xorOffset = off;
                resp.setKeyFileId(fileId);
                resp.setKeyFileOffset(off);
            } else if (mode == HandshakeCryptoMode.PLAIN) {
                xorKey = null;
            } else {
                resp.setOk(false);
                resp.setError("unknown cryptoMode");
                return P2PWrapper.build(request.getSeq(), P2PCommand.STD_ERROR, resp);
            }

            resp.setOk(true);
            resp.setUserId(req.getUserId());
            resp.setServerTime(System.currentTimeMillis());
            resp.setNonce(req.getNonce());
            resp.setXorKeyLength(keyLen);
            resp.setSigVersion(v2 ? 2 : 0);
            resp.setCryptoMode(mode);

            byte[] respSigPayload = v2 ? HandshakePayloads.responseSigPayloadV2(resp) : HandshakePayloads.responseSigPayload(resp);
            if (cfg.getServer().getPrivateKey() != null && !cfg.getServer().getPrivateKey().isBlank()) {
                resp.setSignature(AuthCrypto.signSha256Rsa(respSigPayload, cfg.getServer().getPrivateKey()));
            }

            ctx.channel().attr(ChannelUtils.AUTH_USER_ID).set(req.getUserId());
            ctx.channel().attr(ChannelUtils.AUTH_HANDSHAKE_DONE).set(true);
            ctx.channel().attr(ChannelUtils.AUTH_CRYPTO_MODE).set(mode);
            if (xorKey != null && xorKey.length > 0) {
                ctx.channel().attr(ChannelUtils.XOR_KEY).set(xorKey);
                ctx.channel().attr(ChannelUtils.XOR_OFFSET).set(xorOffset == null ? 0 : xorOffset);
            } else {
                ctx.channel().attr(ChannelUtils.XOR_KEY).set(null);
                ctx.channel().attr(ChannelUtils.XOR_OFFSET).set(null);
            }
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
