import "dart:typed_data";
import "dart:math";

import "package:crypto/crypto.dart";

class P2PCryptoMode {
  static const String keyfileXorRsaOaep = "KEYFILE_XOR_RSA_OAEP";
  static const String clientRandomXorRsaOaep = "CLIENT_RANDOM_XOR_RSA_OAEP";
  static const String serverRandomXorRsaOaep = "SERVER_RANDOM_XOR_RSA_OAEP";
  static const String plain = "PLAIN";

  static bool isPlain(String mode) => mode.trim().toUpperCase() == plain;
}

Uint8List sha256Bytes(Uint8List input) => Uint8List.fromList(sha256.convert(input).bytes);

int nowMs() => DateTime.now().millisecondsSinceEpoch;

Uint8List secureRandomBytes(int len) {
  if (len <= 0) return Uint8List(0);
  final out = Uint8List(len);
  final r = Random.secure();
  for (var i = 0; i < len; i++) {
    out[i] = r.nextInt(256);
  }
  return out;
}
