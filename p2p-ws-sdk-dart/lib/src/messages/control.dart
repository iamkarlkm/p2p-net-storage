import "dart:typed_data";

import "../proto_lite.dart";

class Hand {
  final Uint8List clientPubkeySpkiDer;
  final List<Uint8List> keyIds;
  final int maxFramePayload;
  final String clientId;
  final String cryptoMode;
  final Uint8List clientRandomKey;

  const Hand({
    required this.clientPubkeySpkiDer,
    required this.keyIds,
    required this.maxFramePayload,
    required this.clientId,
    required this.cryptoMode,
    required this.clientRandomKey,
  });
}

Uint8List encodeHand(Hand h) {
  final w = ProtoWriter();
  w.writeBytesField(1, h.clientPubkeySpkiDer);
  for (final kid in h.keyIds) {
    w.writeBytesField(2, kid);
  }
  w.writeUint32(3, h.maxFramePayload);
  w.writeString(4, h.clientId);
  if (h.cryptoMode.isNotEmpty) {
    w.writeString(5, h.cryptoMode);
  }
  if (h.clientRandomKey.isNotEmpty) {
    w.writeBytesField(6, h.clientRandomKey);
  }
  return w.takeBytes();
}

Hand decodeHand(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List clientPubkeySpkiDer = Uint8List(0);
  final keyIds = <Uint8List>[];
  var maxFramePayload = 0;
  var clientId = "";
  var cryptoMode = "";
  Uint8List clientRandomKey = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        clientPubkeySpkiDer = Uint8List.fromList(r.readBytes());
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        keyIds.add(Uint8List.fromList(r.readBytes()));
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        maxFramePayload = r.readVarint();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        clientId = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        cryptoMode = r.readString();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        clientRandomKey = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return Hand(
    clientPubkeySpkiDer: clientPubkeySpkiDer,
    keyIds: keyIds,
    maxFramePayload: maxFramePayload,
    clientId: clientId,
    cryptoMode: cryptoMode,
    clientRandomKey: clientRandomKey,
  );
}

class HandAckPlain {
  final Uint8List sessionId;
  final Uint8List selectedKeyId;
  final int offset;
  final int maxFramePayload;
  final int headerPolicyId;
  final String cryptoMode;
  final Uint8List serverRandomKey;

  const HandAckPlain({
    required this.sessionId,
    required this.selectedKeyId,
    required this.offset,
    required this.maxFramePayload,
    required this.headerPolicyId,
    required this.cryptoMode,
    required this.serverRandomKey,
  });
}

Uint8List encodeHandAckPlain(HandAckPlain a) {
  final w = ProtoWriter();
  w.writeBytesField(1, a.sessionId);
  w.writeBytesField(2, a.selectedKeyId);
  w.writeUint32(3, a.offset);
  w.writeUint32(4, a.maxFramePayload);
  w.writeUint32(5, a.headerPolicyId);
  if (a.cryptoMode.isNotEmpty) {
    w.writeString(6, a.cryptoMode);
  }
  if (a.serverRandomKey.isNotEmpty) {
    w.writeBytesField(7, a.serverRandomKey);
  }
  return w.takeBytes();
}

HandAckPlain decodeHandAckPlain(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List sessionId = Uint8List(0);
  Uint8List selectedKeyId = Uint8List(0);
  var offset = 0;
  var maxFramePayload = 0;
  var headerPolicyId = 0;
  var cryptoMode = "";
  Uint8List serverRandomKey = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        sessionId = Uint8List.fromList(r.readBytes());
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        selectedKeyId = Uint8List.fromList(r.readBytes());
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        offset = r.readVarint();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        maxFramePayload = r.readVarint();
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        headerPolicyId = r.readVarint();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        cryptoMode = r.readString();
        break;
      case 7:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        serverRandomKey = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return HandAckPlain(
    sessionId: sessionId,
    selectedKeyId: selectedKeyId,
    offset: offset,
    maxFramePayload: maxFramePayload,
    headerPolicyId: headerPolicyId,
    cryptoMode: cryptoMode,
    serverRandomKey: serverRandomKey,
  );
}

class CryptUpdate {
  final Uint8List keyId;
  final int offset;
  final int effectiveFromSeq;

  const CryptUpdate({required this.keyId, required this.offset, required this.effectiveFromSeq});
}

CryptUpdate decodeCryptUpdate(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List keyId = Uint8List(0);
  var offset = 0;
  var effectiveFromSeq = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        keyId = Uint8List.fromList(r.readBytes());
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        offset = r.readVarint();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        effectiveFromSeq = r.readVarint().toSigned(32);
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return CryptUpdate(keyId: keyId, offset: offset, effectiveFromSeq: effectiveFromSeq);
}
