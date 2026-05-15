import "dart:async";
import "dart:io";
import "dart:typed_data";

import "package:pointycastle/asymmetric/api.dart";

import "commands.dart";
import "crypto.dart";
import "frame.dart";
import "handshake.dart";
import "keyfile.dart";
import "messages/control.dart";
import "messages/wrapper.dart";
import "xor.dart";

class P2PSessionConfig {
  final int magic;
  final int version;
  final int flagsPlain;
  final int flagsEncrypted;
  final int maxFramePayload;

  const P2PSessionConfig({
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  });
}

class HandshakeState {
  final Uint8List sessionId;
  final Uint8List selectedKeyId;
  final int offset;
  final int maxFramePayload;
  final int headerPolicyId;
  final String cryptoMode;

  const HandshakeState({
    required this.sessionId,
    required this.selectedKeyId,
    required this.offset,
    required this.maxFramePayload,
    required this.headerPolicyId,
    required this.cryptoMode,
  });
}

class P2PSession {
  final WebSocket _ws;
  final P2PSessionConfig _cfg;
  final KeyFileReader? _keyfile;

  int _seq = 1;
  bool _handshaked = false;
  String _cryptoMode = P2PCryptoMode.keyfileXorRsaOaep;
  int? _offset;
  Uint8List? _repeatKey;

  final _incoming = StreamController<P2PWrapper>.broadcast();
  final _pending = <int, Completer<P2PWrapper>>{};

  P2PSession._(this._ws, this._cfg, this._keyfile) {
    _listen();
  }

  static Future<P2PSession> connect({
    required String wsUrl,
    required P2PSessionConfig config,
    KeyFileReader? keyfile,
    Map<String, dynamic>? headers,
  }) async {
    final ws = await WebSocket.connect(wsUrl, headers: headers);
    return P2PSession._(ws, config, keyfile);
  }

  Stream<P2PWrapper> get incoming => _incoming.stream;

  int nextSeq() => ++_seq;

  Future<HandshakeState> handshake({
    required RSAPrivateKey privateKey,
    required Uint8List clientPubkeySpkiDer,
    required String clientId,
    String cryptoMode = P2PCryptoMode.keyfileXorRsaOaep,
    int randomKeyBytes = 32,
  }) async {
    final requestedMode = cryptoMode.trim().isEmpty ? P2PCryptoMode.keyfileXorRsaOaep : cryptoMode.trim().toUpperCase();
    Uint8List clientRandomKey = Uint8List(0);
    if (requestedMode == P2PCryptoMode.clientRandomXorRsaOaep) {
      clientRandomKey = secureRandomBytes(randomKeyBytes);
    }
    if (requestedMode == P2PCryptoMode.keyfileXorRsaOaep && _keyfile == null) {
      throw StateError("keyfile is required for KEYFILE_XOR_RSA_OAEP");
    }

    final wrap = buildHandWrapper(
      seq: nextSeq(),
      clientPubkeySpkiDer: clientPubkeySpkiDer,
      keyIds: _keyfile == null ? const <Uint8List>[] : <Uint8List>[_keyfile.keyId],
      maxFramePayload: _cfg.maxFramePayload,
      clientId: clientId,
      cryptoMode: requestedMode,
      clientRandomKey: clientRandomKey,
    );
    await _sendWrapperPlain(wrap);

    while (true) {
      final w = await incoming.firstWhere((e) => e.command == P2PCommand.handAck);
      final plain = rsaOaepSha256Decrypt(privateKey, w.data);
      final ack = decodeHandAckPlain(plain);
      final ackMode = ack.cryptoMode.trim().isEmpty ? requestedMode : ack.cryptoMode.trim().toUpperCase();
      _cryptoMode = ackMode;
      _handshaked = true;
      if (P2PCryptoMode.isPlain(ackMode)) {
        _offset = null;
        _repeatKey = null;
      } else if (ackMode == P2PCryptoMode.keyfileXorRsaOaep) {
        _offset = ack.offset;
        _repeatKey = null;
      } else if (ackMode == P2PCryptoMode.clientRandomXorRsaOaep) {
        _offset = null;
        _repeatKey = clientRandomKey;
      } else if (ackMode == P2PCryptoMode.serverRandomXorRsaOaep) {
        _offset = null;
        _repeatKey = ack.serverRandomKey;
      } else {
        throw StateError("unsupported crypto_mode=$ackMode");
      }
      return HandshakeState(
        sessionId: ack.sessionId,
        selectedKeyId: ack.selectedKeyId,
        offset: ack.offset,
        maxFramePayload: ack.maxFramePayload,
        headerPolicyId: ack.headerPolicyId,
        cryptoMode: ackMode,
      );
    }
  }

  Future<void> close() async {
    await _incoming.close();
    await _keyfile?.close();
    await _ws.close();
  }

  Future<P2PWrapper> request({
    required int command,
    required Uint8List data,
    required int expectedCommand,
    Map<String, String> headers = const {},
  }) async {
    final seq = nextSeq();
    final c = Completer<P2PWrapper>();
    _pending[seq] = c;
    await _sendWrapper(P2PWrapper(seq: seq, command: command, data: data, headers: headers));
    final w = await c.future;
    if (w.command != expectedCommand) {
      throw StateError("unexpected response command=${w.command} expected=$expectedCommand");
    }
    return w;
  }

  Future<P2PWrapper> requestAny({
    required int command,
    required Uint8List data,
    Map<String, String> headers = const {},
  }) async {
    final seq = nextSeq();
    final c = Completer<P2PWrapper>();
    _pending[seq] = c;
    await _sendWrapper(P2PWrapper(seq: seq, command: command, data: data, headers: headers));
    return c.future;
  }

  Future<void> sendEncrypted(P2PWrapper w) => _sendWrapper(w);

  Future<void> _sendWrapperPlain(P2PWrapper w) async {
    final plain = encodeWrapper(w);
    final frame = encodeFrame(WireHeader(plain.length, _cfg.magic, _cfg.version, _cfg.flagsPlain), plain);
    _ws.add(frame);
  }

  Future<Uint8List> _decrypt(Uint8List cipher) async {
    final mode = _cryptoMode;
    if (P2PCryptoMode.isPlain(mode)) {
      throw StateError("received encrypted frame in PLAIN mode");
    }
    if (mode == P2PCryptoMode.keyfileXorRsaOaep) {
      final off = _offset;
      final keyf = _keyfile;
      if (off == null || keyf == null) {
        throw StateError("keyfile offset/keyfile missing");
      }
      final slice = await keyf.readSlice(off, cipher.length);
      return xorNoWrap(cipher, slice, 0);
    }
    final key = _repeatKey;
    if (key == null || key.isEmpty) {
      throw StateError("repeat key missing");
    }
    return xorRepeat(cipher, key);
  }

  Future<Uint8List> _encrypt(Uint8List plain) async {
    final mode = _cryptoMode;
    if (P2PCryptoMode.isPlain(mode)) {
      return plain;
    }
    if (mode == P2PCryptoMode.keyfileXorRsaOaep) {
      final off = _offset;
      final keyf = _keyfile;
      if (off == null || keyf == null) {
        throw StateError("keyfile offset/keyfile missing");
      }
      final slice = await keyf.readSlice(off, plain.length);
      return xorNoWrap(plain, slice, 0);
    }
    final key = _repeatKey;
    if (key == null || key.isEmpty) {
      throw StateError("repeat key missing");
    }
    return xorRepeat(plain, key);
  }

  Future<void> _sendWrapper(P2PWrapper w) async {
    if (!_handshaked) {
      throw StateError("handshake not completed");
    }
    final plain = encodeWrapper(w);
    final cipherOrPlain = await _encrypt(plain);
    final flags = P2PCryptoMode.isPlain(_cryptoMode) ? _cfg.flagsPlain : _cfg.flagsEncrypted;
    final frame = encodeFrame(WireHeader(cipherOrPlain.length, _cfg.magic, _cfg.version, flags), cipherOrPlain);
    _ws.add(frame);
  }

  void _listen() {
    _ws.listen(
      (dynamic msg) async {
        if (msg is! List<int>) {
          return;
        }
        final f = decodeFrame(Uint8List.fromList(msg));
        final cipher = f.cipherPayload;
        Uint8List plain;
        if (f.header.flags == _cfg.flagsEncrypted) {
          plain = await _decrypt(Uint8List.fromList(cipher));
        } else {
          plain = Uint8List.fromList(cipher);
        }
        final w = decodeWrapper(plain);
        final c = _pending.remove(w.seq);
        if (c != null && !c.isCompleted) {
          c.complete(w);
          return;
        }
        _incoming.add(w);
      },
      onError: (Object e, StackTrace st) {
        for (final c in _pending.values) {
          if (!c.isCompleted) {
            c.completeError(e, st);
          }
        }
        _pending.clear();
        _incoming.addError(e, st);
      },
      onDone: () {
        for (final c in _pending.values) {
          if (!c.isCompleted) {
            c.completeError(StateError("websocket closed"));
          }
        }
        _pending.clear();
        _incoming.close();
      },
      cancelOnError: true,
    );
  }
}
