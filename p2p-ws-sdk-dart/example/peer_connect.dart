import "dart:convert";
import "dart:io";
import "dart:typed_data";

import "package:basic_utils/basic_utils.dart";
import "package:convert/convert.dart";
import "package:yaml/yaml.dart";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  if (args.length < 2) {
    throw StateError("usage: dart run example/peer_connect.dart <peer_cfg.yaml> <target_node_id64>");
  }
  final cfgPath = args[0];
  final targetNodeId64 = int.parse(args[1]);

  final cfgFile = File(cfgPath);
  final cfgDir = cfgFile.parent;
  final cfg = loadYaml(await cfgFile.readAsString()) as YamlMap;

  final centerUrl = (cfg["ws_url"] as String?) ?? "";
  if (centerUrl.isEmpty) {
    throw StateError("ws_url is required");
  }
  final userIdRaw = cfg["user_id"];
  final nodeId64 = int.parse("${userIdRaw ?? 0}");

  final keyfilePathRaw = (cfg["keyfile_path"] as String?) ?? "";
  if (keyfilePathRaw.isEmpty) {
    throw StateError("keyfile_path is required");
  }
  final keyfilePath = _resolvePath(cfgDir.path, keyfilePathRaw);

  final expectedKeyIdHex = ((cfg["key_id_sha256_hex"] as String?) ?? "").trim().toLowerCase();
  final pemPathRaw = ((cfg["rsa_private_key_pem_path"] as String?) ?? "").trim();
  if (pemPathRaw.isEmpty) {
    throw StateError("rsa_private_key_pem_path is required");
  }
  final pemPath = _resolvePath(cfgDir.path, pemPathRaw);

  final cryptoMode = ((cfg["crypto_mode"] as String?) ?? "KEYFILE_XOR_RSA_OAEP").trim();
  final reportedEndpointsCfg = cfg["reported_endpoints"];
  final presenceCachePathRaw = ((cfg["presence_cache_path"] as String?) ?? "").trim();

  final magic = _parseInt(cfg["magic"], 0x1234);
  final version = _parseInt(cfg["version"], 1);
  final flagsPlain = _parseInt(cfg["flags_plain"], 4);
  final flagsEncrypted = _parseInt(cfg["flags_encrypted"], 5);
  final maxFramePayload = _parseInt(cfg["max_frame_payload"], 4 * 1024 * 1024);

  final keyfCenter = await KeyFileReader.open(keyfilePath);
  if (expectedKeyIdHex.isNotEmpty && hex.encode(keyfCenter.keyId) != expectedKeyIdHex) {
    throw StateError("key_id_sha256_hex mismatch");
  }

  final pem = await File(pemPath).readAsString();
  final privateKey = CryptoUtils.rsaPrivateKeyFromPem(pem);
  final pubDer = spkiDerFromRsaPrivateKeyPem(pem);

  Endpoint? cachedObserved;
  if (presenceCachePathRaw.isNotEmpty) {
    final p = File(_resolvePath(cfgDir.path, presenceCachePathRaw));
    if (await p.exists()) {
      try {
        final j = jsonDecode(await p.readAsString());
        if (j is Map<String, dynamic>) {
          final oe = j["observed_endpoint"];
          if (oe is Map<String, dynamic>) {
            final t = "${oe["transport"] ?? ""}";
            final a = "${oe["addr"] ?? ""}";
            if (t.isNotEmpty && a.isNotEmpty) {
              cachedObserved = Endpoint(transport: t, addr: a);
            }
          }
        }
      } catch (_) {}
    }
  }

  final center = await P2PSession.connect(
    wsUrl: centerUrl,
    config: P2PSessionConfig(
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
      maxFramePayload: maxFramePayload,
    ),
    keyfile: keyfCenter,
  );

  await center.handshake(privateKey: privateKey, clientPubkeySpkiDer: pubDer, clientId: "$nodeId64");

  final endpoints = _parseEndpoints(reportedEndpointsCfg);
  final appliedEndpoints = cachedObserved != null ? _applyObservedIp(cachedObserved.addr, endpoints) : endpoints;
  await _centerHello(
    center,
    privateKey,
    nodeId64: nodeId64,
    pubDer: pubDer,
    cryptoMode: cryptoMode,
    endpoints: appliedEndpoints,
    caps: NodeCaps(
      maxFramePayload: maxFramePayload,
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
    ),
  );

  final nodeKey = sha256Bytes(pubDer);
  final getNodeWrap = await center.request(
    command: P2PCommand.centerGetNode,
    data: encodeGetNode(GetNode(nodeId64: targetNodeId64, nodeKey: Uint8List(0))),
    expectedCommand: P2PCommand.centerGetNodeAck,
  );
  final getNodeAck = decodeGetNodeAck(getNodeWrap.data);
  if (!getNodeAck.found || getNodeAck.endpoints.isEmpty) {
    throw StateError("target not found");
  }
  final target = getNodeAck.endpoints.firstWhere((e) => e.transport == "ws", orElse: () => getNodeAck.endpoints.first);
  final peerWsUrl = "${target.transport}://${target.addr}";

  stdout.writeln("self_node_key=${hex.encode(nodeKey)}");
  stdout.writeln("target_node_key=${hex.encode(getNodeAck.nodeKey)}");
  stdout.writeln("connect $peerWsUrl");

  final keyfPeer = await KeyFileReader.open(keyfilePath);
  final peer = await P2PSession.connect(
    wsUrl: peerWsUrl,
    config: P2PSessionConfig(
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
      maxFramePayload: maxFramePayload,
    ),
    keyfile: keyfPeer,
  );

  await peer.handshake(privateKey: privateKey, clientPubkeySpkiDer: pubDer, clientId: "$nodeId64");

  final phBody = PeerHelloBody(nodeId64: nodeId64, pubkeySpkiDer: pubDer, timestampMs: nowMs(), cryptoMode: cryptoMode);
  final phBodyBytes = encodePeerHelloBody(phBody);
  final phSig = rsaSignSha256Pkcs1v15(privateKey, phBodyBytes);
  final ph = PeerHello(body: phBody, signature: phSig);
  final phWrap = await peer.request(
    command: P2PCommand.peerHello,
    data: encodePeerHello(ph),
    expectedCommand: P2PCommand.peerHelloAck,
  );
  final phAck = decodePeerHelloAck(phWrap.data);
  stdout.writeln("peer ack node_key=${hex.encode(phAck.nodeKey)} server_time_ms=${phAck.serverTimeMs}");

  final echo = P2PWrapper(seq: peer.nextSeq(), command: 1, data: Uint8List.fromList(utf8.encode("hello")));
  await peer.sendEncrypted(echo);

  await Future<void>.delayed(const Duration(milliseconds: 300));
  await peer.close();
  await center.close();
}

Future<void> _centerHello(
  P2PSession center,
  RSAPrivateKey privateKey, {
  required int nodeId64,
  required Uint8List pubDer,
  required String cryptoMode,
  required List<Endpoint> endpoints,
  required NodeCaps caps,
}) async {
  final body = CenterHelloBody(
    nodeId64: nodeId64,
    pubkeySpkiDer: pubDer,
    reportedEndpoints: endpoints,
    caps: caps,
    timestampMs: nowMs(),
    cryptoMode: cryptoMode,
  );
  final bodyBytes = encodeCenterHelloBody(body);
  final sig = rsaSignSha256Pkcs1v15(privateKey, bodyBytes);
  final hello = CenterHello(body: body, signature: sig);
  final wrap = await center.request(
    command: P2PCommand.centerHello,
    data: encodeCenterHello(hello),
    expectedCommand: P2PCommand.centerHelloAck,
  );
  final ack = decodeCenterHelloAck(wrap.data);
  stdout.writeln("center observed=${ack.observedEndpoint.transport} ${ack.observedEndpoint.addr} ttl=${ack.ttlSeconds}");
}

List<Endpoint> _parseEndpoints(Object? v) {
  if (v is! List) return const [];
  final out = <Endpoint>[];
  for (final e in v) {
    if (e is Map) {
      final t = "${e["transport"] ?? ""}";
      final a = "${e["addr"] ?? ""}";
      if (t.isNotEmpty && a.isNotEmpty) {
        out.add(Endpoint(transport: t, addr: a));
      }
    }
  }
  return out;
}

List<Endpoint> _applyObservedIp(String observedAddr, List<Endpoint> endpoints) {
  final parts = observedAddr.split(":");
  if (parts.isEmpty) return endpoints;
  final ip = parts[0];
  if (ip.isEmpty) return endpoints;
  return endpoints.map((e) {
    final p = e.addr.split(":");
    if (p.length >= 2) {
      return Endpoint(transport: e.transport, addr: "$ip:${p.sublist(1).join(":")}");
    }
    return e;
  }).toList(growable: false);
}

int _parseInt(Object? v, int def) {
  if (v == null) return def;
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) {
    final s = v.trim();
    if (s.isEmpty) return def;
    if (s.startsWith("0x") || s.startsWith("0X")) {
      return int.parse(s.substring(2), radix: 16);
    }
    return int.parse(s);
  }
  return def;
}

String _resolvePath(String baseDir, String path) {
  final p = path.replaceAll("\\", "/");
  if (p.startsWith("/") || RegExp(r"^[A-Za-z]:/").hasMatch(p)) {
    return p;
  }
  return File("$baseDir/$p").absolute.path;
}
