import "dart:typed_data";

class WireHeader {
  final int length;
  final int magic;
  final int version;
  final int flags;

  const WireHeader(this.length, this.magic, this.version, this.flags);
}

class DecodedFrame {
  final WireHeader header;
  final Uint8List cipherPayload;

  const DecodedFrame(this.header, this.cipherPayload);
}

Uint8List encodeFrame(WireHeader h, Uint8List payload) {
  final out = Uint8List(8 + payload.length);
  final bd = ByteData.sublistView(out);
  bd.setUint32(0, h.length, Endian.big);
  bd.setUint16(4, h.magic & 0xFFFF, Endian.big);
  bd.setUint8(6, h.version & 0xFF);
  bd.setUint8(7, h.flags & 0xFF);
  out.setRange(8, out.length, payload);
  return out;
}

DecodedFrame decodeFrame(Uint8List frame) {
  if (frame.length < 8) {
    throw FormatException("frame too short");
  }
  final bd = ByteData.sublistView(frame);
  final length = bd.getUint32(0, Endian.big);
  final magic = bd.getUint16(4, Endian.big);
  final version = bd.getUint8(6);
  final flags = bd.getUint8(7);
  final payload = Uint8List.sublistView(frame, 8);
  if ((flags & 0x04) != 0 && length != payload.length) {
    throw FormatException("length check failed");
  }
  return DecodedFrame(WireHeader(length, magic, version, flags), payload);
}
