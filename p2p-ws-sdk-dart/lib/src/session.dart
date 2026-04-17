import "dart:async";
import "dart:io";
import "dart:typed_data";

import "package:pointycastle/asymmetric/api.dart";

import "commands.dart";
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

  const HandshakeState({
    required this.sessionId,
    required this.selectedKeyId,
    required this.offset,
    required this.maxFramePayload,
    required this.headerPolicyId,
  });
}

class P2PSession {
  final WebSocket _ws;
  final P2PSessionConfig _cfg;
  final KeyFileReader _keyfile;

  int _seq = 1;
  int? _offset;

  final _incoming = StreamController<P2PWrapper>.broadcast();
  final _pending = <int, Completer<P2PWrapper>>{};

  P2PSession._(this._ws, this._cfg, this._keyfile) {
    _listen();
  }

  static Future<P2PSession> connect({
    required String wsUrl,
    required P2PSessionConfig config,
    required KeyFileReader keyfile,
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
  }) async {
    final wrap = buildHandWrapper(
      seq: nextSeq(),
      clientPubkeySpkiDer: clientPubkeySpkiDer,
      keyIds: [_keyfile.keyId],
      maxFramePayload: _cfg.maxFramePayload,
      clientId: clientId,
    );
    await _sendWrapperPlain(wrap);

    while (true) {
      final w = await incoming.firstWhere((e) => e.command == P2PCommand.handAck);
      final plain = rsaOaepSha256Decrypt(privateKey, w.data);
      final ack = decodeHandAckPlain(plain);
      _offset = ack.offset;
      return HandshakeState(
        sessionId: ack.sessionId,
        selectedKeyId: ack.selectedKeyId,
        offset: ack.offset,
        maxFramePayload: ack.maxFramePayload,
        headerPolicyId: ack.headerPolicyId,
      );
    }
  }

  Future<void> close() async {
    await _incoming.close();
    await _keyfile.close();
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
    await _sendWrapperEncrypted(P2PWrapper(seq: seq, command: command, data: data, headers: headers));
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
    await _sendWrapperEncrypted(P2PWrapper(seq: seq, command: command, data: data, headers: headers));
    return c.future;
  }

  Future<void> sendEncrypted(P2PWrapper w) => _sendWrapperEncrypted(w);

  Future<void> _sendWrapperPlain(P2PWrapper w) async {
    final plain = encodeWrapper(w);
    final frame = encodeFrame(WireHeader(plain.length, _cfg.magic, _cfg.version, _cfg.flagsPlain), plain);
    _ws.add(frame);
  }

  Future<void> _sendWrapperEncrypted(P2PWrapper w) async {
    final off = _offset;
    if (off == null) {
      throw StateError("not encrypted yet");
    }
    final plain = encodeWrapper(w);
    final slice = await _keyfile.readSlice(off, plain.length);
    final cipher = xorNoWrap(plain, slice, 0);
    final frame = encodeFrame(WireHeader(cipher.length, _cfg.magic, _cfg.version, _cfg.flagsEncrypted), cipher);
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
        final off = _offset;
        if (off == null) {
          plain = Uint8List.fromList(cipher);
        } else {
          final slice = await _keyfile.readSlice(off, cipher.length);
          plain = xorNoWrap(Uint8List.fromList(cipher), slice, 0);
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
