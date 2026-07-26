import "dart:convert";
import "dart:io";

Map<String, int> loadCommandOrdinalsFromJsonFile(String path) {
  final raw = jsonDecode(File(path).readAsStringSync());
  if (raw is! Map) {
    throw FormatException("invalid ordinals json");
  }
  final names = raw["names"];
  if (names is! List) {
    throw FormatException("invalid ordinals json");
  }
  final out = <String, int>{};
  for (var i = 0; i < names.length; i++) {
    final n = names[i];
    if (n is String) {
      out[n] = i;
    }
  }
  return out;
}

