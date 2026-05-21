import "dart:async";
import "dart:convert";
import "dart:io";
import "dart:math";
import "dart:typed_data";

import "package:basic_utils/basic_utils.dart";
import "package:crypto/crypto.dart";

import "../rsa.dart";
import "../xor.dart";
import "core_frame.dart";
import "protostuff.dart";

class CoreWsClientConfig {
  final String wsUrl;
  final int magic;
  final int xorKeyLength;

  CoreWsClientConfig({required this.wsUrl, required this.magic, this.xorKeyLength = 4096});
}

class CoreWsClient {
  final CoreWsClientConfig _cfg;
  final Map<String, int> _ordinals;

  WebSocket? _ws;
  int _seq = 1;
  final _pending = <int, Completer<CoreStreamP2PWrapper>>{};
  final _streamHandlers = <int, void Function(CoreStreamP2PWrapper)>{};
  Uint8List? _xorKey;

  CoreWsClient(this._cfg, this._ordinals);

  Future<void> connect() async {
    final ws = await WebSocket.connect(_cfg.wsUrl);
    _ws = ws;
    ws.listen(_onMessage, onDone: close, onError: (_) => close());
  }

  Future<void> close() async {
    final ws = _ws;
    _ws = null;
    _xorKey = null;
    for (final c in _pending.values) {
      if (!c.isCompleted) {
        c.completeError(StateError("closed"));
      }
    }
    _pending.clear();
    _streamHandlers.clear();
    if (ws != null) {
      try {
        await ws.close();
      } catch (_) {}
    }
  }

  Future<CoreP2PWrapper> request(String commandName, Uint8List data, {Duration timeout = const Duration(seconds: 10)}) async {
    final cmd = _ordinals[commandName];
    if (cmd == null) {
      throw ArgumentError("unknown command: $commandName");
    }
    final seq = ++_seq;
    final c = Completer<CoreStreamP2PWrapper>();
    _pending[seq] = c;
    await _sendWrapper(CoreP2PWrapper(seq: seq, commandOrdinal: cmd, data: data), encrypt: commandName != "HAND");
    final sw = await c.future.timeout(timeout, onTimeout: () {
      _pending.remove(seq);
      throw TimeoutException("request timeout");
    });
    return CoreP2PWrapper(seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data);
  }

  Future<CoreP2PWrapper> sendAndAwait(CoreP2PWrapper w, {required bool encrypt, Duration timeout = const Duration(seconds: 10)}) async {
    if (w.seq <= 0) {
      throw ArgumentError("wrapper.seq required");
    }
    final c = Completer<CoreStreamP2PWrapper>();
    _pending[w.seq] = c;
    await send(w, encrypt: encrypt);
    final sw = await c.future.timeout(timeout, onTimeout: () {
      _pending.remove(w.seq);
      throw TimeoutException("request timeout");
    });
    return CoreP2PWrapper(seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data);
  }

  Future<CoreP2PWrapper> sendStreamOpen(CoreStreamP2PWrapper w, {Duration timeout = const Duration(seconds: 10)}) async {
    if (w.seq <= 0) {
      throw ArgumentError("wrapper.seq required");
    }
    final c = Completer<CoreStreamP2PWrapper>();
    _pending[w.seq] = c;
    await sendStream(w, encrypt: true);
    final sw = await c.future.timeout(timeout, onTimeout: () {
      _pending.remove(w.seq);
      throw TimeoutException("request timeout");
    });
    return CoreP2PWrapper(seq: sw.seq, commandOrdinal: sw.commandOrdinal, data: sw.data);
  }

  int allocateSeq() {
    _seq += 1;
    return _seq;
  }

  int mustOrdinal(String name) {
    final v = _ordinals[name];
    if (v == null) {
      throw ArgumentError("missing ordinal: $name");
    }
    return v;
  }

  void registerStreamHandler(int requestId, void Function(CoreStreamP2PWrapper)? handler) {
    if (requestId <= 0) {
      throw ArgumentError("requestId required");
    }
    if (handler == null) {
      _streamHandlers.remove(requestId);
      return;
    }
    _streamHandlers[requestId] = handler;
  }

  Future<void> send(CoreP2PWrapper w, {required bool encrypt}) async {
    await _sendWrapper(w, encrypt: encrypt);
  }

  Future<void> sendStream(CoreStreamP2PWrapper w, {required bool encrypt}) async {
    final ws = _ws;
    if (ws == null) {
      throw StateError("not connected");
    }
    final payload = encodeCoreStreamP2PWrapper(w);
    if (encrypt) {
      final key = _xorKey;
      if (key != null && payload.isNotEmpty) {
        xorRepeatInPlace(payload, key);
      }
    }
    ws.add(encodeCoreFrame(magic: _cfg.magic, payload: payload));
  }

  Future<void> handshakeAndLogin({required String userId, required String clientPrivateKeyPem}) async {
    final priv = CryptoUtils.rsaPrivateKeyFromPem(clientPrivateKeyPem);
    final xorKey = _randomBytes(_cfg.xorKeyLength);
    final now = DateTime.now().millisecondsSinceEpoch;
    final userUtf8 = Uint8List.fromList(utf8.encode(userId));
    final nonce = Uint8List.fromList(sha256.convert(_concat([_beI64(now), userUtf8])).bytes).sublist(0, 16);
    final encryptedXorKey = rsaPrivateEncryptPkcs1v15Large(priv, xorKey);

    final req0 = HandshakeRequestPs(
      userId: userId,
      timestampMs: now,
      nonce: nonce,
      xorKeyLength: _cfg.xorKeyLength,
      encryptedXorKey: encryptedXorKey,
      signature: Uint8List(0),
    );
    final sig = rsaSignSha256Pkcs1v15(priv, _handSigPayload(req0));
    final req = HandshakeRequestPs(
      userId: req0.userId,
      timestampMs: req0.timestampMs,
      nonce: req0.nonce,
      xorKeyLength: req0.xorKeyLength,
      encryptedXorKey: req0.encryptedXorKey,
      signature: sig,
    );

    final handResp = await request("HAND", encodeHandshakeRequestPs(req));
    if (handResp.commandOrdinal != (_ordinals["STD_OK"] ?? -1)) {
      throw StateError("handshake failed");
    }
    final hs = decodeHandshakeResponsePs(handResp.data);
    if (!hs.ok) {
      throw StateError("handshake failed: ${hs.error}");
    }
    _xorKey = xorKey;

    final lnow = DateTime.now().millisecondsSinceEpoch;
    final l0 = LoginRequestPs(userId: userId, timestampMs: lnow, signature: Uint8List(0));
    final lsig = rsaSignSha256Pkcs1v15(priv, _loginSigPayload(l0));
    final lreq = LoginRequestPs(userId: userId, timestampMs: lnow, signature: lsig);
    final loginResp = await request("LOGIN", encodeLoginRequestPs(lreq));
    if (loginResp.commandOrdinal != (_ordinals["STD_OK"] ?? -1)) {
      throw StateError("login failed");
    }
    final lr = decodeLoginResponsePs(loginResp.data);
    if (!lr.ok) {
      throw StateError("login failed: ${lr.error}");
    }
  }

  Future<void> _sendWrapper(CoreP2PWrapper w, {required bool encrypt}) async {
    final ws = _ws;
    if (ws == null) {
      throw StateError("not connected");
    }
    final payload = encodeCoreP2PWrapper(w);
    if (encrypt) {
      final key = _xorKey;
      if (key != null && payload.isNotEmpty) {
        xorRepeatInPlace(payload, key);
      }
    }
    ws.add(encodeCoreFrame(magic: _cfg.magic, payload: payload));
  }

  void _onMessage(dynamic msg) {
    if (msg is! List<int>) {
      return;
    }
    final bytes = Uint8List.fromList(msg);
    try {
      final frames = decodeAllCoreFrames(bytes);
      for (final f in frames) {
        if (f.magic != _cfg.magic) {
          continue;
        }
        final payload = Uint8List.fromList(f.payload);
        final key = _xorKey;
        if (key != null && payload.isNotEmpty) {
          xorRepeatInPlace(payload, key);
        }
        final w = decodeCoreStreamP2PWrapper(payload);

        final isAck = w.commandOrdinal == (_ordinals["STREAM_ACK"] ?? -999999) ||
            w.commandOrdinal == (_ordinals["STD_OK"] ?? -999999) ||
            w.commandOrdinal == (_ordinals["STD_ERROR"] ?? -999999) ||
            w.commandOrdinal == (_ordinals["INVALID_PROTOCOL"] ?? -999999);
        if (isAck) {
          final c = _pending.remove(w.seq);
          if (c != null && !c.isCompleted) {
            c.complete(w);
          }
          continue;
        }

        final h = _streamHandlers[w.seq];
        final isStream = w.commandOrdinal == (_ordinals["RPC_STREAM"] ?? -999999) || w.commandOrdinal == (_ordinals["RPC_EVENT"] ?? -999999);
        if (h != null && isStream) {
          h(w);
          continue;
        }
        if (isStream) {
          continue;
        }

        final c = _pending.remove(w.seq);
        if (c != null && !c.isCompleted) {
          c.complete(w);
        }
      }
    } catch (_) {
      return;
    }
  }
}

Uint8List _handSigPayload(HandshakeRequestPs req) {
  final user = Uint8List.fromList(utf8.encode(req.userId));
  final keyHash = Uint8List.fromList(sha256.convert(req.encryptedXorKey).bytes);
  return _concat([_beI64(req.timestampMs), _beI32(req.xorKeyLength), user, req.nonce, keyHash]);
}

Uint8List _loginSigPayload(LoginRequestPs req) {
  final user = Uint8List.fromList(utf8.encode(req.userId));
  return _concat([_beI64(req.timestampMs), user]);
}

Uint8List _beI64(int v) {
  final out = Uint8List(8);
  ByteData.sublistView(out).setInt64(0, v, Endian.big);
  return out;
}

Uint8List _beI32(int v) {
  final out = Uint8List(4);
  ByteData.sublistView(out).setInt32(0, v, Endian.big);
  return out;
}

Uint8List _concat(List<Uint8List> parts) {
  var len = 0;
  for (final p in parts) {
    len += p.length;
  }
  final out = Uint8List(len);
  var off = 0;
  for (final p in parts) {
    out.setRange(off, off + p.length, p);
    off += p.length;
  }
  return out;
}

Uint8List _randomBytes(int n) {
  final rnd = Random.secure();
  final out = Uint8List(n);
  for (var i = 0; i < n; i++) {
    out[i] = rnd.nextInt(256);
  }
  return out;
}
