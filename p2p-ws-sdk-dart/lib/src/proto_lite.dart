import "dart:typed_data";
import "dart:convert";

class ProtoWriter {
  final _b = BytesBuilder(copy: false);

  Uint8List takeBytes() => _b.takeBytes();

  void writeTag(int fieldNumber, int wireType) {
    writeVarint((fieldNumber << 3) | (wireType & 0x7));
  }

  void writeVarint(int value) {
    var v = value;
    while (true) {
      if ((v & ~0x7F) == 0) {
        _b.addByte(v);
        return;
      }
      _b.addByte((v & 0x7F) | 0x80);
      v = v >>> 7;
    }
  }

  void writeVarint64(int value) {
    var v = value;
    while (true) {
      if ((v & ~0x7F) == 0) {
        _b.addByte(v);
        return;
      }
      _b.addByte((v & 0x7F) | 0x80);
      v = v >>> 7;
    }
  }

  void writeInt32(int fieldNumber, int value) {
    writeTag(fieldNumber, 0);
    if (value < 0) {
      writeVarint64(value & 0xFFFFFFFFFFFFFFFF);
    } else {
      writeVarint(value);
    }
  }

  void writeUint32(int fieldNumber, int value) {
    writeTag(fieldNumber, 0);
    writeVarint(value);
  }

  void writeUint64(int fieldNumber, int value) {
    writeTag(fieldNumber, 0);
    writeVarint64(value);
  }

  void writeBool(int fieldNumber, bool value) {
    writeTag(fieldNumber, 0);
    writeVarint(value ? 1 : 0);
  }

  void writeFixed64(int fieldNumber, int value) {
    writeTag(fieldNumber, 1);
    final bd = ByteData(8)..setUint64(0, value, Endian.little);
    _b.add(bd.buffer.asUint8List());
  }

  void writeBytesField(int fieldNumber, Uint8List value) {
    writeTag(fieldNumber, 2);
    writeVarint(value.length);
    _b.add(value);
  }

  void writeString(int fieldNumber, String value) {
    final bytes = Uint8List.fromList(utf8.encode(value));
    writeBytesField(fieldNumber, bytes);
  }

  void writeEmbedded(int fieldNumber, Uint8List messageBytes) {
    writeTag(fieldNumber, 2);
    writeVarint(messageBytes.length);
    _b.add(messageBytes);
  }
}

class ProtoReader {
  final Uint8List _buf;
  int _pos = 0;

  ProtoReader(this._buf);

  bool get isEOF => _pos >= _buf.length;

  int readVarint() {
    var shift = 0;
    var result = 0;
    while (true) {
      if (_pos >= _buf.length) {
        throw FormatException("truncated varint");
      }
      final b = _buf[_pos++];
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
      if (shift > 70) {
        throw FormatException("varint too long");
      }
    }
  }

  int readVarint64() => readVarint();

  int readFixed64() {
    if (_pos + 8 > _buf.length) {
      throw FormatException("truncated fixed64");
    }
    final bd = ByteData.sublistView(_buf, _pos, _pos + 8);
    _pos += 8;
    final v = bd.getUint64(0, Endian.little);
    return v;
  }

  Uint8List readBytes() {
    final len = readVarint();
    if (len < 0 || _pos + len > _buf.length) {
      throw FormatException("truncated bytes");
    }
    final out = Uint8List.sublistView(_buf, _pos, _pos + len);
    _pos += len;
    return out;
  }

  String readString() {
    final b = readBytes();
    return utf8.decode(b);
  }

  ({int fieldNumber, int wireType}) readTag() {
    final t = readVarint();
    return (fieldNumber: t >>> 3, wireType: t & 0x7);
  }

  void skipField(int wireType) {
    switch (wireType) {
      case 0:
        readVarint();
        return;
      case 1:
        if (_pos + 8 > _buf.length) throw FormatException("truncated fixed64");
        _pos += 8;
        return;
      case 2:
        final len = readVarint();
        if (len < 0 || _pos + len > _buf.length) throw FormatException("truncated len");
        _pos += len;
        return;
      case 5:
        if (_pos + 4 > _buf.length) throw FormatException("truncated fixed32");
        _pos += 4;
        return;
      default:
        throw FormatException("unsupported wireType=$wireType");
    }
  }
}
