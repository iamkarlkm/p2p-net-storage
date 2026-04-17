import "dart:typed_data";

import "../proto_lite.dart";

class Endpoint {
  final String transport;
  final String addr;

  const Endpoint({required this.transport, required this.addr});
}

Uint8List encodeEndpoint(Endpoint e) {
  final w = ProtoWriter();
  w.writeString(1, e.transport);
  w.writeString(2, e.addr);
  return w.takeBytes();
}

Endpoint decodeEndpoint(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var transport = "";
  var addr = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        transport = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        addr = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return Endpoint(transport: transport, addr: addr);
}

class NodeCaps {
  final int maxFramePayload;
  final int magic;
  final int version;
  final int flagsPlain;
  final int flagsEncrypted;

  const NodeCaps({
    required this.maxFramePayload,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
  });
}

Uint8List encodeNodeCaps(NodeCaps c) {
  final w = ProtoWriter();
  w.writeUint32(1, c.maxFramePayload);
  w.writeUint32(2, c.magic);
  w.writeUint32(3, c.version);
  w.writeUint32(4, c.flagsPlain);
  w.writeUint32(5, c.flagsEncrypted);
  return w.takeBytes();
}

NodeCaps decodeNodeCaps(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var maxFramePayload = 0;
  var magic = 0;
  var version = 0;
  var flagsPlain = 0;
  var flagsEncrypted = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        maxFramePayload = r.readVarint();
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        magic = r.readVarint();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        version = r.readVarint();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        flagsPlain = r.readVarint();
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        flagsEncrypted = r.readVarint();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return NodeCaps(
    maxFramePayload: maxFramePayload,
    magic: magic,
    version: version,
    flagsPlain: flagsPlain,
    flagsEncrypted: flagsEncrypted,
  );
}

class CenterHelloBody {
  final int nodeId64;
  final Uint8List pubkeySpkiDer;
  final List<Endpoint> reportedEndpoints;
  final NodeCaps caps;
  final int timestampMs;
  final String cryptoMode;

  const CenterHelloBody({
    required this.nodeId64,
    required this.pubkeySpkiDer,
    required this.reportedEndpoints,
    required this.caps,
    required this.timestampMs,
    required this.cryptoMode,
  });
}

Uint8List encodeCenterHelloBody(CenterHelloBody b) {
  final w = ProtoWriter();
  w.writeFixed64(1, b.nodeId64);
  w.writeBytesField(2, b.pubkeySpkiDer);
  for (final ep in b.reportedEndpoints) {
    w.writeEmbedded(3, encodeEndpoint(ep));
  }
  w.writeEmbedded(4, encodeNodeCaps(b.caps));
  w.writeUint64(5, b.timestampMs);
  w.writeString(6, b.cryptoMode);
  return w.takeBytes();
}

class CenterHello {
  final CenterHelloBody body;
  final Uint8List signature;

  const CenterHello({required this.body, required this.signature});
}

Uint8List encodeCenterHello(CenterHello h) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodeCenterHelloBody(h.body));
  w.writeBytesField(2, h.signature);
  return w.takeBytes();
}

class CenterHelloAck {
  final Uint8List nodeKey;
  final Endpoint observedEndpoint;
  final int ttlSeconds;
  final int serverTimeMs;

  const CenterHelloAck({
    required this.nodeKey,
    required this.observedEndpoint,
    required this.ttlSeconds,
    required this.serverTimeMs,
  });
}

CenterHelloAck decodeCenterHelloAck(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List nodeKey = Uint8List(0);
  Endpoint observedEndpoint = const Endpoint(transport: "", addr: "");
  var ttlSeconds = 0;
  var serverTimeMs = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        nodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        observedEndpoint = decodeEndpoint(r.readBytes());
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        ttlSeconds = r.readVarint();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        serverTimeMs = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return CenterHelloAck(nodeKey: nodeKey, observedEndpoint: observedEndpoint, ttlSeconds: ttlSeconds, serverTimeMs: serverTimeMs);
}

class GetNode {
  final int nodeId64;
  final Uint8List nodeKey;

  const GetNode({required this.nodeId64, required this.nodeKey});
}

Uint8List encodeGetNode(GetNode g) {
  final w = ProtoWriter();
  w.writeFixed64(1, g.nodeId64);
  w.writeBytesField(2, g.nodeKey);
  return w.takeBytes();
}

class GetNodeAck {
  final bool found;
  final Uint8List nodeKey;
  final int nodeId64;
  final List<Endpoint> endpoints;
  final NodeCaps? caps;
  final int expiresAtMs;

  const GetNodeAck({
    required this.found,
    required this.nodeKey,
    required this.nodeId64,
    required this.endpoints,
    required this.caps,
    required this.expiresAtMs,
  });
}

GetNodeAck decodeGetNodeAck(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var found = false;
  Uint8List nodeKey = Uint8List(0);
  var nodeId64 = 0;
  final endpoints = <Endpoint>[];
  NodeCaps? caps;
  var expiresAtMs = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        found = r.readVarint() != 0;
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        nodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 3:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        nodeId64 = r.readFixed64();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        endpoints.add(decodeEndpoint(r.readBytes()));
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        caps = decodeNodeCaps(r.readBytes());
        break;
      case 6:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        expiresAtMs = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return GetNodeAck(found: found, nodeKey: nodeKey, nodeId64: nodeId64, endpoints: endpoints, caps: caps, expiresAtMs: expiresAtMs);
}

class PeerHelloBody {
  final int nodeId64;
  final Uint8List pubkeySpkiDer;
  final int timestampMs;
  final String cryptoMode;

  const PeerHelloBody({
    required this.nodeId64,
    required this.pubkeySpkiDer,
    required this.timestampMs,
    required this.cryptoMode,
  });
}

Uint8List encodePeerHelloBody(PeerHelloBody b) {
  final w = ProtoWriter();
  w.writeFixed64(1, b.nodeId64);
  w.writeBytesField(2, b.pubkeySpkiDer);
  w.writeUint64(3, b.timestampMs);
  w.writeString(4, b.cryptoMode);
  return w.takeBytes();
}

PeerHelloBody decodePeerHelloBody(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var nodeId64 = 0;
  Uint8List pubkeySpkiDer = Uint8List(0);
  var timestampMs = 0;
  var cryptoMode = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        nodeId64 = r.readFixed64();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        pubkeySpkiDer = Uint8List.fromList(r.readBytes());
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        timestampMs = r.readVarint64();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        cryptoMode = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return PeerHelloBody(nodeId64: nodeId64, pubkeySpkiDer: pubkeySpkiDer, timestampMs: timestampMs, cryptoMode: cryptoMode);
}

class PeerHello {
  final PeerHelloBody body;
  final Uint8List signature;

  const PeerHello({required this.body, required this.signature});
}

Uint8List encodePeerHello(PeerHello h) {
  final w = ProtoWriter();
  w.writeEmbedded(1, encodePeerHelloBody(h.body));
  w.writeBytesField(2, h.signature);
  return w.takeBytes();
}

class PeerHelloDecoded {
  final PeerHelloBody body;
  final Uint8List bodyBytes;
  final Uint8List signature;

  const PeerHelloDecoded({required this.body, required this.bodyBytes, required this.signature});
}

PeerHelloDecoded decodePeerHello(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List bodyBytes = Uint8List(0);
  PeerHelloBody? body;
  Uint8List signature = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        bodyBytes = Uint8List.fromList(r.readBytes());
        body = decodePeerHelloBody(bodyBytes);
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        signature = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return PeerHelloDecoded(
    body: body ?? PeerHelloBody(nodeId64: 0, pubkeySpkiDer: Uint8List(0), timestampMs: 0, cryptoMode: ""),
    bodyBytes: bodyBytes,
    signature: signature,
  );
}

class PeerHelloAck {
  final Uint8List nodeKey;
  final int serverTimeMs;

  const PeerHelloAck({required this.nodeKey, required this.serverTimeMs});
}

Uint8List encodePeerHelloAck(PeerHelloAck a) {
  final w = ProtoWriter();
  w.writeBytesField(1, a.nodeKey);
  w.writeUint64(2, a.serverTimeMs);
  return w.takeBytes();
}

PeerHelloAck decodePeerHelloAck(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List nodeKey = Uint8List(0);
  var serverTimeMs = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        nodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        serverTimeMs = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return PeerHelloAck(nodeKey: nodeKey, serverTimeMs: serverTimeMs);
}

class RelayData {
  final int targetNodeId64;
  final Uint8List targetNodeKey;
  final int sourceNodeId64;
  final Uint8List sourceNodeKey;
  final Uint8List payload;

  const RelayData({
    required this.targetNodeId64,
    required this.targetNodeKey,
    required this.sourceNodeId64,
    required this.sourceNodeKey,
    required this.payload,
  });
}

Uint8List encodeRelayData(RelayData r) {
  final w = ProtoWriter();
  w.writeFixed64(1, r.targetNodeId64);
  w.writeBytesField(2, r.targetNodeKey);
  w.writeFixed64(3, r.sourceNodeId64);
  w.writeBytesField(4, r.sourceNodeKey);
  w.writeBytesField(5, r.payload);
  return w.takeBytes();
}

RelayData decodeRelayData(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var targetNodeId64 = 0;
  Uint8List targetNodeKey = Uint8List(0);
  var sourceNodeId64 = 0;
  Uint8List sourceNodeKey = Uint8List(0);
  Uint8List payload = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeId64 = r.readFixed64();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 3:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        sourceNodeId64 = r.readFixed64();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        sourceNodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        payload = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return RelayData(
    targetNodeId64: targetNodeId64,
    targetNodeKey: targetNodeKey,
    sourceNodeId64: sourceNodeId64,
    sourceNodeKey: sourceNodeKey,
    payload: payload,
  );
}

class ConnectHint {
  final int targetNodeId64;
  final Uint8List targetNodeKey;

  const ConnectHint({required this.targetNodeId64, required this.targetNodeKey});
}

Uint8List encodeConnectHint(ConnectHint h) {
  final w = ProtoWriter();
  w.writeFixed64(1, h.targetNodeId64);
  w.writeBytesField(2, h.targetNodeKey);
  return w.takeBytes();
}

ConnectHint decodeConnectHint(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var targetNodeId64 = 0;
  Uint8List targetNodeKey = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeId64 = r.readFixed64();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeKey = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return ConnectHint(targetNodeId64: targetNodeId64, targetNodeKey: targetNodeKey);
}

class ConnectHintAck {
  final bool found;
  final int targetNodeId64;
  final Uint8List targetNodeKey;
  final List<Endpoint> targetEndpoints;
  final int token;

  const ConnectHintAck({
    required this.found,
    required this.targetNodeId64,
    required this.targetNodeKey,
    required this.targetEndpoints,
    required this.token,
  });
}

ConnectHintAck decodeConnectHintAck(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var found = false;
  var targetNodeId64 = 0;
  Uint8List targetNodeKey = Uint8List(0);
  final targetEndpoints = <Endpoint>[];
  var token = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        found = r.readVarint() != 0;
        break;
      case 2:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeId64 = r.readFixed64();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        targetNodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        targetEndpoints.add(decodeEndpoint(r.readBytes()));
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        token = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return ConnectHintAck(
    found: found,
    targetNodeId64: targetNodeId64,
    targetNodeKey: targetNodeKey,
    targetEndpoints: targetEndpoints,
    token: token,
  );
}

class IncomingHint {
  final int sourceNodeId64;
  final Uint8List sourceNodeKey;
  final List<Endpoint> sourceEndpoints;
  final int token;

  const IncomingHint({
    required this.sourceNodeId64,
    required this.sourceNodeKey,
    required this.sourceEndpoints,
    required this.token,
  });
}

IncomingHint decodeIncomingHint(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var sourceNodeId64 = 0;
  Uint8List sourceNodeKey = Uint8List(0);
  final sourceEndpoints = <Endpoint>[];
  var token = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 1) {
          r.skipField(t.wireType);
          break;
        }
        sourceNodeId64 = r.readFixed64();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        sourceNodeKey = Uint8List.fromList(r.readBytes());
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        sourceEndpoints.add(decodeEndpoint(r.readBytes()));
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        token = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return IncomingHint(sourceNodeId64: sourceNodeId64, sourceNodeKey: sourceNodeKey, sourceEndpoints: sourceEndpoints, token: token);
}
