import "dart:convert";
import "dart:io";
import "dart:typed_data";

import "package:convert/convert.dart";

import "../lib/src/frame.dart";
import "../lib/src/xor.dart";

Future<void> main() async {
  final here = File.fromUri(Platform.script).parent;
  final sdkRoot = here.parent;
  final vectorsDir = Directory("${sdkRoot.parent.path}/p2p-ws-protocol/test-vectors");

  await _verifyFrameVector(File("${vectorsDir.path}/frame_vector_001.json"));
  await _verifyXorVector(File("${vectorsDir.path}/xor_vector_001.json"));
  stdout.writeln("OK");
}

Future<void> _verifyFrameVector(File f) async {
  final j = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
  final header = j["header"] as Map<String, dynamic>;
  final lengthU32 = (header["length_u32"] as num).toInt();
  final magicU16 = (header["magic_u16"] as num).toInt();
  final versionU8 = (header["version_u8"] as num).toInt();
  final flagsU8 = (header["flags_u8"] as num).toInt();
  final cipherPayload = Uint8List.fromList(hex.decode(j["cipher_payload_hex"] as String));
  final expectedWs = hex.decode(j["ws_binary_payload_hex"] as String);

  final got = encodeFrame(WireHeader(lengthU32, magicU16, versionU8, flagsU8), cipherPayload);
  final gotHex = hex.encode(got);
  final expHex = hex.encode(expectedWs);
  if (gotHex != expHex) {
    throw StateError("frame vector mismatch: got=$gotHex expected=$expHex");
  }

  final decoded = decodeFrame(got);
  if (decoded.header.length != lengthU32) throw StateError("frame decode length mismatch");
  if (decoded.header.magic != magicU16) throw StateError("frame decode magic mismatch");
  if (decoded.header.version != versionU8) throw StateError("frame decode version mismatch");
  if (decoded.header.flags != flagsU8) throw StateError("frame decode flags mismatch");
  if (hex.encode(decoded.cipherPayload) != hex.encode(cipherPayload)) throw StateError("frame decode payload mismatch");
}

Future<void> _verifyXorVector(File f) async {
  final j = jsonDecode(await f.readAsString()) as Map<String, dynamic>;
  final plain = Uint8List.fromList(hex.decode(j["plain_hex"] as String));
  final keyfile = Uint8List.fromList(hex.decode(j["keyfile_bytes_hex"] as String));
  final offset = (j["offset"] as num).toInt();
  final keySlice = Uint8List.sublistView(keyfile, offset, offset + plain.length);
  final expectedCipher = hex.decode(j["cipher_hex"] as String);
  final gotCipher = xorNoWrap(plain, keySlice, 0);
  if (hex.encode(gotCipher) != hex.encode(expectedCipher)) {
    throw StateError("xor vector mismatch");
  }
  final back = xorNoWrap(gotCipher, keySlice, 0);
  if (hex.encode(back) != hex.encode(plain)) {
    throw StateError("xor invert mismatch");
  }
}
