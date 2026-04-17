import "dart:typed_data";

import "package:pointycastle/asymmetric/api.dart";
import "package:crypto/crypto.dart";

Uint8List rsaSignSha256Pkcs1v15(RSAPrivateKey privateKey, Uint8List message) {
  final n = privateKey.modulus!;
  final d = privateKey.privateExponent!;
  final k = (n.bitLength + 7) ~/ 8;
  final h = Uint8List.fromList(sha256.convert(message).bytes);

  final digestInfo = BytesBuilder(copy: false)
    ..add(const [
      0x30, 0x31,
      0x30, 0x0d,
      0x06, 0x09,
      0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01,
      0x05, 0x00,
      0x04, 0x20,
    ])
    ..add(h);
  final t = digestInfo.takeBytes();

  final psLen = k - t.length - 3;
  if (psLen < 8) {
    throw StateError("invalid key size");
  }
  final em = Uint8List(k);
  em[0] = 0x00;
  em[1] = 0x01;
  for (var i = 0; i < psLen; i++) {
    em[2 + i] = 0xFF;
  }
  em[2 + psLen] = 0x00;
  em.setRange(3 + psLen, em.length, t);

  final m = _os2ip(em);
  final s = m.modPow(d, n);
  return _i2osp(s, k);
}

bool rsaVerifySha256Pkcs1v15(RSAPublicKey publicKey, Uint8List message, Uint8List signature) {
  final n = publicKey.modulus!;
  final e = publicKey.exponent!;
  final k = (n.bitLength + 7) ~/ 8;
  final sig = signature.length == k ? signature : _leftPad(signature, k);
  final s = _os2ip(sig);
  final m = s.modPow(e, n);
  final em = _i2osp(m, k);
  if (em.length < 11) return false;
  if (em[0] != 0x00 || em[1] != 0x01) return false;
  var idx = 2;
  while (idx < em.length) {
    final b = em[idx];
    if (b == 0xFF) {
      idx++;
      continue;
    }
    if (b == 0x00) {
      idx++;
      break;
    }
    return false;
  }
  if (idx >= em.length) return false;

  final h = Uint8List.fromList(sha256.convert(message).bytes);
  final t = BytesBuilder(copy: false)
    ..add(const [
      0x30, 0x31,
      0x30, 0x0d,
      0x06, 0x09,
      0x60, 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01,
      0x05, 0x00,
      0x04, 0x20,
    ])
    ..add(h);
  final expected = t.takeBytes();
  if (em.length - idx != expected.length) return false;
  for (var i = 0; i < expected.length; i++) {
    if (em[idx + i] != expected[i]) return false;
  }
  return true;
}

BigInt _os2ip(Uint8List x) {
  var r = BigInt.zero;
  for (final b in x) {
    r = (r << 8) | BigInt.from(b);
  }
  return r;
}

Uint8List _i2osp(BigInt x, int size) {
  final out = Uint8List(size);
  var v = x;
  for (var i = size - 1; i >= 0; i--) {
    out[i] = (v & BigInt.from(0xFF)).toInt();
    v = v >> 8;
  }
  if (v != BigInt.zero) {
    throw StateError("integer too large");
  }
  return out;
}

Uint8List _leftPad(Uint8List x, int size) {
  if (x.length == size) return x;
  if (x.length > size) {
    return Uint8List.fromList(x.sublist(x.length - size));
  }
  final out = Uint8List(size);
  out.setRange(size - x.length, size, x);
  return out;
}
