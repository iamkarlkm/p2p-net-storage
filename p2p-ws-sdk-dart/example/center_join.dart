import "dart:convert";
import "dart:io";

import "package:basic_utils/basic_utils.dart";
import "package:convert/convert.dart";
import "package:yaml/yaml.dart";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  final cfgPath = args.isNotEmpty
      ? args[0]
      : "${Directory.current.path}/../p2p-ws-protocol/examples/center_client.yaml";
  final cfgFile = File(cfgPath);
  final cfgDir = cfgFile.parent;
  final cfg = loadYaml(await cfgFile.readAsString()) as YamlMap;

  final wsUrl = (cfg["ws_url"] as String?) ?? "";
  if (wsUrl.isEmpty) {
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
  final renewSeconds = _parseInt(cfg["renew_seconds"], 0);
  final renewCount = _parseInt(cfg["renew_count"], 0);

  final magic = _parseInt(cfg["magic"], 0x1234);
  final version = _parseInt(cfg["version"], 1);
  final flagsPlain = _parseInt(cfg["flags_plain"], 4);
  final flagsEncrypted = _parseInt(cfg["flags_encrypted"], 5);
  final maxFramePayload = _parseInt(cfg["max_frame_payload"], 4 * 1024 * 1024);

  final keyf = await KeyFileReader.open(keyfilePath);
  if (expectedKeyIdHex.isNotEmpty && hex.encode(keyf.keyId) != expectedKeyIdHex) {
    throw StateError("key_id_sha256_hex mismatch");
  }

  final pem = await File(pemPath).readAsString();
  final privateKey = CryptoUtils.rsaPrivateKeyFromPem(pem);
  final pubDer = spkiDerFromRsaPrivateKeyPem(pem);
  final nodeKey = sha256Bytes(pubDer);

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

  final session = await P2PSession.connect(
    wsUrl: wsUrl,
    config: P2PSessionConfig(
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
      maxFramePayload: maxFramePayload,
    ),
    keyfile: keyf,
  );

  final hs = await session.handshake(privateKey: privateKey, clientPubkeySpkiDer: pubDer, clientId: "$nodeId64");

  final endpoints = _parseEndpoints(reportedEndpointsCfg);
  final appliedEndpoints = cachedObserved != null ? _applyObservedIp(cachedObserved.addr, endpoints) : endpoints;

  Future<CenterHelloAck> sendHello() async {
    final caps = NodeCaps(
      maxFramePayload: maxFramePayload,
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
    );
    final body = CenterHelloBody(
      nodeId64: nodeId64,
      pubkeySpkiDer: pubDer,
      reportedEndpoints: appliedEndpoints,
      caps: caps,
      timestampMs: nowMs(),
      cryptoMode: cryptoMode,
    );
    final bodyBytes = encodeCenterHelloBody(body);
    final sig = rsaSignSha256Pkcs1v15(privateKey, bodyBytes);
    final hello = CenterHello(body: body, signature: sig);
    final wrap = await session.request(
      command: P2PCommand.centerHello,
      data: encodeCenterHello(hello),
      expectedCommand: P2PCommand.centerHelloAck,
    );
    return decodeCenterHelloAck(wrap.data);
  }

  final ack1 = await sendHello();
  stdout.writeln("node_key=${hex.encode(nodeKey)}");
  stdout.writeln("ack.node_key=${hex.encode(ack1.nodeKey)}");
  stdout.writeln("observed=${ack1.observedEndpoint.transport} ${ack1.observedEndpoint.addr} ttl=${ack1.ttlSeconds}");

  if (presenceCachePathRaw.isNotEmpty) {
    final p = File(_resolvePath(cfgDir.path, presenceCachePathRaw));
    final out = jsonEncode({
      "observed_endpoint": {"transport": ack1.observedEndpoint.transport, "addr": ack1.observedEndpoint.addr},
    });
    await p.writeAsString(out);
  }

  if (renewSeconds > 0) {
    final count = (renewCount <= 0) ? 3 : renewCount;
    for (var i = 0; i < count; i++) {
      await Future<void>.delayed(Duration(seconds: renewSeconds));
      final ack = await sendHello();
      stdout.writeln("renew observed=${ack.observedEndpoint.transport} ${ack.observedEndpoint.addr} ttl=${ack.ttlSeconds} hsOffset=${hs.offset}");
    }
  }

  await session.close();
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
