import "dart:typed_data";

Uint8List xorNoWrap(Uint8List data, Uint8List key, int keyOffset) {
  if (keyOffset < 0) {
    throw RangeError("keyOffset must be >= 0");
  }
  if (keyOffset + data.length > key.length) {
    throw RangeError("key slice out of range");
  }
  final out = Uint8List(data.length);
  for (var i = 0; i < data.length; i++) {
    out[i] = data[i] ^ key[keyOffset + i];
  }
  return out;
}

Uint8List xorRepeat(Uint8List data, Uint8List key) {
  if (key.isEmpty) {
    throw ArgumentError("key must not be empty");
  }
  final out = Uint8List(data.length);
  for (var i = 0; i < data.length; i++) {
    out[i] = data[i] ^ key[i % key.length];
  }
  return out;
}

void xorRepeatInPlace(Uint8List data, Uint8List key) {
  if (key.isEmpty) {
    throw ArgumentError("key must not be empty");
  }
  for (var i = 0; i < data.length; i++) {
    data[i] = data[i] ^ key[i % key.length];
  }
}
