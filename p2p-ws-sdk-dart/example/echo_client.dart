import "dart:convert";
import "dart:io";
import "dart:math";
import "dart:typed_data";

import "package:basic_utils/basic_utils.dart";
import "package:convert/convert.dart";
import "package:yaml/yaml.dart";
import "package:pointycastle/key_generators/api.dart";
import "package:pointycastle/key_generators/rsa_key_generator.dart";
import "package:pointycastle/random/fortuna_random.dart";

import "../lib/p2p_ws_sdk.dart";

Future<void> main(List<String> args) async {
  final cfgPath = args.isNotEmpty
      ? args[0]
      : "${Directory.current.path}/../p2p-ws-protocol/examples/client.yaml";
  final cfgFile = File(cfgPath);
  final cfgDir = cfgFile.parent;
  final cfg = loadYaml(await cfgFile.readAsString()) as YamlMap;

  final url = (cfg["ws_url"] as String?) ?? "";
  if (url.isEmpty) {
    throw StateError("ws_url is required");
  }
  final userId = (cfg["user_id"] as String?) ?? "";
  if (userId.isEmpty) {
    throw StateError("user_id is required");
  }

  final keyfilePathRaw = (cfg["keyfile_path"] as String?) ?? "";
  if (keyfilePathRaw.isEmpty) {
    throw StateError("keyfile_path is required");
  }
  final keyfilePath = _resolvePath(cfgDir.path, keyfilePathRaw);

  final expectedKeyIdHex = ((cfg["key_id_sha256_hex"] as String?) ?? "").trim().toLowerCase();

  final magic = _parseInt(cfg["magic"], 0x1234);
  final version = _parseInt(cfg["version"], 1);
  final flagsPlain = _parseInt(cfg["flags_plain"], 4);
  final flagsEncrypted = _parseInt(cfg["flags_encrypted"], 5);
  final maxFramePayload = _parseInt(cfg["max_frame_payload"], 4 * 1024 * 1024);

  final keyf = await KeyFileReader.open(keyfilePath);
  if (expectedKeyIdHex.isNotEmpty && hex.encode(keyf.keyId) != expectedKeyIdHex) {
    throw StateError("key_id_sha256_hex mismatch");
  }

  final pemPathRaw = ((cfg["rsa_private_key_pem_path"] as String?) ?? "").trim();
  RSAPrivateKey privateKey;
  Uint8List clientPubDer;
  if (pemPathRaw.isNotEmpty) {
    final pem = await File(_resolvePath(cfgDir.path, pemPathRaw)).readAsString();
    privateKey = CryptoUtils.rsaPrivateKeyFromPem(pem);
    clientPubDer = spkiDerFromRsaPrivateKeyPem(pem);
  } else {
    final kp = _generateRsaKeyPair();
    privateKey = kp.privateKey;
    clientPubDer = spkiDerFromRsaPublicKey(kp.publicKey);
  }

  final ws = await WebSocket.connect(url);
  int? offset;

  final hand = buildHandWrapper(
    seq: 1,
    clientPubkeySpkiDer: clientPubDer,
    keyIds: [keyf.keyId],
    maxFramePayload: maxFramePayload,
    clientId: userId,
  );
  final handPlain = encodeWrapper(hand);
  ws.add(encodeFrame(WireHeader(handPlain.length, magic, version, flagsPlain), handPlain));

  await for (final msg in ws) {
    if (msg is! List<int>) continue;
    final f = decodeFrame(Uint8List.fromList(msg));
    final cipherPayload = f.cipherPayload;
    Uint8List plainPayload;
    if (offset == null) {
      plainPayload = Uint8List.fromList(cipherPayload);
    } else {
      final slice = await keyf.readSlice(offset, cipherPayload.length);
      plainPayload = xorNoWrap(Uint8List.fromList(cipherPayload), slice, 0);
    }
    final w = decodeWrapper(plainPayload);

    if (w.command == -10002) {
      final plain = rsaOaepSha256Decrypt(privateKey, w.data);
      final ack = decodeHandAckPlain(plain);
      offset = ack.offset;

      final echo = P2PWrapper(seq: 2, command: 1, data: Uint8List.fromList(utf8.encode("hello")));
      final echoPlain = encodeWrapper(echo);
      final slice = await keyf.readSlice(offset, echoPlain.length);
      final echoCipher = xorNoWrap(echoPlain, slice, 0);
      ws.add(encodeFrame(WireHeader(echoCipher.length, magic, version, flagsEncrypted), echoCipher));
      continue;
    }

    if (w.command == -10010) {
      final cu = decodeCryptUpdate(w.data);
      offset = cu.offset;
      continue;
    }

    if (w.command == 1 && w.seq == 2) {
      stdout.writeln(utf8.decode(w.data));
      await keyf.close();
      await ws.close();
      return;
    }
  }
}

int _parseInt(Object? v, int def) {
  if (v == null) return def;
  if (v is int) return v;
  if (v is num) return v.toInt();
  if (v is String) {
    final s = v.trim();
    if (s.isEmpty) return def;
    return int.parse(s.startsWith("0x") ? s.substring(2) : s, radix: s.startsWith("0x") ? 16 : 10);
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

({RSAPublicKey publicKey, RSAPrivateKey privateKey}) _generateRsaKeyPair() {
  final rnd = FortunaRandom();
  final seed = Uint8List(32);
  final rs = Random.secure();
  for (var i = 0; i < seed.length; i++) {
    seed[i] = rs.nextInt(256);
  }
  rnd.seed(KeyParameter(seed));
  final gen = RSAKeyGenerator();
  gen.init(ParametersWithRandom(RSAKeyGeneratorParameters(BigInt.from(65537), 2048, 64), rnd));
  final pair = gen.generateKeyPair();
  return (publicKey: pair.publicKey as RSAPublicKey, privateKey: pair.privateKey as RSAPrivateKey);
}
