import "dart:async";
import "dart:convert";
import "dart:io";
import "dart:typed_data";

import "package:basic_utils/basic_utils.dart";
import "package:convert/convert.dart";
import "package:crypto/crypto.dart" as crypto;
import "package:yaml/yaml.dart";

import "commands.dart";
import "crypto.dart";
import "handshake.dart";
import "keyfile.dart";
import "messages/control_plane.dart";
import "messages/data.dart";
import "messages/wrapper.dart";
import "rsa.dart";
import "server.dart";
import "session.dart";
import "shared_storage.dart";

class PeerNodeConfig {
  final String userId;
  final String configDir;
  final String wsUrl;
  final int listenPort;
  final String wsPath;
  final String keyfilePath;
  final String keyIdSha256Hex;
  final String rsaPrivateKeyPemPath;
  final String cryptoMode;
  final List<Endpoint> reportedEndpoints;
  final Map<int, String> storageLocations;
  final Map<int, String> imStorageLocations;
  final String presenceCachePath;
  final String cooldownCachePath;
  final bool enableConnectHint;
  final int renewSeconds;
  final int magic;
  final int version;
  final int flagsPlain;
  final int flagsEncrypted;
  final int maxFramePayload;

  const PeerNodeConfig({
    required this.userId,
    required this.configDir,
    required this.wsUrl,
    required this.listenPort,
    required this.wsPath,
    required this.keyfilePath,
    required this.keyIdSha256Hex,
    required this.rsaPrivateKeyPemPath,
    required this.cryptoMode,
    required this.reportedEndpoints,
    required this.storageLocations,
    required this.imStorageLocations,
    required this.presenceCachePath,
    required this.cooldownCachePath,
    required this.enableConnectHint,
    required this.renewSeconds,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  });

  static Future<PeerNodeConfig> fromYamlFile(String path) async {
    final cfgFile = File(path);
    final cfgDir = cfgFile.parent;
    final cfg = loadYaml(await cfgFile.readAsString()) as YamlMap;

    String strVal(String k, String def) {
      final v = cfg[k];
      if (v == null) return def;
      return "$v".trim();
    }

    int intVal(String k, int def) {
      final v = cfg[k];
      if (v == null) return def;
      if (v is int) return v;
      if (v is num) return v.toInt();
      final s = "$v".trim();
      if (s.isEmpty) return def;
      if (s.startsWith("0x") || s.startsWith("0X")) {
        return int.parse(s.substring(2), radix: 16);
      }
      return int.parse(s);
    }

    int normalizeStoreId(Object? raw) {
      if (raw == null) return 0;
      final v = int.tryParse("$raw") ?? 0;
      if (v == 0) return 0;
      return v & 0xFFFFFFFF;
    }

    String resolvePath(String p) {
      final pp = p.replaceAll("\\", "/");
      if (pp.startsWith("/") || RegExp(r"^[A-Za-z]:/").hasMatch(pp)) {
        return pp;
      }
      return File("${cfgDir.path}/$pp").absolute.path;
    }

    final userId = strVal("user_id", "");
    final wsUrl = strVal("ws_url", "");
    final listenPort = intVal("listen_port", 0);
    final wsPath = strVal("ws_path", "/p2p");

    if (userId.isEmpty) throw StateError("user_id is required");
    if (wsUrl.isEmpty) throw StateError("ws_url is required");
    if (listenPort <= 0) throw StateError("listen_port is required");

    final keyfilePathRaw = strVal("keyfile_path", "");
    if (keyfilePathRaw.isEmpty) throw StateError("keyfile_path is required");
    final keyfilePath = resolvePath(keyfilePathRaw);

    final pemPathRaw = strVal("rsa_private_key_pem_path", "");
    if (pemPathRaw.isEmpty) throw StateError("rsa_private_key_pem_path is required");
    final rsaPrivateKeyPemPath = resolvePath(pemPathRaw);

    final keyIdSha256Hex = strVal("key_id_sha256_hex", "").toLowerCase();
    final cryptoMode = strVal("crypto_mode", "KEYFILE_XOR_RSA_OAEP");

    final presenceCachePathRaw = strVal("presence_cache_path", "");
    final presenceCachePath = presenceCachePathRaw.isEmpty ? "" : resolvePath(presenceCachePathRaw);
    final cooldownCachePathRaw = strVal("cooldown_cache_path", "");
    final cooldownCachePath = cooldownCachePathRaw.isEmpty ? "" : resolvePath(cooldownCachePathRaw);

    final enableConnectHint = cfg["enable_connect_hint"] == true;
    final renewSeconds = intVal("renew_seconds", 0);

    final magic = intVal("magic", 0x1234);
    final version = intVal("version", 1);
    final flagsPlain = intVal("flags_plain", 4);
    final flagsEncrypted = intVal("flags_encrypted", 5);
    final maxFramePayload = intVal("max_frame_payload", 4 * 1024 * 1024);

    final reportedEndpoints = <Endpoint>[];
    final rep = cfg["reported_endpoints"];
    if (rep is List) {
      for (final e in rep) {
        if (e is Map) {
          final t = "${e["transport"] ?? ""}".trim();
          final a = "${e["addr"] ?? ""}".trim();
          if (t.isNotEmpty && a.isNotEmpty) {
            reportedEndpoints.add(Endpoint(transport: t, addr: a));
          }
        }
      }
    }

    final storageLocations = <int, String>{};
    final sl = cfg["storage_locations"];
    if (sl is Map) {
      for (final e in sl.entries) {
        final k = normalizeStoreId(e.key);
        final v = "${e.value ?? ""}".trim();
        if (k != 0 && v.isNotEmpty) {
          storageLocations[k] = resolvePath(v);
        }
      }
    } else if (sl is List) {
      for (final e in sl) {
        if (e is Map) {
          final k = normalizeStoreId(e["store_id"] ?? e["storeId"]);
          final v = "${e["path"] ?? e["dir"] ?? ""}".trim();
          if (k != 0 && v.isNotEmpty) {
            storageLocations[k] = resolvePath(v);
          }
        }
      }
    }

    final imStorageLocations = <int, String>{};
    final isl = cfg["im_storage_locations"];
    if (isl is Map) {
      for (final e in isl.entries) {
        final k = normalizeStoreId(e.key);
        final v = "${e.value ?? ""}".trim();
        if (k != 0 && v.isNotEmpty) {
          imStorageLocations[k] = resolvePath(v);
        }
      }
    } else if (isl is List) {
      for (final e in isl) {
        if (e is Map) {
          final k = normalizeStoreId(e["store_id"] ?? e["storeId"]);
          final v = "${e["path"] ?? e["dir"] ?? ""}".trim();
          if (k != 0 && v.isNotEmpty) {
            imStorageLocations[k] = resolvePath(v);
          }
        }
      }
    }

    return PeerNodeConfig(
      userId: userId,
      configDir: cfgDir.absolute.path,
      wsUrl: wsUrl,
      listenPort: listenPort,
      wsPath: wsPath,
      keyfilePath: keyfilePath,
      keyIdSha256Hex: keyIdSha256Hex,
      rsaPrivateKeyPemPath: rsaPrivateKeyPemPath,
      cryptoMode: cryptoMode,
      reportedEndpoints: reportedEndpoints,
      storageLocations: storageLocations,
      imStorageLocations: imStorageLocations,
      presenceCachePath: presenceCachePath,
      cooldownCachePath: cooldownCachePath,
      enableConnectHint: enableConnectHint,
      renewSeconds: renewSeconds,
      magic: magic,
      version: version,
      flagsPlain: flagsPlain,
      flagsEncrypted: flagsEncrypted,
      maxFramePayload: maxFramePayload,
    );
  }
}

class PeerNode {
  final PeerNodeConfig config;
  final SharedStorageRegistry storage;
  final String storageDir;

  final Map<String, _EndpointCooldown> _endpointCooldown;
  final Set<int> _pendingConnectHintTargets = {};
  bool _flushingConnectHints = false;
  int _dialRunId = 0;
  Timer? _renewTimer;
  bool _stopped = false;

  P2PServer? _server;
  P2PSession? _center;
  StreamSubscription<P2PWrapper>? _sub;

  late final RSAPrivateKey _privateKey;
  late final Uint8List _pubDer;
  late final Uint8List _nodeKey32;
  late final int _nodeId64;

  PeerNode._(this.config, this.storage, this.storageDir, this._endpointCooldown);

  static Future<PeerNode> fromConfig(PeerNodeConfig cfg) async {
    final nodeId64 = int.parse(cfg.userId);
    final pem = await File(cfg.rsaPrivateKeyPemPath).readAsString();
    final privateKey = CryptoUtils.rsaPrivateKeyFromPem(pem);
    final pubDer = spkiDerFromRsaPrivateKeyPem(pem);
    final nodeKey32 = sha256Bytes(pubDer);

    final keyf = await KeyFileReader.open(cfg.keyfilePath);
    if (cfg.keyIdSha256Hex.isNotEmpty && hex.encode(keyf.keyId) != cfg.keyIdSha256Hex) {
      await keyf.close();
      throw StateError("key_id_sha256_hex mismatch");
    }
    await keyf.close();

    if (cfg.storageLocations.isEmpty && cfg.imStorageLocations.isEmpty) {
      throw StateError("storage_locations or im_storage_locations is required");
    }
    final storage = SharedStorageRegistry();
    for (final e in cfg.storageLocations.entries) {
      storage.registerStorageLocation(e.key, Directory(e.value));
    }
    for (final e in cfg.imStorageLocations.entries) {
      storage.registerStorageLocation(e.key, Directory(e.value));
    }

    final storageDir = Directory("${cfg.configDir}/../data/node_${cfg.userId}").absolute.path;

    final cooldownPath = cfg.cooldownCachePath.isNotEmpty ? cfg.cooldownCachePath : cfg.presenceCachePath;
    final endpointCooldown = _loadCooldownCache(cooldownPath);

    final n = PeerNode._(cfg, storage, storageDir, endpointCooldown);
    n._privateKey = privateKey;
    n._pubDer = pubDer;
    n._nodeKey32 = nodeKey32;
    n._nodeId64 = nodeId64;
    return n;
  }

  Future<void> start() async {
    await Directory(storageDir).create(recursive: true);

    final server = P2PServer(
      config: P2PServerConfig(
        listenPort: config.listenPort,
        wsPath: config.wsPath,
        magic: config.magic,
        version: config.version,
        flagsPlain: config.flagsPlain,
        flagsEncrypted: config.flagsEncrypted,
        maxFramePayload: config.maxFramePayload,
      ),
      keyfilePath: config.keyfilePath,
      storage: storage,
      serverNodeKey: _nodeKey32,
    );
    await server.start();
    _server = server;

    _stopped = false;
    unawaited(_connectCenterLoop());
  }

  Future<void> stop() async {
    _stopped = true;
    _dialRunId += 1;
    _renewTimer?.cancel();
    _renewTimer = null;
    final c = _center;
    _center = null;
    await c?.close();
    await _sub?.cancel();
    _sub = null;
    await _server?.stop();
    _server = null;
  }

  Future<void> connectHint(int targetNodeId64) async {
    if (!config.enableConnectHint) return;
    _pendingConnectHintTargets.add(targetNodeId64);
    final center = _center;
    if (center == null) return;
    await _flushPendingConnectHints(center);
  }

  Future<void> _connectCenterLoop() async {
    var backoffMs = 1000;
    while (!_stopped) {
      try {
        await _connectCenterOnce();
        backoffMs = 1000;
      } catch (_) {
      }
      if (_stopped) return;
      await Future<void>.delayed(Duration(milliseconds: backoffMs));
      backoffMs = backoffMs < 30000 ? backoffMs * 2 : 30000;
    }
  }

  Future<void> _connectCenterOnce() async {
    KeyFileReader? keyf;
    P2PSession? center;
    try {
      keyf = await KeyFileReader.open(config.keyfilePath);
      final center0 = await P2PSession.connect(
        wsUrl: config.wsUrl,
        config: P2PSessionConfig(
          magic: config.magic,
          version: config.version,
          flagsPlain: config.flagsPlain,
          flagsEncrypted: config.flagsEncrypted,
          maxFramePayload: config.maxFramePayload,
        ),
        keyfile: keyf,
      );
      center = center0;
      _center = center0;

      final done = Completer<void>();

      await center0.handshake(privateKey: _privateKey, clientPubkeySpkiDer: _pubDer, clientId: config.userId);

      final eps0 = config.reportedEndpoints;
      final cachedObserved = _loadPresenceCacheObservedAddr(config.presenceCachePath);
      var reportedEndpoints = cachedObserved == null ? eps0 : _applyObservedIpToEndpoints(eps0, cachedObserved);

      Future<CenterHelloAck> sendHello() async {
        final caps = NodeCaps(
          maxFramePayload: config.maxFramePayload,
          magic: config.magic,
          version: config.version,
          flagsPlain: config.flagsPlain,
          flagsEncrypted: config.flagsEncrypted,
        );
        final body = CenterHelloBody(
          nodeId64: _nodeId64,
          pubkeySpkiDer: _pubDer,
          reportedEndpoints: reportedEndpoints,
          caps: caps,
          timestampMs: nowMs(),
          cryptoMode: config.cryptoMode,
        );
        final bodyBytes = encodeCenterHelloBody(body);
        final sig = rsaSignSha256Pkcs1v15(_privateKey, bodyBytes);
        final hello = CenterHello(body: body, signature: sig);
        final w = await center0.request(
          command: P2PCommand.centerHello,
          data: encodeCenterHello(hello),
          expectedCommand: P2PCommand.centerHelloAck,
        );
        return decodeCenterHelloAck(w.data);
      }

    var ack = await sendHello();
    var lastObservedAddr = ack.observedEndpoint.addr;
    if (ack.observedEndpoint.addr.isNotEmpty) {
      _savePresenceCacheObserved(config.presenceCachePath, ack.observedEndpoint);

      final observedAddr = ack.observedEndpoint.addr;
      final nextReported = _applyObservedIpToEndpoints(eps0, observedAddr);
      if (!_sameEndpoints(reportedEndpoints, nextReported)) {
        reportedEndpoints = nextReported;
        ack = await sendHello();
        _savePresenceCacheObserved(config.presenceCachePath, ack.observedEndpoint);
        lastObservedAddr = ack.observedEndpoint.addr;
      }
    }

    if (config.renewSeconds > 0) {
      _renewTimer?.cancel();
      _renewTimer = Timer.periodic(Duration(seconds: config.renewSeconds), (_) async {
        try {
          final a = await sendHello();
          if (a.observedEndpoint.addr.isNotEmpty) {
            _savePresenceCacheObserved(config.presenceCachePath, a.observedEndpoint);
            if (a.observedEndpoint.addr != lastObservedAddr) {
              lastObservedAddr = a.observedEndpoint.addr;
              final nextReported = _applyObservedIpToEndpoints(eps0, lastObservedAddr);
              if (!_sameEndpoints(reportedEndpoints, nextReported)) {
                reportedEndpoints = nextReported;
                final a2 = await sendHello();
                if (a2.observedEndpoint.addr.isNotEmpty) {
                  _savePresenceCacheObserved(config.presenceCachePath, a2.observedEndpoint);
                  lastObservedAddr = a2.observedEndpoint.addr;
                }
              }
            }
          }
        } catch (_) {
          unawaited(center0.close());
        }
      });
    }

      await _sub?.cancel();
      _sub = center0.incoming.listen((w) async {
      if (w.command == P2PCommand.centerIncomingHint && config.enableConnectHint) {
        final ih = decodeIncomingHint(w.data);
        if (ih.sourceEndpoints.isNotEmpty) {
          await _dialHintEndpoints(ih.sourceEndpoints);
        }
        return;
      }

      if (w.command == P2PCommand.centerRelayData) {
        final rd = decodeRelayData(w.data);
        if (!_equalsBytes(rd.targetNodeKey, _nodeKey32)) {
          return;
        }
        if (rd.payload.isEmpty) return;
        P2PWrapper inner;
        try {
          inner = decodeWrapper(rd.payload);
        } catch (_) {
          return;
        }

        if (inner.command == P2PCommand.putFile || inner.command == P2PCommand.forcePutFile) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getSandboxFileForWrite(req.storeId, req.path);
            if (inner.command == P2PCommand.putFile && await file.exists()) {
              final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("file exists"))));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
              return;
            }
            await file.writeAsBytes(req.data);
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.getFile) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
            final content = Uint8List.fromList(await file.readAsBytes());
            final resp = FileDataModel(storeId: req.storeId, length: content.length, data: content, path: req.path, md5: "", blockSize: 0);
            final respWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.okGetFile, data: encodeFileDataModel(resp)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, respWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.getFileSegments) {
          final req = decodeFileSegmentsDataModel(inner.data);
          try {
            final file = storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
            final raf = await file.open();
            try {
              await raf.setPosition(req.start);
              final block = await raf.read(req.blockSize);
              final resp = FileSegmentsDataModel(
                storeId: req.storeId,
                length: req.length != 0 ? req.length : file.lengthSync(),
                start: req.start,
                blockIndex: req.blockIndex,
                blockSize: req.blockSize,
                blockData: Uint8List.fromList(block),
                blockMd5: md5Hex(Uint8List.fromList(block)),
                path: req.path,
                md5: req.md5.isEmpty ? await _md5HexFile(file) : req.md5,
              );
              final respWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.okGetFileSegments, data: encodeFileSegmentsDataModel(resp)));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, respWrap, w.seq);
              return;
            } finally {
              await raf.close();
            }
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.putFileSegments) {
          final req = decodeFileSegmentsDataModel(inner.data);
          try {
            if (req.blockMd5.isNotEmpty) {
              final got = md5Hex(req.blockData);
              if (got.toLowerCase() != req.blockMd5.toLowerCase()) {
                final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.invalidData, data: encodeStdError(StdError("Md5 check error -> ${req.blockMd5}"))));
                await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
                return;
              }
            }
            final file = storage.getSandboxFileForWrite(req.storeId, req.path);
            final raf = await file.open(mode: FileMode.writeOnlyAppend);
            try {
              await raf.setPosition(req.start);
              await raf.writeFrom(req.blockData);
            } finally {
              await raf.close();
            }
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.putFileSegmentsComplete) {
          final req = decodeFileSegmentsDataModel(inner.data);
          try {
            final file = storage.getSandboxFileForWrite(req.storeId, req.path);
            final actualLen = file.existsSync() ? file.lengthSync() : 0;
            if (actualLen != req.length) {
              final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.invalidData, data: encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"))));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
              return;
            }
            if (req.md5.isNotEmpty) {
              final actualMd5 = await _md5HexFile(file);
              if (actualMd5.toLowerCase() != req.md5.toLowerCase()) {
                final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.invalidData, data: encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"))));
                await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
                return;
              }
            }
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.checkFile) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
            final actualLen = file.lengthSync();
            if (actualLen != req.length) {
              final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"))));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
              return;
            }
            if (req.md5.isNotEmpty) {
              final actualMd5 = await _md5HexFile(file);
              if (actualMd5 != req.md5) {
                final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"))));
                await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
                return;
              }
            }
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.infoFile) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
            final len = file.lengthSync();
            final md5hex = req.md5.isEmpty ? await _md5HexFile(file) : req.md5;
            const blockSize = 8 * 1024 * 1024;
            final resp = FileDataModel(storeId: req.storeId, length: len, data: Uint8List(0), path: req.path, md5: md5hex, blockSize: blockSize);
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: encodeFileDataModel(resp)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.fileRename) {
          final req = decodeFileRenameRequest(inner.data);
          try {
            final src = storage.getSandboxFile(req.storeId, req.srcPath);
            final t = FileSystemEntity.typeSync(src.path, followLinks: false);
            if (t == FileSystemEntityType.notFound) {
              final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("file not found"))));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
              return;
            }
            final dst = storage.getSandboxFileForWrite(req.storeId, req.dstPath);
            src.renameSync(dst.path);
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.fileList) {
          final req = decodeFileListRequest(inner.data);
          var page = req.page <= 0 ? 1 : req.page;
          var pageSize = req.pageSize <= 0 ? 100 : req.pageSize;
          if (pageSize > 1000) pageSize = 1000;
          try {
            final dirFile = storage.getSandboxFile(req.storeId, req.path);
            final dirType = FileSystemEntity.typeSync(dirFile.path, followLinks: false);
            if (dirType != FileSystemEntityType.directory) {
              final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("not a directory"))));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
              return;
            }
            final d = Directory(dirFile.path);
            final entries = d
                .listSync(followLinks: false)
                .where((e) => FileSystemEntity.typeSync(e.path, followLinks: false) != FileSystemEntityType.link)
                .toList(growable: false);
            entries.sort((a, b) => a.path.compareTo(b.path));
            final total = entries.length;
            final start = (page - 1) * pageSize;
            var end = start + pageSize;
            if (end > total) end = total;
            final items = <FileListEntry>[];
            if (start < total) {
              for (final e in entries.sublist(start, end)) {
                final st = e.statSync();
                final isDir = st.type == FileSystemEntityType.directory;
                final name = e.uri.pathSegments.isNotEmpty ? e.uri.pathSegments.last : e.path;
                final p = req.path.endsWith("/") ? "${req.path}$name" : "${req.path}/$name";
                items.add(FileListEntry(name: name, path: p, isDir: isDir, size: isDir ? 0 : st.size, modifiedMs: st.modified.millisecondsSinceEpoch));
              }
            } else {
              page = 1;
            }
            final resp = FileListResponse(page: page, pageSize: pageSize, total: total, items: items);
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: encodeFileListResponse(resp)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.fileExists) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getSandboxFile(req.storeId, req.path);
            final t = FileSystemEntity.typeSync(file.path, followLinks: false);
            if (t != FileSystemEntityType.notFound) {
              final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
              await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
              return;
            }
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("not exists"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.fileMkdirs) {
          final req = decodeFileDataModel(inner.data);
          try {
            final file = storage.getSandboxFileForWrite(req.storeId, req.path);
            Directory(file.path).createSync(recursive: true);
            final okWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, okWrap, w.seq);
            return;
          } catch (e) {
            final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(StdError("$e"))));
            await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
            return;
          }
        }

        if (inner.command == P2PCommand.filesCommand) {
          final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("deprecated command"))));
          await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
          return;
        }

        if (inner.command == P2PCommand.filePutReq || inner.command == P2PCommand.fileGetReq) {
          final errWrap = encodeWrapper(P2PWrapper(seq: inner.seq, command: P2PCommand.stdError, data: encodeStdError(const StdError("deprecated command"))));
          await _sendRelay(rd.sourceNodeId64, rd.sourceNodeKey, errWrap, w.seq);
          return;
        }
      }
      }, onError: (_, __) {
        if (!done.isCompleted) {
          done.complete();
        }
      }, onDone: () {
        if (!done.isCompleted) {
          done.complete();
        }
      });

      unawaited(_flushPendingConnectHints(center0));

      await done.future;

      if (_stopped) {
        return;
      }
      _renewTimer?.cancel();
      _renewTimer = null;
      await _sub?.cancel();
      _sub = null;
      await center0.close();
      if (identical(_center, center0)) {
        _center = null;
      }
    } catch (_) {
      if (center != null) {
        try {
          await center.close();
        } catch (_) {}
      } else if (keyf != null) {
        try {
          await keyf.close();
        } catch (_) {}
      }
      rethrow;
    }
  }

  Future<void> _flushPendingConnectHints(P2PSession center) async {
    if (_flushingConnectHints) return;
    _flushingConnectHints = true;
    try {
      while (!_stopped && identical(_center, center) && _pendingConnectHintTargets.isNotEmpty) {
        final target = _pendingConnectHintTargets.first;
        try {
          final wrap = await center.request(
            command: P2PCommand.centerConnectHint,
            data: encodeConnectHint(ConnectHint(targetNodeId64: target, targetNodeKey: Uint8List(0))),
            expectedCommand: P2PCommand.centerConnectHint,
          );
          final ackHint = decodeConnectHintAck(wrap.data);
          _pendingConnectHintTargets.remove(target);
          if (ackHint.found && ackHint.targetEndpoints.isNotEmpty) {
            await _dialHintEndpoints(ackHint.targetEndpoints);
          }
        } catch (_) {
          return;
        }
        if (_pendingConnectHintTargets.isNotEmpty) {
          await Future<void>.delayed(const Duration(milliseconds: 200));
        }
      }
    } finally {
      _flushingConnectHints = false;
    }
  }

  Future<void> _sendRelay(int targetNodeId64, Uint8List targetNodeKey, Uint8List payload, int seq) async {
    final center = _center;
    if (center == null) return;
    final data = encodeRelayData(
      RelayData(
        targetNodeId64: targetNodeId64,
        targetNodeKey: targetNodeKey,
        sourceNodeId64: _nodeId64,
        sourceNodeKey: _nodeKey32,
        payload: payload,
      ),
    );
    await center.sendEncrypted(P2PWrapper(seq: seq, command: P2PCommand.centerRelayData, data: data));
  }

  Future<void> _dialHintEndpoints(List<Endpoint> endpoints) async {
    final runId = ++_dialRunId;
    final eps = endpoints.where((e) => !isCoolDownEndpoint(e)).toList(growable: false)
      ..sort((a, b) => endpointScore(b).compareTo(endpointScore(a)));
    if (eps.isEmpty) return;

    const maxParallel = 4;
    const staggerMs = 250;
    const timeoutMs = 4000;

    final queue = List<Endpoint>.from(eps);
    final results = StreamController<_DialResult>();
    var active = 0;
    var done = false;

    Future<void> startMore() async {
      if (_stopped || runId != _dialRunId) {
        done = true;
        if (!results.isClosed) {
          await results.close();
        }
        return;
      }
      while (!done && active < maxParallel && queue.isNotEmpty) {
        if (_stopped || runId != _dialRunId) {
          done = true;
          if (!results.isClosed) {
            await results.close();
          }
          return;
        }
        final ep = queue.removeAt(0);
        if (isCoolDownEndpoint(ep)) continue;
        active += 1;
        () async {
          final ok = await tryHandshakeOnce(ep, timeoutMs, runId: runId);
          if (!results.isClosed) {
            results.add(_DialResult(ep, ok));
          }
        }()
            .whenComplete(() {
          active -= 1;
          if (!done && active == 0 && queue.isEmpty && !results.isClosed) {
            results.close();
          }
        });
        if (queue.isNotEmpty) {
          await Future<void>.delayed(const Duration(milliseconds: staggerMs));
        }
      }
    }

    await startMore();
    final it = StreamIterator(results.stream);
    while (await it.moveNext()) {
      if (_stopped || runId != _dialRunId) {
        done = true;
        if (!results.isClosed) {
          await results.close();
        }
        break;
      }
      final r = it.current;
      if (r.ok) {
        done = true;
        markEndpointSuccess(r.endpoint);
        await results.close();
        break;
      } else {
        markEndpointFail(r.endpoint);
      }
      await startMore();
    }
  }

  bool isCoolDownEndpoint(Endpoint e) {
    final s = _endpointCooldown[endpointKey(e)];
    return s != null && s.untilMs > nowMs();
  }

  void markEndpointFail(Endpoint e) {
    final k = endpointKey(e);
    final prev = _endpointCooldown[k] ?? const _EndpointCooldown(0, 0);
    final fails = prev.fails + 1;
    const baseCooldownMs = 5000;
    const maxCooldownMs = 5 * 60 * 1000;
    final pow = fails - 1 > 6 ? 6 : (fails - 1);
    final cd = baseCooldownMs * (1 << pow);
    final untilMs = nowMs() + (cd > maxCooldownMs ? maxCooldownMs : cd);
    _endpointCooldown[k] = _EndpointCooldown(untilMs, fails);
    saveCooldownCache();
  }

  void markEndpointSuccess(Endpoint e) {
    final k = endpointKey(e);
    if (_endpointCooldown.containsKey(k)) {
      _endpointCooldown.remove(k);
      saveCooldownCache();
    }
  }

  void saveCooldownCache() {
    final p = config.cooldownCachePath.isNotEmpty ? config.cooldownCachePath : config.presenceCachePath;
    if (p.isEmpty) return;
    final obj = <String, Object?>{};
    _endpointCooldown.forEach((k, v) {
      obj[k] = {"untilMs": v.untilMs, "fails": v.fails};
    });
    _writeJsonFileMerge(p, {"endpoint_cooldown": obj});
  }

  String endpointKey(Endpoint e) => "${e.transport}|${e.addr}";

  int endpointScore(Endpoint e) {
    final host = endpointHost(e.addr);
    if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") return -1000;
    if (isPrivateIpv4(host)) return 10;
    if (RegExp(r"^\\d+\\.\\d+\\.\\d+\\.\\d+$").hasMatch(host)) return 100;
    return 30;
  }

  String endpointHost(String addr) {
    final s = addr.trim();
    final idx = s.lastIndexOf(":");
    if (idx <= 0) return s;
    return s.substring(0, idx);
  }

  bool isPrivateIpv4(String host) {
    final h = host.trim();
    if (!RegExp(r"^\\d+\\.\\d+\\.\\d+\\.\\d+$").hasMatch(h)) return false;
    final parts = h.split(".");
    final a = int.parse(parts[0]);
    final b = int.parse(parts[1]);
    if (a == 10) return true;
    if (a == 127) return true;
    if (a == 192 && b == 168) return true;
    if (a == 172 && b >= 16 && b <= 31) return true;
    return false;
  }

  Future<bool> tryHandshakeOnce(Endpoint endpoint, int timeoutMs, {required int runId}) async {
    if (_stopped || runId != _dialRunId) return false;
    final wsUrl = "${endpoint.transport}://${endpoint.addr}";
    P2PSession? s;
    try {
      s = await P2PSession.connect(
        wsUrl: wsUrl,
        config: P2PSessionConfig(
          magic: config.magic,
          version: config.version,
          flagsPlain: config.flagsPlain,
          flagsEncrypted: config.flagsEncrypted,
          maxFramePayload: config.maxFramePayload,
        ),
        keyfile: await KeyFileReader.open(config.keyfilePath),
      ).timeout(Duration(milliseconds: timeoutMs));

      if (_stopped || runId != _dialRunId) {
        await s.close();
        return false;
      }
      await s.handshake(privateKey: _privateKey, clientPubkeySpkiDer: _pubDer, clientId: config.userId).timeout(Duration(milliseconds: timeoutMs));

      if (_stopped || runId != _dialRunId) {
        await s.close();
        return false;
      }
      final body = PeerHelloBody(
        nodeId64: _nodeId64,
        pubkeySpkiDer: _pubDer,
        timestampMs: nowMs(),
        cryptoMode: config.cryptoMode,
      );
      final bodyBytes = encodePeerHelloBody(body);
      final sig = rsaSignSha256Pkcs1v15(_privateKey, bodyBytes);
      final ph = PeerHello(body: body, signature: sig);
      await s.request(command: P2PCommand.peerHello, data: encodePeerHello(ph), expectedCommand: P2PCommand.peerHelloAck).timeout(Duration(milliseconds: timeoutMs));
      await s.close();
      return true;
    } catch (_) {
      try {
        await s?.close();
      } catch (_) {}
      return false;
    }
  }
}

class _EndpointCooldown {
  final int untilMs;
  final int fails;

  const _EndpointCooldown(this.untilMs, this.fails);
}

class _DialResult {
  final Endpoint endpoint;
  final bool ok;

  const _DialResult(this.endpoint, this.ok);
}

Map<String, _EndpointCooldown> _loadCooldownCache(String path) {
  try {
    final f = File(path);
    if (!f.existsSync()) return {};
    final j = jsonDecode(f.readAsStringSync());
    if (j is! Map) return {};
    final m = j["endpoint_cooldown"];
    if (m is! Map) return {};
    final out = <String, _EndpointCooldown>{};
    for (final e in m.entries) {
      final k = "${e.key}";
      final v = e.value;
      if (v is! Map) continue;
      final untilMs = (v["untilMs"] is num) ? (v["untilMs"] as num).toInt() : int.tryParse("${v["untilMs"] ?? ""}") ?? 0;
      final fails = (v["fails"] is num) ? (v["fails"] as num).toInt() : int.tryParse("${v["fails"] ?? ""}") ?? 0;
      out[k] = _EndpointCooldown(untilMs, fails);
    }
    return out;
  } catch (_) {
    return {};
  }
}

String? _loadPresenceCacheObservedAddr(String presenceCachePath) {
  if (presenceCachePath.isEmpty) return null;
  try {
    final f = File(presenceCachePath);
    if (!f.existsSync()) return null;
    final j = jsonDecode(f.readAsStringSync());
    if (j is! Map) return null;
    final oe = j["observed_endpoint"];
    if (oe is! Map) return null;
    final addr = "${oe["addr"] ?? ""}".trim();
    if (addr.isEmpty) return null;
    return addr;
  } catch (_) {
    return null;
  }
}

void _savePresenceCacheObserved(String presenceCachePath, Endpoint observed) {
  if (presenceCachePath.isEmpty) return;
  if (observed.addr.isEmpty) return;
  _writeJsonFileMerge(presenceCachePath, {
    "observed_endpoint": {"transport": observed.transport.isEmpty ? "ws" : observed.transport, "addr": observed.addr}
  });
}

List<Endpoint> _applyObservedIpToEndpoints(List<Endpoint> endpoints, String observedAddr) {
  final host = observedAddr.split(":").first;
  if (host.isEmpty) return endpoints;
  return endpoints.map((e) {
    final addr = e.addr;
    final idx = addr.lastIndexOf(":");
    if (idx <= 0) return e;
    final port = addr.substring(idx + 1);
    return Endpoint(transport: e.transport, addr: "$host:$port");
  }).toList(growable: false);
}

bool _sameEndpoints(List<Endpoint> a, List<Endpoint> b) {
  if (identical(a, b)) return true;
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i].transport != b[i].transport) return false;
    if (a[i].addr != b[i].addr) return false;
  }
  return true;
}

void _writeJsonFileMerge(String path, Map<String, Object?> patch) {
  try {
    final f = File(path);
    Map<String, Object?> cur = {};
    if (f.existsSync()) {
      final j = jsonDecode(f.readAsStringSync());
      if (j is Map) {
        cur = Map<String, Object?>.fromEntries(j.entries.map((e) => MapEntry("${e.key}", e.value)));
      }
    }
    cur.addAll(patch);
    f.parent.createSync(recursive: true);
    f.writeAsStringSync(const JsonEncoder.withIndent("  ").convert(cur));
  } catch (_) {}
}

bool _equalsBytes(Uint8List a, Uint8List b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

Future<String> _md5HexFile(File file) async {
  final out = AccumulatorSink<crypto.Digest>();
  final input = crypto.md5.startChunkedConversion(out);
  final raf = await file.open();
  try {
    while (true) {
      final chunk = await raf.read(64 * 1024);
      if (chunk.isEmpty) break;
      input.add(chunk);
    }
  } finally {
    await raf.close();
  }
  input.close();
  return out.events.single.toString();
}
