import "dart:typed_data";

import "package:crypto/crypto.dart";

Uint8List sha256Bytes(Uint8List input) => Uint8List.fromList(sha256.convert(input).bytes);

int nowMs() => DateTime.now().millisecondsSinceEpoch;

