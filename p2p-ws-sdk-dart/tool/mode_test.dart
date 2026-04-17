import "dart:io";

Future<void> main() async {
  final f = File("tool/tmp_test.bin");
  await f.parent.create(recursive: true);

  for (final mode in [
    FileMode.write,
    FileMode.writeOnly,
    FileMode.append,
    FileMode.writeOnlyAppend,
  ]) {
    await f.writeAsBytes([1, 2, 3, 4, 5]);
    final raf = await f.open(mode: mode);
    await raf.setPosition(2);
    await raf.writeFrom([9, 9]);
    await raf.close();
    stdout.writeln("$mode len=${f.lengthSync()} bytes=${f.readAsBytesSync()}");
  }
}

