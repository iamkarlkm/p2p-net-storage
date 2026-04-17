import "dart:typed_data";

import "package:crypto/crypto.dart";

import "../proto_lite.dart";

class FileDataModel {
  final int storeId;
  final int length;
  final Uint8List data;
  final String path;
  final String md5;
  final int blockSize;

  const FileDataModel({
    required this.storeId,
    required this.length,
    required this.data,
    required this.path,
    required this.md5,
    required this.blockSize,
  });
}

Uint8List encodeFileDataModel(FileDataModel m) {
  final w = ProtoWriter();
  w.writeUint32(1, m.storeId);
  w.writeUint64(2, m.length);
  w.writeBytesField(3, m.data);
  w.writeString(4, m.path);
  w.writeString(5, m.md5);
  w.writeUint32(6, m.blockSize);
  return w.takeBytes();
}

FileDataModel decodeFileDataModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var storeId = 0;
  var length = 0;
  Uint8List data = Uint8List(0);
  var path = "";
  var md5 = "";
  var blockSize = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        storeId = r.readVarint();
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        length = r.readVarint64();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        data = Uint8List.fromList(r.readBytes());
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        path = r.readString();
        break;
      case 5:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        md5 = r.readString();
        break;
      case 6:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        blockSize = r.readVarint();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileDataModel(storeId: storeId, length: length, data: data, path: path, md5: md5, blockSize: blockSize);
}

class FileSegmentsDataModel {
  final int storeId;
  final int length;
  final int start;
  final int blockIndex;
  final int blockSize;
  final Uint8List blockData;
  final String blockMd5;
  final String path;
  final String md5;

  const FileSegmentsDataModel({
    required this.storeId,
    required this.length,
    required this.start,
    required this.blockIndex,
    required this.blockSize,
    required this.blockData,
    required this.blockMd5,
    required this.path,
    required this.md5,
  });
}

Uint8List encodeFileSegmentsDataModel(FileSegmentsDataModel m) {
  final w = ProtoWriter();
  w.writeUint32(1, m.storeId);
  w.writeUint64(2, m.length);
  w.writeUint64(3, m.start);
  w.writeUint32(4, m.blockIndex);
  w.writeUint32(5, m.blockSize);
  w.writeBytesField(6, m.blockData);
  w.writeString(7, m.blockMd5);
  w.writeString(8, m.path);
  w.writeString(9, m.md5);
  return w.takeBytes();
}

FileSegmentsDataModel decodeFileSegmentsDataModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var storeId = 0;
  var length = 0;
  var start = 0;
  var blockIndex = 0;
  var blockSize = 0;
  Uint8List blockData = Uint8List(0);
  var blockMd5 = "";
  var path = "";
  var md5 = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        storeId = r.readVarint();
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        length = r.readVarint64();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        start = r.readVarint64();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        blockIndex = r.readVarint();
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        blockSize = r.readVarint();
        break;
      case 6:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        blockData = Uint8List.fromList(r.readBytes());
        break;
      case 7:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        blockMd5 = r.readString();
        break;
      case 8:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        path = r.readString();
        break;
      case 9:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        md5 = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileSegmentsDataModel(
    storeId: storeId,
    length: length,
    start: start,
    blockIndex: blockIndex,
    blockSize: blockSize,
    blockData: blockData,
    blockMd5: blockMd5,
    path: path,
    md5: md5,
  );
}

class FilesCommandModel {
  final int storeId;
  final String command;
  final List<String> params;
  final Uint8List data;

  const FilesCommandModel({required this.storeId, required this.command, required this.params, required this.data});
}

Uint8List encodeFilesCommandModel(FilesCommandModel m) {
  final w = ProtoWriter();
  w.writeUint32(1, m.storeId);
  w.writeString(2, m.command);
  for (final p in m.params) {
    w.writeString(3, p);
  }
  w.writeBytesField(4, m.data);
  return w.takeBytes();
}

FilesCommandModel decodeFilesCommandModel(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var storeId = 0;
  var command = "";
  final params = <String>[];
  Uint8List data = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        storeId = r.readVarint();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        command = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        params.add(r.readString());
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        data = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FilesCommandModel(storeId: storeId, command: command, params: params, data: data);
}

class StringList {
  final List<String> items;

  const StringList(this.items);
}

Uint8List encodeStringList(StringList l) {
  final w = ProtoWriter();
  for (final s in l.items) {
    w.writeString(1, s);
  }
  return w.takeBytes();
}

StringList decodeStringList(Uint8List bytes) {
  final r = ProtoReader(bytes);
  final items = <String>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(r.readString());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return StringList(items);
}

class FileRenameRequest {
  final int storeId;
  final String srcPath;
  final String dstPath;

  const FileRenameRequest({required this.storeId, required this.srcPath, required this.dstPath});
}

Uint8List encodeFileRenameRequest(FileRenameRequest r) {
  final w = ProtoWriter();
  w.writeUint32(1, r.storeId);
  w.writeString(2, r.srcPath);
  w.writeString(3, r.dstPath);
  return w.takeBytes();
}

FileRenameRequest decodeFileRenameRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var storeId = 0;
  var srcPath = "";
  var dstPath = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        storeId = r.readVarint();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        srcPath = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        dstPath = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileRenameRequest(storeId: storeId, srcPath: srcPath, dstPath: dstPath);
}

class FileListRequest {
  final int storeId;
  final String path;
  final int page;
  final int pageSize;

  const FileListRequest({required this.storeId, required this.path, required this.page, required this.pageSize});
}

Uint8List encodeFileListRequest(FileListRequest r) {
  final w = ProtoWriter();
  w.writeUint32(1, r.storeId);
  w.writeString(2, r.path);
  w.writeUint32(3, r.page);
  w.writeUint32(4, r.pageSize);
  return w.takeBytes();
}

FileListRequest decodeFileListRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var storeId = 0;
  var path = "";
  var page = 0;
  var pageSize = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        storeId = r.readVarint();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        path = r.readString();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        page = r.readVarint();
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        pageSize = r.readVarint();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileListRequest(storeId: storeId, path: path, page: page, pageSize: pageSize);
}

class FileListEntry {
  final String name;
  final String path;
  final bool isDir;
  final int size;
  final int modifiedMs;

  const FileListEntry({required this.name, required this.path, required this.isDir, required this.size, required this.modifiedMs});
}

Uint8List encodeFileListEntry(FileListEntry e) {
  final w = ProtoWriter();
  w.writeString(1, e.name);
  w.writeString(2, e.path);
  w.writeBool(3, e.isDir);
  w.writeUint64(4, e.size);
  w.writeUint64(5, e.modifiedMs);
  return w.takeBytes();
}

FileListEntry decodeFileListEntry(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var name = "";
  var path = "";
  var isDir = false;
  var size = 0;
  var modifiedMs = 0;
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        name = r.readString();
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        path = r.readString();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        isDir = r.readVarint() != 0;
        break;
      case 4:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        size = r.readVarint64();
        break;
      case 5:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        modifiedMs = r.readVarint64();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileListEntry(name: name, path: path, isDir: isDir, size: size, modifiedMs: modifiedMs);
}

class FileListResponse {
  final int page;
  final int pageSize;
  final int total;
  final List<FileListEntry> items;

  const FileListResponse({required this.page, required this.pageSize, required this.total, required this.items});
}

Uint8List encodeFileListResponse(FileListResponse r) {
  final w = ProtoWriter();
  w.writeUint32(1, r.page);
  w.writeUint32(2, r.pageSize);
  w.writeUint32(3, r.total);
  for (final it in r.items) {
    w.writeBytesField(4, encodeFileListEntry(it));
  }
  return w.takeBytes();
}

FileListResponse decodeFileListResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var page = 0;
  var pageSize = 0;
  var total = 0;
  final items = <FileListEntry>[];
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        page = r.readVarint();
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        pageSize = r.readVarint();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        total = r.readVarint();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        items.add(decodeFileListEntry(r.readBytes()));
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileListResponse(page: page, pageSize: pageSize, total: total, items: items);
}

class StdError {
  final String message;

  const StdError(this.message);
}

Uint8List encodeStdError(StdError e) {
  final w = ProtoWriter();
  w.writeString(1, e.message);
  return w.takeBytes();
}

StdError decodeStdError(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var message = "";
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        message = r.readString();
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return StdError(message);
}

String md5Hex(Uint8List bytes) => md5.convert(bytes).toString();

class FilePutRequest {
  final String fileName;
  final int fileSize;
  final Uint8List fileHashSha256;
  final Uint8List content;

  const FilePutRequest({
    required this.fileName,
    required this.fileSize,
    required this.fileHashSha256,
    required this.content,
  });
}

Uint8List encodeFilePutRequest(FilePutRequest r) {
  final w = ProtoWriter();
  w.writeString(1, r.fileName);
  w.writeUint64(2, r.fileSize);
  w.writeBytesField(3, r.fileHashSha256);
  w.writeBytesField(4, r.content);
  return w.takeBytes();
}

FilePutRequest decodeFilePutRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var fileName = "";
  var fileSize = 0;
  Uint8List fileHashSha256 = Uint8List(0);
  Uint8List content = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileName = r.readString();
        break;
      case 2:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        fileSize = r.readVarint64();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileHashSha256 = Uint8List.fromList(r.readBytes());
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        content = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FilePutRequest(fileName: fileName, fileSize: fileSize, fileHashSha256: fileHashSha256, content: content);
}

class FilePutResponse {
  final bool success;
  final String errorMessage;
  final Uint8List fileHashSha256;

  const FilePutResponse({required this.success, required this.errorMessage, required this.fileHashSha256});
}

Uint8List encodeFilePutResponse(FilePutResponse r) {
  final w = ProtoWriter();
  w.writeBool(1, r.success);
  w.writeString(2, r.errorMessage);
  w.writeBytesField(3, r.fileHashSha256);
  return w.takeBytes();
}

FilePutResponse decodeFilePutResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var success = false;
  var errorMessage = "";
  Uint8List fileHashSha256 = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        success = r.readVarint() != 0;
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        errorMessage = r.readString();
        break;
      case 3:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileHashSha256 = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FilePutResponse(success: success, errorMessage: errorMessage, fileHashSha256: fileHashSha256);
}

class FileGetRequest {
  final Uint8List fileHashSha256;

  const FileGetRequest({required this.fileHashSha256});
}

Uint8List encodeFileGetRequest(FileGetRequest r) {
  final w = ProtoWriter();
  w.writeBytesField(1, r.fileHashSha256);
  return w.takeBytes();
}

FileGetRequest decodeFileGetRequest(Uint8List bytes) {
  final r = ProtoReader(bytes);
  Uint8List fileHashSha256 = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileHashSha256 = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileGetRequest(fileHashSha256: fileHashSha256);
}

class FileGetResponse {
  final bool found;
  final String fileName;
  final int fileSize;
  final Uint8List content;

  const FileGetResponse({required this.found, required this.fileName, required this.fileSize, required this.content});
}

Uint8List encodeFileGetResponse(FileGetResponse r) {
  final w = ProtoWriter();
  w.writeBool(1, r.found);
  w.writeString(2, r.fileName);
  w.writeUint64(3, r.fileSize);
  w.writeBytesField(4, r.content);
  return w.takeBytes();
}

FileGetResponse decodeFileGetResponse(Uint8List bytes) {
  final r = ProtoReader(bytes);
  var found = false;
  var fileName = "";
  var fileSize = 0;
  Uint8List content = Uint8List(0);
  while (!r.isEOF) {
    final t = r.readTag();
    switch (t.fieldNumber) {
      case 1:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        found = r.readVarint() != 0;
        break;
      case 2:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        fileName = r.readString();
        break;
      case 3:
        if (t.wireType != 0) {
          r.skipField(t.wireType);
          break;
        }
        fileSize = r.readVarint64();
        break;
      case 4:
        if (t.wireType != 2) {
          r.skipField(t.wireType);
          break;
        }
        content = Uint8List.fromList(r.readBytes());
        break;
      default:
        r.skipField(t.wireType);
        break;
    }
  }
  return FileGetResponse(found: found, fileName: fileName, fileSize: fileSize, content: content);
}
