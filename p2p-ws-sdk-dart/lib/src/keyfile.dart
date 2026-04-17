import "dart:io";
import "dart:typed_data";

import "package:convert/convert.dart";
import "package:crypto/crypto.dart";

class KeyFileReader {
  final RandomAccessFile _raf;
  final int length;
  final Uint8List keyId;

  KeyFileReader._(this._raf, this.length, this.keyId);

  static Future<KeyFileReader> open(String path) async {
    final f = File(path);
    final raf = await f.open(mode: FileMode.read);
    final len = await raf.length();
    final out = AccumulatorSink<Digest>();
    final d = sha256.startChunkedConversion(out);
    await raf.setPosition(0);
    final buf = Uint8List(1024 * 1024);
    while (true) {
      final n = await raf.readInto(buf);
      if (n <= 0) break;
      d.add(buf.sublist(0, n));
    }
    d.close();
    final keyId = Uint8List.fromList(out.events.single.bytes);
    await raf.setPosition(0);
    return KeyFileReader._(raf, len, keyId);
  }

  Future<Uint8List> readSlice(int offset, int length) async {
    if (offset < 0 || length < 0) {
      throw RangeError("offset/length must be >= 0");
    }
    if (offset + length > this.length) {
      throw RangeError("slice out of range");
    }
    await _raf.setPosition(offset);
    final out = Uint8List(length);
    var pos = 0;
    while (pos < length) {
      final n = await _raf.readInto(out, pos, length);
      if (n <= 0) {
        throw StateError("unexpected EOF");
      }
      pos += n;
    }
    return out;
  }

  Future<void> close() => _raf.close();
}
