import "dart:typed_data";

import "../proto_lite.dart";

class CoreP2PWrapper {
  final int seq;
  final int commandOrdinal;
  final Uint8List data;

  CoreP2PWrapper({required this.seq, required this.commandOrdinal, required this.data});
}

Uint8List encodeCoreP2PWrapper(CoreP2PWrapper w) {
  final wr = ProtoWriter();
  wr.writeInt32(1, w.seq);
  wr.writeInt32(2, w.commandOrdinal);
  if (w.data.isNotEmpty) {
    wr.writeBytesField(3, w.data);
  }
  return wr.takeBytes();
}

CoreP2PWrapper decodeCoreP2PWrapper(Uint8List payload) {
  final r = ProtoReader(payload);
  var seq = 0;
  var cmd = 0;
  Uint8List data = Uint8List(0);
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        seq = r.readVarint();
        break;
      case 2:
        cmd = r.readVarint();
        break;
      case 3:
        data = r.readBytes();
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return CoreP2PWrapper(seq: seq, commandOrdinal: cmd, data: data);
}

class HandshakeRequestPs {
  final String userId;
  final int timestampMs;
  final Uint8List nonce;
  final int xorKeyLength;
  final Uint8List encryptedXorKey;
  final Uint8List signature;

  HandshakeRequestPs({
    required this.userId,
    required this.timestampMs,
    required this.nonce,
    required this.xorKeyLength,
    required this.encryptedXorKey,
    required this.signature,
  });
}

Uint8List encodeHandshakeRequestPs(HandshakeRequestPs req) {
  final wr = ProtoWriter();
  if (req.userId.isNotEmpty) {
    wr.writeString(1, req.userId);
  }
  wr.writeUint64(2, req.timestampMs);
  if (req.nonce.isNotEmpty) {
    wr.writeBytesField(3, req.nonce);
  }
  wr.writeInt32(4, req.xorKeyLength);
  if (req.encryptedXorKey.isNotEmpty) {
    wr.writeBytesField(5, req.encryptedXorKey);
  }
  if (req.signature.isNotEmpty) {
    wr.writeBytesField(6, req.signature);
  }
  return wr.takeBytes();
}

class HandshakeResponsePs {
  final bool ok;
  final String error;
  final String userId;
  final int serverTime;
  final Uint8List nonce;
  final int xorKeyLength;
  final Uint8List encryptedSeed;
  final Uint8List signature;

  HandshakeResponsePs({
    required this.ok,
    required this.error,
    required this.userId,
    required this.serverTime,
    required this.nonce,
    required this.xorKeyLength,
    required this.encryptedSeed,
    required this.signature,
  });
}

HandshakeResponsePs decodeHandshakeResponsePs(Uint8List payload) {
  final r = ProtoReader(payload);
  var ok = false;
  var error = "";
  var userId = "";
  var serverTime = 0;
  Uint8List nonce = Uint8List(0);
  var xorKeyLength = 0;
  Uint8List encryptedSeed = Uint8List(0);
  Uint8List signature = Uint8List(0);
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        ok = r.readVarint() != 0;
        break;
      case 2:
        error = r.readString();
        break;
      case 3:
        userId = r.readString();
        break;
      case 4:
        serverTime = r.readVarint();
        break;
      case 5:
        nonce = r.readBytes();
        break;
      case 6:
        xorKeyLength = r.readVarint();
        break;
      case 7:
        encryptedSeed = r.readBytes();
        break;
      case 8:
        signature = r.readBytes();
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return HandshakeResponsePs(
    ok: ok,
    error: error,
    userId: userId,
    serverTime: serverTime,
    nonce: nonce,
    xorKeyLength: xorKeyLength,
    encryptedSeed: encryptedSeed,
    signature: signature,
  );
}

class LoginRequestPs {
  final String userId;
  final int timestampMs;
  final Uint8List signature;

  LoginRequestPs({required this.userId, required this.timestampMs, required this.signature});
}

Uint8List encodeLoginRequestPs(LoginRequestPs req) {
  final wr = ProtoWriter();
  if (req.userId.isNotEmpty) {
    wr.writeString(1, req.userId);
  }
  wr.writeUint64(2, req.timestampMs);
  if (req.signature.isNotEmpty) {
    wr.writeBytesField(3, req.signature);
  }
  return wr.takeBytes();
}

class LoginResponsePs {
  final bool ok;
  final String error;
  final String userId;
  final int serverTime;
  final Uint8List signature;

  LoginResponsePs({
    required this.ok,
    required this.error,
    required this.userId,
    required this.serverTime,
    required this.signature,
  });
}

LoginResponsePs decodeLoginResponsePs(Uint8List payload) {
  final r = ProtoReader(payload);
  var ok = false;
  var error = "";
  var userId = "";
  var serverTime = 0;
  Uint8List signature = Uint8List(0);
  while (!r.isEOF) {
    final tag = r.readTag();
    switch (tag.fieldNumber) {
      case 1:
        ok = r.readVarint() != 0;
        break;
      case 2:
        error = r.readString();
        break;
      case 3:
        userId = r.readString();
        break;
      case 4:
        serverTime = r.readVarint();
        break;
      case 5:
        signature = r.readBytes();
        break;
      default:
        r.skipField(tag.wireType);
        break;
    }
  }
  return LoginResponsePs(ok: ok, error: error, userId: userId, serverTime: serverTime, signature: signature);
}

