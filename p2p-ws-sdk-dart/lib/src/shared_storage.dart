import "dart:io";

class SharedStorageRegistry {
  final Map<int, Directory> _storage = {};

  void registerStorageLocation(int storeId, Directory dir) {
    if (storeId <= 0) {
      throw ArgumentError("storeId must be > 0");
    }
    _storage[storeId] = dir;
  }

  Directory getStorageLocation(int storeId) {
    final d = _storage[storeId];
    if (d == null) {
      throw StateError("storage location not registered: $storeId");
    }
    return d;
  }

  File getSandboxFileForWrite(int storeId, String path) {
    final base = getStorageLocation(storeId);
    final baseAbs = base.absolute.path;
    base.createSync(recursive: true);
    final baseReal = _resolveDirReal(baseAbs);
    final rel = _normalizeRelativePath(path);
    final target = File("$baseAbs${Platform.pathSeparator}$rel");
    final parent = target.parent;
    parent.createSync(recursive: true);
    final parentReal = _resolveDirReal(parent.absolute.path);
    if (!_isUnder(baseReal, parentReal)) {
      throw StateError("path traversal detected");
    }
    if (target.existsSync()) {
      final t = FileSystemEntity.typeSync(target.path, followLinks: false);
      if (t == FileSystemEntityType.link) {
        final real = _resolveFileReal(target.absolute.path);
        if (!_isUnder(baseReal, real)) {
          throw StateError("path traversal detected");
        }
      }
    }
    return target;
  }

  File getSandboxFile(int storeId, String path) {
    final base = getStorageLocation(storeId);
    final baseAbs = base.absolute.path;
    base.createSync(recursive: true);
    final baseReal = _resolveDirReal(baseAbs);
    final rel = _normalizeRelativePath(path);
    final target = File("$baseAbs${Platform.pathSeparator}$rel");
    final realParent = _resolveDirReal(target.parent.absolute.path);
    if (!_isUnder(baseReal, realParent)) {
      throw StateError("path traversal detected");
    }
    final t = FileSystemEntity.typeSync(target.path, followLinks: false);
    if (t == FileSystemEntityType.link) {
      final real = _resolveFileReal(target.absolute.path);
      if (!_isUnder(baseReal, real)) {
        throw StateError("path traversal detected");
      }
    }
    return target;
  }

  File getAndCheckExistsSandboxFile(int storeId, String path) {
    final base = getStorageLocation(storeId);
    final baseAbs = base.absolute.path;
    base.createSync(recursive: true);
    final baseReal = _resolveDirReal(baseAbs);
    final rel = _normalizeRelativePath(path);
    final target = File("$baseAbs${Platform.pathSeparator}$rel");
    if (!target.existsSync()) {
      throw StateError("file not found");
    }
    final real = _resolveFileReal(target.absolute.path);
    if (!_isUnder(baseReal, real)) {
      throw StateError("path traversal detected");
    }
    return target;
  }
}

String _normalizeRelativePath(String path) {
  final p = path.replaceAll("\\", "/").trim();
  if (p.isEmpty) {
    throw ArgumentError("path is empty");
  }
  if (p.startsWith(".") || p.startsWith("/") || RegExp(r"^[A-Za-z]:").hasMatch(p)) {
    throw ArgumentError("illegal path");
  }
  if (p.contains("\u0000")) {
    throw ArgumentError("illegal path");
  }
  final segs = p.split("/");
  final out = <String>[];
  for (final s in segs) {
    if (s.isEmpty || s == ".") continue;
    if (s == "..") {
      throw ArgumentError("illegal path");
    }
    if (s.contains(":")) {
      throw ArgumentError("illegal path");
    }
    out.add(s);
  }
  if (out.isEmpty) {
    throw ArgumentError("illegal path");
  }
  return out.join(Platform.pathSeparator);
}

String _resolveDirReal(String dirAbs) {
  final d = Directory(dirAbs);
  if (!d.existsSync()) {
    d.createSync(recursive: true);
  }
  return d.resolveSymbolicLinksSync();
}

String _resolveFileReal(String fileAbs) {
  return File(fileAbs).resolveSymbolicLinksSync();
}

bool _isUnder(String baseReal, String targetReal) {
  final b = baseReal.endsWith(Platform.pathSeparator) ? baseReal : "$baseReal${Platform.pathSeparator}";
  final t = targetReal.endsWith(Platform.pathSeparator) ? targetReal : "$targetReal${Platform.pathSeparator}";
  if (Platform.isWindows) {
    return t.toLowerCase().startsWith(b.toLowerCase());
  }
  return t.startsWith(b);
}
