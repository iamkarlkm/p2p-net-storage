import "dart:typed_data";

import "../proto_lite.dart";

class P2PWrapper {
  final int seq;
  final int command;
  final Uint8List data;
  final Map<String, String> headers;

  const P2PWrapper({
    required this.seq,
    required this.command,
    required this.data,
    this.headers = const {},
  });
}

Uint8List encodeWrapper(P2PWrapper w) {
  final pw = ProtoWriter();
  pw.writeInt32(1, w.seq);
  pw.writeInt32(2, w.command);
  pw.writeBytesField(3, w.data);
  if (w.headers.isNotEmpty) {
    for (final e in w.headers.entries) {
      final entry = ProtoWriter();
      entry.writeString(1, e.key);
      entry.writeString(2, e.value);
      pw.writeEmbedded(4, entry.takeBytes());
    }
  }
  return pw.takeBytes();
}

P2PWrapper decodeWrapper(Uint8List bytes) {
  final pr = ProtoReader(bytes);
  var seq = 0;
  var command = 0;
  Uint8List data = Uint8List(0);
  final headers = <String, String>{};
  while (!pr.isEOF) {
    final tag = pr.readTag();
    switch (tag.fieldNumber) {
      case 1:
        if (tag.wireType != 0) {
          pr.skipField(tag.wireType);
          break;
        }
        seq = pr.readVarint().toSigned(32);
        break;
      case 2:
        if (tag.wireType != 0) {
          pr.skipField(tag.wireType);
          break;
        }
        command = pr.readVarint().toSigned(32);
        break;
      case 3:
        if (tag.wireType != 2) {
          pr.skipField(tag.wireType);
          break;
        }
        data = Uint8List.fromList(pr.readBytes());
        break;
      case 4:
        if (tag.wireType != 2) {
          pr.skipField(tag.wireType);
          break;
        }
        final entryBytes = pr.readBytes();
        final er = ProtoReader(entryBytes);
        String? k;
        String? v;
        while (!er.isEOF) {
          final et = er.readTag();
          switch (et.fieldNumber) {
            case 1:
              if (et.wireType != 2) {
                er.skipField(et.wireType);
                break;
              }
              k = er.readString();
              break;
            case 2:
              if (et.wireType != 2) {
                er.skipField(et.wireType);
                break;
              }
              v = er.readString();
              break;
            default:
              er.skipField(et.wireType);
              break;
          }
        }
        if (k != null && v != null) {
          headers[k] = v;
        }
        break;
      default:
        pr.skipField(tag.wireType);
        break;
    }
  }
  return P2PWrapper(seq: seq, command: command, data: data, headers: headers);
}

