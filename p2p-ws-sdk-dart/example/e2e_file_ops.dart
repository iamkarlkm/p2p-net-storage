import "dart:convert";
import "dart:io";
import "dart:typed_data";

import "package:basic_utils/basic_utils.dart";
import "package:crypto/crypto.dart";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  final cfgPath = args.isNotEmpty ? args[0] : "${Directory.current.path}/../p2p-ws-protocol/examples/peer1.yaml";
  final cfg = await PeerNodeConfig.fromYamlFile(cfgPath);

  final keyf = await KeyFileReader.open(cfg.keyfilePath);
  final pem = await File(cfg.rsaPrivateKeyPemPath).readAsString();
  final privateKey = CryptoUtils.rsaPrivateKeyFromPem(pem);
  final pubDer = spkiDerFromRsaPrivateKeyPem(pem);

  final ep = cfg.reportedEndpoints.firstWhere((e) => e.transport == "ws", orElse: () => cfg.reportedEndpoints.first);
  final peerUrl = "${ep.transport}://${ep.addr}/p2p";

  final session = await P2PSession.connect(
    wsUrl: peerUrl,
    config: P2PSessionConfig(
      magic: cfg.magic,
      version: cfg.version,
      flagsPlain: cfg.flagsPlain,
      flagsEncrypted: cfg.flagsEncrypted,
      maxFramePayload: cfg.maxFramePayload,
    ),
    keyfile: keyf,
  );

  await session.handshake(privateKey: privateKey, clientPubkeySpkiDer: pubDer, clientId: cfg.userId);

  final nodeId64 = int.parse(cfg.userId);
  final phBody = PeerHelloBody(nodeId64: nodeId64, pubkeySpkiDer: pubDer, timestampMs: nowMs(), cryptoMode: cfg.cryptoMode);
  final phBodyBytes = encodePeerHelloBody(phBody);
  final phSig = rsaSignSha256Pkcs1v15(privateKey, phBodyBytes);
  final ph = PeerHello(body: phBody, signature: phSig);
  await session.request(command: P2PCommand.peerHello, data: encodePeerHello(ph), expectedCommand: P2PCommand.peerHelloAck);

  final storeId = cfg.storageLocations.keys.first;

  final helloBytes = Uint8List.fromList(utf8.encode("hello world"));
  await _putFile(session, storeId, "demo/hello.txt", helloBytes, force: true);
  final gotHello = await _getFile(session, storeId, "demo/hello.txt");
  if (!_eq(gotHello, helloBytes)) {
    throw StateError("GET_FILE mismatch");
  }

  final segBytes = _genBytes(200000);
  final segPath = "demo/seg.bin";
  final fullMd5 = md5.convert(segBytes).toString();
  final blockSize = 65536;
  for (var start = 0; start < segBytes.length; start += blockSize) {
    final end = (start + blockSize <= segBytes.length) ? start + blockSize : segBytes.length;
    final block = Uint8List.fromList(segBytes.sublist(start, end));
    final blockMd5 = md5.convert(block).toString();
    final req = FileSegmentsDataModel(
      storeId: storeId,
      length: segBytes.length,
      start: start,
      blockIndex: start ~/ blockSize,
      blockSize: block.length,
      blockData: block,
      blockMd5: blockMd5,
      path: segPath,
      md5: fullMd5,
    );
    final resp = await session.requestAny(command: P2PCommand.putFileSegments, data: encodeFileSegmentsDataModel(req));
    _expectOkOrThrow(resp);
  }

  final complete = FileSegmentsDataModel(
    storeId: storeId,
    length: segBytes.length,
    start: 0,
    blockIndex: 0,
    blockSize: blockSize,
    blockData: Uint8List(0),
    blockMd5: "",
    path: segPath,
    md5: fullMd5,
  );
  final completeResp = await session.requestAny(command: P2PCommand.putFileSegmentsComplete, data: encodeFileSegmentsDataModel(complete));
  _expectOkOrThrow(completeResp);

  final gotSeg = await _getFile(session, storeId, segPath);
  if (!_eq(gotSeg, segBytes)) {
    throw StateError("seg GET_FILE mismatch");
  }

  final segReq = FileSegmentsDataModel(
    storeId: storeId,
    length: segBytes.length,
    start: 12345,
    blockIndex: 0,
    blockSize: 4096,
    blockData: Uint8List(0),
    blockMd5: "",
    path: segPath,
    md5: "",
  );
  final segResp = await session.request(command: P2PCommand.getFileSegments, data: encodeFileSegmentsDataModel(segReq), expectedCommand: P2PCommand.okGetFileSegments);
  final segMsg = decodeFileSegmentsDataModel(segResp.data);
  final expectedBlock = Uint8List.fromList(segBytes.sublist(12345, 12345 + 4096));
  if (!_eq(segMsg.blockData, expectedBlock)) {
    throw StateError("GET_FILE_SEGMENTS block mismatch");
  }

  final info1 = await _infoFile(session, storeId, "demo/hello.txt");
  if (info1.length != helloBytes.length) {
    throw StateError("INFO_FILE length mismatch");
  }
  await _checkFile(session, storeId, "demo/hello.txt", helloBytes.length, md5.convert(helloBytes).toString());

  await _renameFile(session, storeId, "demo/hello.txt", "demo/hello2.txt");
  final gotHello2 = await _getFile(session, storeId, "demo/hello2.txt");
  if (!_eq(gotHello2, helloBytes)) {
    throw StateError("GET_FILE after rename mismatch");
  }

  final list = await _listDir(session, storeId, "demo", page: 1, pageSize: 100);
  if (list.items.isEmpty) {
    throw StateError("list empty");
  }

  await session.close();
}

Future<void> _putFile(P2PSession session, int storeId, String path, Uint8List bytes, {required bool force}) async {
  final req = FileDataModel(storeId: storeId, length: bytes.length, data: bytes, path: path, md5: "", blockSize: 0);
  final cmd = force ? P2PCommand.forcePutFile : P2PCommand.putFile;
  final resp = await session.requestAny(command: cmd, data: encodeFileDataModel(req));
  _expectOkOrThrow(resp);
}

Future<Uint8List> _getFile(P2PSession session, int storeId, String path) async {
  final req = FileDataModel(storeId: storeId, length: 0, data: Uint8List(0), path: path, md5: "", blockSize: 0);
  final resp = await session.request(command: P2PCommand.getFile, data: encodeFileDataModel(req), expectedCommand: P2PCommand.okGetFile);
  final m = decodeFileDataModel(resp.data);
  return m.data;
}

Future<FileDataModel> _infoFile(P2PSession session, int storeId, String path) async {
  final req = FileDataModel(storeId: storeId, length: 0, data: Uint8List(0), path: path, md5: "", blockSize: 0);
  final resp = await session.request(command: P2PCommand.infoFile, data: encodeFileDataModel(req), expectedCommand: P2PCommand.stdOk);
  return decodeFileDataModel(resp.data);
}

Future<void> _checkFile(P2PSession session, int storeId, String path, int length, String md5Hex) async {
  final req = FileDataModel(storeId: storeId, length: length, data: Uint8List(0), path: path, md5: md5Hex, blockSize: 0);
  final resp = await session.requestAny(command: P2PCommand.checkFile, data: encodeFileDataModel(req));
  _expectOkOrThrow(resp);
}

Future<void> _renameFile(P2PSession session, int storeId, String src, String dst) async {
  final req = FileRenameRequest(storeId: storeId, srcPath: src, dstPath: dst);
  final resp = await session.requestAny(command: P2PCommand.fileRename, data: encodeFileRenameRequest(req));
  _expectOkOrThrow(resp);
}

Future<FileListResponse> _listDir(P2PSession session, int storeId, String path, {required int page, required int pageSize}) async {
  final req = FileListRequest(storeId: storeId, path: path, page: page, pageSize: pageSize);
  final resp = await session.request(command: P2PCommand.fileList, data: encodeFileListRequest(req), expectedCommand: P2PCommand.stdOk);
  return decodeFileListResponse(resp.data);
}

void _expectOkOrThrow(P2PWrapper w) {
  if (w.command == P2PCommand.stdOk) return;
  if (w.command == P2PCommand.invalidData || w.command == P2PCommand.stdError) {
    final e = decodeStdError(w.data);
    throw StateError("${w.command}:${e.message}");
  }
  throw StateError("unexpected command=${w.command}");
}

Uint8List _genBytes(int n) {
  final out = Uint8List(n);
  for (var i = 0; i < n; i++) {
    out[i] = (i * 131 + 7) & 0xFF;
  }
  return out;
}

bool _eq(Uint8List a, Uint8List b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}
