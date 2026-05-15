package p2pws.sdk.core_compat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.auth.model.HandshakeRequest;
import javax.net.p2p.auth.model.HandshakeResponse;
import javax.net.p2p.auth.model.LoginRequest;
import javax.net.p2p.auth.model.LoginResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;

public final class CoreWsClient {
    private final URI wsUri;
    private final int magic;
    private final int xorKeyLength;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<P2PWrapper>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Consumer<StreamP2PWrapper>> streamHandlers = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    private volatile WebSocket ws;
    private volatile byte[] xorKey;

    public CoreWsClient(URI wsUri, int magic, int xorKeyLength) {
        this.wsUri = Objects.requireNonNull(wsUri, "wsUri");
        this.magic = magic;
        this.xorKeyLength = xorKeyLength <= 0 ? 4096 : xorKeyLength;
    }

    public CompletableFuture<Void> connect() {
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .buildAsync(wsUri, new Listener(ready))
                .thenAccept(w -> this.ws = w)
                .exceptionally(e -> {
                    ready.completeExceptionally(e);
                    return null;
                });
        return ready;
    }

    public void close() {
        WebSocket w = this.ws;
        this.ws = null;
        this.xorKey = null;
        for (var e : pending.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException("closed"));
        }
        pending.clear();
        streamHandlers.clear();
        if (w != null) {
            w.abort();
        }
    }

    public CompletableFuture<P2PWrapper> request(P2PCommand command, byte[] data, Duration timeout) {
        if (command == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("command required"));
        }
        int s = seq.incrementAndGet();
        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending.put(s, fut);
        try {
            sendWrapper(s, command, data, command != P2PCommand.HAND);
        } catch (Exception e) {
            pending.remove(s);
            return CompletableFuture.failedFuture(e);
        }
        Duration t = timeout == null ? Duration.ofSeconds(10) : timeout;
        return fut.orTimeout(t.toMillis(), TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<P2PWrapper> sendAndAwait(P2PWrapper wrapper, boolean encrypt, Duration timeout) {
        if (wrapper == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("wrapper required"));
        }
        int requestId = wrapper.getSeq();
        if (requestId <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("wrapper.seq required"));
        }
        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending.put(requestId, fut);
        try {
            sendObject(wrapper, encrypt);
        } catch (Exception e) {
            pending.remove(requestId);
            return CompletableFuture.failedFuture(e);
        }
        Duration t = timeout == null ? Duration.ofSeconds(10) : timeout;
        return fut.orTimeout(t.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int allocateSeq() {
        return seq.incrementAndGet();
    }

    public void registerStreamHandler(int requestId, Consumer<StreamP2PWrapper> handler) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId required");
        }
        if (handler == null) {
            streamHandlers.remove(requestId);
            return;
        }
        streamHandlers.put(requestId, handler);
    }

    public CompletableFuture<Void> handshakeAndLogin(String userId, String clientPrivateKeyPem) {
        final String uid = userId == null ? "" : userId;
        PrivateKey privateKey;
        try {
            privateKey = loadPrivateKeyPkcs8Pem(clientPrivateKeyPem);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        byte[] key = randomBytes(xorKeyLength);
        byte[] nonce = randomBytes(16);
        long ts = System.currentTimeMillis();
        byte[] encryptedXorKey;
        try {
            encryptedXorKey = rsaEncryptLargeWithPrivate(key, privateKey);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        byte[] sigPayload = handSigPayload(ts, xorKeyLength, uid, nonce, encryptedXorKey);
        byte[] sig;
        try {
            sig = signSha256Rsa(sigPayload, privateKey);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        HandshakeRequest req = new HandshakeRequest();
        req.setUserId(uid);
        req.setTimestamp(ts);
        req.setNonce(nonce);
        req.setXorKeyLength(xorKeyLength);
        req.setEncryptedXorKey(encryptedXorKey);
        req.setSignature(sig);
        byte[] reqBytes = ProtostuffCodec.serialize(req);

        return request(P2PCommand.HAND, reqBytes, Duration.ofSeconds(10)).thenCompose(w -> {
            if (w.getCommand() != P2PCommand.STD_OK) {
                return CompletableFuture.failedFuture(new IllegalStateException("handshake failed"));
            }
            Object data = w.getData();
            if (!(data instanceof byte[])) {
                return CompletableFuture.failedFuture(new IllegalStateException("handshake invalid response type"));
            }
            HandshakeResponse hr = ProtostuffCodec.deserialize(HandshakeResponse.class, (byte[]) data);
            if (!hr.isOk()) {
                return CompletableFuture.failedFuture(new IllegalStateException("handshake failed: " + hr.getError()));
            }
            this.xorKey = key;
            long lts = System.currentTimeMillis();
            byte[] loginSigPayload = loginSigPayload(lts, uid);
            byte[] loginSig;
            try {
                loginSig = signSha256Rsa(loginSigPayload, privateKey);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            LoginRequest lrq = new LoginRequest();
            lrq.setUserId(uid);
            lrq.setTimestamp(lts);
            lrq.setSignature(loginSig);
            byte[] loginReqBytes = ProtostuffCodec.serialize(lrq);

            return request(P2PCommand.LOGIN, loginReqBytes, Duration.ofSeconds(10)).thenApply(lw -> {
                if (lw.getCommand() != P2PCommand.STD_OK) {
                    throw new IllegalStateException("login failed");
                }
                Object ld = lw.getData();
                if (!(ld instanceof byte[])) {
                    throw new IllegalStateException("login invalid response type");
                }
                LoginResponse lr = ProtostuffCodec.deserialize(LoginResponse.class, (byte[]) ld);
                if (!lr.isOk()) {
                    throw new IllegalStateException("login failed: " + lr.getError());
                }
                return null;
            });
        });
    }

    private void sendWrapper(int seq, P2PCommand cmd, byte[] data, boolean encrypt) {
        WebSocket w = this.ws;
        if (w == null) {
            throw new IllegalStateException("not connected");
        }
        byte[] payload = ProtostuffCodec.serialize(P2PWrapper.build(seq, cmd, data));
        if (encrypt) {
            byte[] key = this.xorKey;
            if (key != null) {
                ProtostuffLite.xorInPlace(payload, key);
            }
        }
        byte[] frame = CoreFrameCodec.encode(magic, payload);
        synchronized (sendLock) {
            w.sendBinary(ByteBuffer.wrap(frame), true).join();
        }
    }

    public void sendObject(Object obj, boolean encrypt) {
        WebSocket w = this.ws;
        if (w == null) {
            throw new IllegalStateException("not connected");
        }
        byte[] payload = ProtostuffCodec.serialize(obj);
        if (encrypt) {
            byte[] key = this.xorKey;
            if (key != null) {
                ProtostuffLite.xorInPlace(payload, key);
            }
        }
        byte[] frame = CoreFrameCodec.encode(magic, payload);
        synchronized (sendLock) {
            w.sendBinary(ByteBuffer.wrap(frame), true).join();
        }
    }

    public CompletableFuture<P2PWrapper> sendStreamOpen(StreamP2PWrapper wrapper, Duration timeout) {
        if (wrapper == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("wrapper required"));
        }
        int requestId = wrapper.getSeq();
        if (requestId <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("wrapper.seq required"));
        }
        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending.put(requestId, fut);
        try {
            sendObject(wrapper, true);
        } catch (Exception e) {
            pending.remove(requestId);
            return CompletableFuture.failedFuture(e);
        }
        Duration t = timeout == null ? Duration.ofSeconds(10) : timeout;
        return fut.orTimeout(t.toMillis(), TimeUnit.MILLISECONDS);
    }

    void onBinaryMessage(byte[] msg) {
        java.util.List<CoreFrameCodec.Decoded> frames;
        try {
            frames = CoreFrameCodec.decodeAll(msg);
        } catch (Exception e) {
            return;
        }
        for (CoreFrameCodec.Decoded f : frames) {
            if (f.magic() != magic) {
                continue;
            }
            byte[] payload = f.payload();
            byte[] key = this.xorKey;
            if (key != null && payload.length > 0) {
                ProtostuffLite.xorInPlace(payload, key);
            }
            StreamP2PWrapper w;
            try {
                w = ProtostuffCodec.deserialize(StreamP2PWrapper.class, payload);
            } catch (Exception e) {
                continue;
            }
            int requestId = w.getSeq();
            if (w.getCommand() == P2PCommand.STREAM_ACK
                || w.getCommand() == P2PCommand.STD_OK
                || w.getCommand() == P2PCommand.STD_ERROR
                || w.getCommand() == P2PCommand.INVALID_PROTOCOL) {
                CompletableFuture<P2PWrapper> fut = pending.remove(requestId);
                if (fut != null) {
                    fut.complete(w);
                    continue;
                }
            }
            Consumer<StreamP2PWrapper> handler = streamHandlers.get(requestId);
            if (handler != null && (w.getCommand() == P2PCommand.RPC_STREAM || w.getCommand() == P2PCommand.RPC_EVENT)) {
                handler.accept(w);
                continue;
            }
            if (w.getCommand() == P2PCommand.RPC_STREAM || w.getCommand() == P2PCommand.RPC_EVENT) {
                continue;
            }
            CompletableFuture<P2PWrapper> fut = pending.remove(requestId);
            if (fut != null) {
                fut.complete(w);
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final CompletableFuture<Void> ready;
        private ByteArrayOutputStream buf;

        private Listener(CompletableFuture<Void> ready) {
            this.ready = ready;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ready.complete(null);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (buf == null) {
                buf = new ByteArrayOutputStream();
            }
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buf.writeBytes(chunk);
            if (last) {
                byte[] msg = buf.toByteArray();
                buf = null;
                onBinaryMessage(msg);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            close();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            close();
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static PrivateKey loadPrivateKeyPkcs8Pem(String pem) throws Exception {
        if (pem == null) {
            throw new IllegalArgumentException("pem required");
        }
        String s = pem.replace("\r", "").replace("\n", "");
        s = s.replace("-----BEGIN PRIVATE KEY-----", "");
        s = s.replace("-----END PRIVATE KEY-----", "");
        byte[] der = Base64.getDecoder().decode(s.trim());
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] signSha256Rsa(byte[] payload, PrivateKey privateKey) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey);
        s.update(payload);
        return s.sign();
    }

    private static byte[] rsaEncryptLargeWithPrivate(byte[] data, PrivateKey privateKey) throws Exception {
        RSAPrivateKey rsa = (RSAPrivateKey) privateKey;
        int keySizeBytes = (rsa.getModulus().bitLength() + 7) / 8;
        int maxPlain = keySizeBytes - 11;
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int off = 0; off < data.length; off += maxPlain) {
            int n = Math.min(maxPlain, data.length - off);
            byte[] block = cipher.doFinal(data, off, n);
            out.writeBytes(block);
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    private static byte[] handSigPayload(long timestamp, int xorKeyLength, String userId, byte[] nonce, byte[] encryptedXorKey) {
        byte[] user = userId == null ? new byte[0] : userId.getBytes(StandardCharsets.UTF_8);
        byte[] n = nonce == null ? new byte[0] : nonce;
        byte[] key = encryptedXorKey == null ? new byte[0] : encryptedXorKey;
        byte[] keyHash;
        try {
            keyHash = sha256(key);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ByteBuffer buf = ByteBuffer.allocate(8 + 4 + user.length + n.length + keyHash.length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(timestamp);
        buf.putInt(xorKeyLength);
        buf.put(user);
        buf.put(n);
        buf.put(keyHash);
        return buf.array();
    }

    private static byte[] loginSigPayload(long timestamp, String userId) {
        byte[] user = userId == null ? new byte[0] : userId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + user.length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(timestamp);
        buf.put(user);
        return buf.array();
    }
}
