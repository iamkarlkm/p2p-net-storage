import "dart:io";
import "dart:math";
import "dart:typed_data";

import "package:convert/convert.dart";
import "package:crypto/crypto.dart" as crypto;

import "commands.dart";
import "crypto.dart";
import "frame.dart";
import "handshake.dart";
import "keyfile.dart";
import "messages/control.dart";
import "messages/control_plane.dart";
import "messages/data.dart";
import "messages/wrapper.dart";
import "shared_storage.dart";
import "xor.dart";

class P2PServerConfig {
  final int listenPort;
  final String wsPath;
  final int magic;
  final int version;
  final int flagsPlain;
  final int flagsEncrypted;
  final int maxFramePayload;

  const P2PServerConfig({
    required this.listenPort,
    required this.wsPath,
    required this.magic,
    required this.version,
    required this.flagsPlain,
    required this.flagsEncrypted,
    required this.maxFramePayload,
  });
}

class P2PServer {
  final P2PServerConfig config;
  final String keyfilePath;
  final SharedStorageRegistry storage;
  final Uint8List serverNodeKey;

  HttpServer? _http;

  P2PServer({
    required this.config,
    required this.keyfilePath,
    required this.storage,
    required this.serverNodeKey,
  });

  Future<void> start() async {
    final http = await HttpServer.bind(InternetAddress.anyIPv4, config.listenPort);
    _http = http;
    http.listen(_handleHttp);
  }

  Future<void> stop() async {
    final http = _http;
    _http = null;
    await http?.close(force: true);
  }

  Future<void> _handleHttp(HttpRequest req) async {
    if (!WebSocketTransformer.isUpgradeRequest(req)) {
      req.response.statusCode = HttpStatus.badRequest;
      await req.response.close();
      return;
    }
    final ws = await WebSocketTransformer.upgrade(req);
    final keyf = await KeyFileReader.open(keyfilePath);
    final s = _InboundSession(ws, config, keyf, serverNodeKey, storage);
    await s.run();
  }
}

class _InboundSession {
  final WebSocket _ws;
  final P2PServerConfig _cfg;
  final KeyFileReader _keyf;
  final Uint8List _serverNodeKey;
  final SharedStorageRegistry _storage;

  int? _offset;

  late final Map<int, Future<void> Function(P2PWrapper)> _handlers = _buildHandlers();

  _InboundSession(this._ws, this._cfg, this._keyf, this._serverNodeKey, this._storage);

  Future<void> run() async {
    try {
      await for (final msg in _ws) {
        if (msg is! List<int>) continue;
        final f = decodeFrame(Uint8List.fromList(msg));
        final cipherPayload = f.cipherPayload;
        final off = _offset;
        Uint8List plainPayload;
        if (off == null) {
          plainPayload = Uint8List.fromList(cipherPayload);
        } else {
          final slice = await _keyf.readSlice(off, cipherPayload.length);
          plainPayload = xorNoWrap(Uint8List.fromList(cipherPayload), slice, 0);
        }
        final w = decodeWrapper(plainPayload);

        if (_offset == null) {
          if (w.command != P2PCommand.hand) {
            await _ws.close();
            return;
          }
          await _handleHand(w);
          continue;
        }

        final h = _handlers[w.command];
        if (h != null) {
          await h(w);
          continue;
        }
      }
    } finally {
      await _keyf.close();
    }
  }

  Map<int, Future<void> Function(P2PWrapper)> _buildHandlers() {
    return {
      P2PCommand.cryptUpdate: _handleCryptUpdate,
      P2PCommand.peerHello: _handlePeerHello,

      P2PCommand.putFile: _handlePutFile,
      P2PCommand.forcePutFile: _handlePutFile,
      P2PCommand.getFile: _handleGetFile,

      P2PCommand.getFileSegments: _handleGetFileSegments,
      P2PCommand.putFileSegments: _handlePutFileSegments,
      P2PCommand.putFileSegmentsComplete: _handlePutFileSegmentsComplete,

      P2PCommand.checkFile: _handleCheckFile,
      P2PCommand.infoFile: _handleInfoFile,
      P2PCommand.fileRename: _handleFileRename,
      P2PCommand.fileList: _handleFileList,
      P2PCommand.fileExists: _handleFileExists,
      P2PCommand.fileMkdirs: _handleFileMkdirs,

      P2PCommand.filesCommand: _handleDeprecated,
      P2PCommand.filePutReq: _handleDeprecated,
      P2PCommand.fileGetReq: _handleDeprecated,
    };
  }

  Future<void> _handleHand(P2PWrapper w) async {
    final hand = decodeHand(w.data);
    final selected = _keyf.keyId;
    final maxPayload = _cfg.maxFramePayload;
    final seed = Random.secure();
    final sessionId = Uint8List(16);
    for (var i = 0; i < sessionId.length; i++) {
      sessionId[i] = seed.nextInt(256);
    }
    final offset = seed.nextInt(1024);
    final ack = HandAckPlain(
      sessionId: sessionId,
      selectedKeyId: selected,
      offset: offset,
      maxFramePayload: maxPayload,
      headerPolicyId: 0,
    );
    final ackBytes = encodeHandAckPlain(ack);
    final pub = rsaPublicKeyFromSpkiDer(hand.clientPubkeySpkiDer);
    final encrypted = rsaOaepSha256Encrypt(pub, ackBytes);
    final out = encodeWrapper(P2PWrapper(seq: w.seq, command: P2PCommand.handAck, data: encrypted));
    final frame = encodeFrame(WireHeader(out.length, _cfg.magic, _cfg.version, _cfg.flagsPlain), out);
    _ws.add(frame);
    _offset = offset;
  }

  Future<void> _handleCryptUpdate(P2PWrapper w) async {
    final cu = decodeCryptUpdate(w.data);
    _offset = cu.offset;
  }

  Future<void> _handlePeerHello(P2PWrapper w) async {
    decodePeerHello(w.data);
    final ack = PeerHelloAck(nodeKey: _serverNodeKey, serverTimeMs: nowMs());
    final ackBytes = encodePeerHelloAck(ack);
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.peerHelloAck, data: ackBytes));
  }

  Future<void> _handleDeprecated(P2PWrapper w) async {
    final err = encodeStdError(const StdError("deprecated command"));
    await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
  }

  Future<void> _handlePutFile(P2PWrapper w) async {
    final m = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(m.storeId, m.path);
      if (w.command == P2PCommand.putFile && await file.exists()) {
        final err = encodeStdError(const StdError("file exists"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      await file.writeAsBytes(m.data);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleGetFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final content = Uint8List.fromList(await file.readAsBytes());
      final resp = FileDataModel(
        storeId: req.storeId,
        length: content.length,
        data: content,
        path: req.path,
        md5: "",
        blockSize: 0,
      );
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.okGetFile, data: encodeFileDataModel(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleGetFileSegments(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
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
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.okGetFileSegments, data: encodeFileSegmentsDataModel(resp)));
      } finally {
        await raf.close();
      }
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handlePutFileSegments(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      if (req.blockMd5.isNotEmpty) {
        final got = md5Hex(req.blockData);
        if (got.toLowerCase() != req.blockMd5.toLowerCase()) {
          final err = encodeStdError(StdError("Md5 check error -> ${req.blockMd5}"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
          return;
        }
      }
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final raf = await file.open(mode: FileMode.writeOnlyAppend);
      try {
        await raf.setPosition(req.start);
        await raf.writeFrom(req.blockData);
      } finally {
        await raf.close();
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handlePutFileSegmentsComplete(P2PWrapper w) async {
    final req = decodeFileSegmentsDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final actualLen = file.existsSync() ? file.lengthSync() : 0;
      if (actualLen != req.length) {
        final err = encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
        return;
      }
      if (req.md5.isNotEmpty) {
        final actualMd5 = await _md5HexFile(file);
        if (actualMd5.toLowerCase() != req.md5.toLowerCase()) {
          final err = encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.invalidData, data: err));
          return;
        }
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleCheckFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final actualLen = file.lengthSync();
      if (actualLen != req.length) {
        final err = encodeStdError(StdError("文件长度不一致 ${req.length} <> $actualLen"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      if (req.md5.isNotEmpty) {
        final actualMd5 = await _md5HexFile(file);
        if (actualMd5 != req.md5) {
          final err = encodeStdError(StdError("MD5校验错误 ${req.md5} <> $actualMd5"));
          await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
          return;
        }
      }
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleInfoFile(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getAndCheckExistsSandboxFile(req.storeId, req.path);
      final len = file.lengthSync();
      final md5hex = req.md5.isEmpty ? await _md5HexFile(file) : req.md5;
      const blockSize = 8 * 1024 * 1024;
      final resp = FileDataModel(
        storeId: req.storeId,
        length: len,
        data: Uint8List(0),
        path: req.path,
        md5: md5hex,
        blockSize: blockSize,
      );
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeFileDataModel(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileRename(P2PWrapper w) async {
    final req = decodeFileRenameRequest(w.data);
    try {
      final src = _storage.getSandboxFile(req.storeId, req.srcPath);
      final t = FileSystemEntity.typeSync(src.path, followLinks: false);
      if (t == FileSystemEntityType.notFound) {
        final err = encodeStdError(const StdError("file not found"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
        return;
      }
      final dst = _storage.getSandboxFileForWrite(req.storeId, req.dstPath);
      src.renameSync(dst.path);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileList(P2PWrapper w) async {
    final req = decodeFileListRequest(w.data);
    var page = req.page <= 0 ? 1 : req.page;
    var pageSize = req.pageSize <= 0 ? 100 : req.pageSize;
    if (pageSize > 1000) pageSize = 1000;
    try {
      final dirFile = _storage.getSandboxFile(req.storeId, req.path);
      final dirType = FileSystemEntity.typeSync(dirFile.path, followLinks: false);
      if (dirType != FileSystemEntityType.directory) {
        final err = encodeStdError(const StdError("not a directory"));
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
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
      final end = min(start + pageSize, total);
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
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: encodeFileListResponse(resp)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileExists(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFile(req.storeId, req.path);
      final t = FileSystemEntity.typeSync(file.path, followLinks: false);
      if (t != FileSystemEntityType.notFound) {
        await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
        return;
      }
      final err = encodeStdError(const StdError("not exists"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _handleFileMkdirs(P2PWrapper w) async {
    final req = decodeFileDataModel(w.data);
    try {
      final file = _storage.getSandboxFileForWrite(req.storeId, req.path);
      final d = Directory(file.path);
      d.createSync(recursive: true);
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdOk, data: Uint8List(0)));
    } catch (e) {
      final err = encodeStdError(StdError("$e"));
      await _sendEncrypted(P2PWrapper(seq: w.seq, command: P2PCommand.stdError, data: err));
    }
  }

  Future<void> _sendEncrypted(P2PWrapper w) async {
    final off = _offset;
    if (off == null) {
      throw StateError("not encrypted yet");
    }
    final plain = encodeWrapper(w);
    final slice = await _keyf.readSlice(off, plain.length);
    final cipher = xorNoWrap(plain, slice, 0);
    final frame = encodeFrame(WireHeader(cipher.length, _cfg.magic, _cfg.version, _cfg.flagsEncrypted), cipher);
    _ws.add(frame);
  }
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
