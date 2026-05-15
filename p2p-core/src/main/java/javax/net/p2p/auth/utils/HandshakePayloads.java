package javax.net.p2p.auth.utils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.net.p2p.auth.model.HandshakeRequest;
import javax.net.p2p.auth.model.HandshakeResponse;

public final class HandshakePayloads {

    private HandshakePayloads() {
    }

    public static byte[] requestSigPayload(HandshakeRequest req) {
        byte[] user = req.getUserId() == null ? new byte[0] : req.getUserId().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = req.getNonce() == null ? new byte[0] : req.getNonce();
        byte[] key = req.getEncryptedXorKey() == null ? new byte[0] : req.getEncryptedXorKey();
        byte[] keyHash = sha256(key);
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + user.length + nonce.length + keyHash.length);
        buf.putLong(req.getTimestamp());
        buf.putInt(req.getXorKeyLength());
        buf.put(user);
        buf.put(nonce);
        buf.put(keyHash);
        return buf.array();
    }

    public static byte[] responseSigPayload(HandshakeResponse resp) {
        byte[] user = resp.getUserId() == null ? new byte[0] : resp.getUserId().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = resp.getNonce() == null ? new byte[0] : resp.getNonce();
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + user.length + nonce.length);
        buf.putLong(resp.getServerTime());
        buf.putInt(resp.getXorKeyLength());
        buf.put(user);
        buf.put(nonce);
        return buf.array();
    }

    public static byte[] requestSigPayloadV2(HandshakeRequest req) {
        byte[] user = req.getUserId() == null ? new byte[0] : req.getUserId().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = req.getNonce() == null ? new byte[0] : req.getNonce();
        byte[] key = req.getEncryptedXorKey() == null ? new byte[0] : req.getEncryptedXorKey();
        byte[] keyHash = sha256(key);
        byte[] seed = new byte[0];
        byte[] seedHash = sha256(seed);
        byte[] keyFileId = req.getKeyFileId() == null ? new byte[0] : req.getKeyFileId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4 + user.length + nonce.length + keyHash.length + seedHash.length + keyFileId.length);
        buf.putLong(req.getTimestamp());
        buf.putInt(req.getXorKeyLength());
        buf.putInt(req.getCryptoMode());
        buf.put(user);
        buf.put(nonce);
        buf.put(keyHash);
        buf.put(seedHash);
        buf.put(keyFileId);
        return buf.array();
    }

    public static byte[] responseSigPayloadV2(HandshakeResponse resp) {
        byte[] user = resp.getUserId() == null ? new byte[0] : resp.getUserId().getBytes(StandardCharsets.UTF_8);
        byte[] nonce = resp.getNonce() == null ? new byte[0] : resp.getNonce();
        byte[] seed = resp.getEncryptedSeed() == null ? new byte[0] : resp.getEncryptedSeed();
        byte[] seedHash = sha256(seed);
        byte[] keyFileId = resp.getKeyFileId() == null ? new byte[0] : resp.getKeyFileId().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4 + user.length + nonce.length + 4 + keyFileId.length + seedHash.length);
        buf.putLong(resp.getServerTime());
        buf.putInt(resp.getXorKeyLength());
        buf.putInt(resp.getCryptoMode());
        buf.put(user);
        buf.put(nonce);
        buf.putInt(resp.getKeyFileOffset());
        buf.put(keyFileId);
        buf.put(seedHash);
        return buf.array();
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
