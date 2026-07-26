import "dart:typed_data";

Uint8List encodeCoreFrame({required int magic, required Uint8List payload}) {
  final out = Uint8List(8 + payload.length);
  final bd = ByteData.sublistView(out);
  bd.setInt32(0, payload.length, Endian.big);
  bd.setInt32(4, magic, Endian.big);
  out.setRange(8, out.length, payload);
  return out;
}

({int magic, Uint8List payload}) decodeCoreFrame(Uint8List frameBytes) {
  if (frameBytes.length < 8) {
    throw FormatException("frame too short");
  }
  final bd = ByteData.sublistView(frameBytes);
  final len = bd.getInt32(0, Endian.big);
  final magic = bd.getInt32(4, Endian.big);
  final end = 8 + len;
  if (len < 0 || end > frameBytes.length) {
    throw FormatException("bad length");
  }
  return (magic: magic, payload: Uint8List.sublistView(frameBytes, 8, end));
}

List<({int magic, Uint8List payload})> decodeAllCoreFrames(Uint8List bytes) {
  final out = <({int magic, Uint8List payload})>[];
  var pos = 0;
  while (pos < bytes.length) {
    if (pos + 8 > bytes.length) {
      throw FormatException("frame truncated");
    }
    final bd = ByteData.sublistView(bytes, pos, pos + 8);
    final len = bd.getInt32(0, Endian.big);
    final magic = bd.getInt32(4, Endian.big);
    final end = pos + 8 + len;
    if (len < 0 || end > bytes.length) {
      throw FormatException("bad length");
    }
    out.add((magic: magic, payload: Uint8List.sublistView(bytes, pos + 8, end)));
    pos = end;
  }
  return out;
}
