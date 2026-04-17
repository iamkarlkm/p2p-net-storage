import "dart:typed_data";
import "dart:math";

import "package:asn1lib/asn1lib.dart";
import "package:basic_utils/basic_utils.dart";
import "package:crypto/crypto.dart";

import "messages/control.dart";
import "messages/wrapper.dart";

Uint8List rsaOaepSha256Encrypt(RSAPublicKey publicKey, Uint8List message) {
  final n = publicKey.modulus!;
  final e = publicKey.exponent!;
  final k = (n.bitLength + 7) ~/ 8;
  const hLen = 32;
  if (message.length > k - 2 * hLen - 2) {
    throw StateError("message too long");
  }
  final lHash = Uint8List.fromList(sha256.convert(const <int>[]).bytes);
  final psLen = k - message.length - 2 * hLen - 2;
  final db = BytesBuilder(copy: false)
    ..add(lHash)
    ..add(Uint8List(psLen))
    ..add([1])
    ..add(message);
  final dbBytes = db.takeBytes();

  final seed = Uint8List(hLen);
  final rs = _secureRandomBytes(hLen);
  seed.setAll(0, rs);
  final dbMask = _mgf1Sha256(seed, k - hLen - 1);
  final maskedDb = _xor(dbBytes, dbMask);
  final seedMask = _mgf1Sha256(maskedDb, hLen);
  final maskedSeed = _xor(seed, seedMask);
  final em = BytesBuilder(copy: false)
    ..add([0])
    ..add(maskedSeed)
    ..add(maskedDb);
  final emBytes = em.takeBytes();

  final m = _os2ip(emBytes);
  final c = m.modPow(e, n);
  return _i2osp(c, k);
}

Uint8List rsaOaepSha256Decrypt(RSAPrivateKey privateKey, Uint8List cipher) {
  final n = privateKey.modulus!;
  final d = privateKey.privateExponent!;
  final k = (n.bitLength + 7) ~/ 8;
  final c = _os2ip(_leftPad(cipher, k));
  final m = c.modPow(d, n);
  final em = _i2osp(m, k);

  const hLen = 32;
  if (k < 2 * hLen + 2) {
    throw StateError("invalid key size");
  }
  if (em[0] != 0) {
    throw StateError("oaep decoding error");
  }
  final maskedSeed = Uint8List.sublistView(em, 1, 1 + hLen);
  final maskedDb = Uint8List.sublistView(em, 1 + hLen);
  final seedMask = _mgf1Sha256(maskedDb, hLen);
  final seed = _xor(maskedSeed, seedMask);
  final dbMask = _mgf1Sha256(seed, k - hLen - 1);
  final db = _xor(maskedDb, dbMask);

  final lHash = Uint8List.fromList(sha256.convert(const <int>[]).bytes);
  for (var i = 0; i < hLen; i++) {
    if (db[i] != lHash[i]) {
      throw StateError("oaep decoding error");
    }
  }
  var idx = hLen;
  while (idx < db.length) {
    final b = db[idx];
    if (b == 0) {
      idx++;
      continue;
    }
    if (b == 1) {
      idx++;
      break;
    }
    throw StateError("oaep decoding error");
  }
  if (idx > db.length) {
    throw StateError("oaep decoding error");
  }
  return Uint8List.fromList(db.sublist(idx));
}

RSAPublicKey rsaPublicKeyFromSpkiDer(Uint8List spkiDer) {
  final p = ASN1Parser(spkiDer);
  final top = p.nextObject() as ASN1Sequence;
  final els = top.elements;
  if (els.length < 2) {
    throw FormatException("invalid spki");
  }
  final bit = els[1] as ASN1BitString;
  final vb = bit.valueBytes();
  if (vb.isEmpty || vb[0] != 0) {
    throw FormatException("invalid spki bit string");
  }
  final pubBytes = Uint8List.fromList(vb.sublist(1));
  final p2 = ASN1Parser(pubBytes);
  final pubSeq = p2.nextObject() as ASN1Sequence;
  final pubEls = pubSeq.elements;
  if (pubEls.length < 2) {
    throw FormatException("invalid rsa public key");
  }
  final n = (pubEls[0] as ASN1Integer).valueAsBigInteger;
  final e = (pubEls[1] as ASN1Integer).valueAsBigInteger;
  return RSAPublicKey(n, e);
}

Uint8List rsaOaepSha256DecryptPem(String privateKeyPem, Uint8List cipher) {
  final priv = CryptoUtils.rsaPrivateKeyFromPem(privateKeyPem);
  return rsaOaepSha256Decrypt(priv, cipher);
}

Uint8List spkiDerFromRsaPrivateKeyPem(String privateKeyPem) {
  final priv = CryptoUtils.rsaPrivateKeyFromPem(privateKeyPem);
  final e = priv.publicExponent;
  if (e == null) {
    throw StateError("private key missing public exponent");
  }
  final pub = RSAPublicKey(priv.modulus!, e);
  return spkiDerFromRsaPublicKey(pub);
}

Uint8List spkiDerFromRsaPublicKey(RSAPublicKey pub) {
  final rsaPubSeq = ASN1Sequence()
    ..add(ASN1Integer(pub.modulus!))
    ..add(ASN1Integer(pub.exponent!));
  final rsaPubDer = rsaPubSeq.encodedBytes;

  final algId = ASN1Sequence()
    ..add(ASN1ObjectIdentifier.fromComponentString("1.2.840.113549.1.1.1"))
    ..add(ASN1Null());

  final spki = ASN1Sequence()
    ..add(algId)
    ..add(ASN1BitString(rsaPubDer));

  return Uint8List.fromList(spki.encodedBytes);
}

P2PWrapper buildHandWrapper({
  required int seq,
  required Uint8List clientPubkeySpkiDer,
  required List<Uint8List> keyIds,
  required int maxFramePayload,
  required String clientId,
}) {
  final hand = Hand(
    clientPubkeySpkiDer: clientPubkeySpkiDer,
    keyIds: keyIds,
    maxFramePayload: maxFramePayload,
    clientId: clientId,
  );
  return P2PWrapper(seq: seq, command: -10001, data: encodeHand(hand));
}

HandAckPlain decryptHandAckPlain({
  required String privateKeyPem,
  required Uint8List encrypted,
}) {
  final plain = rsaOaepSha256DecryptPem(privateKeyPem, encrypted);
  return decodeHandAckPlain(plain);
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

Uint8List _mgf1Sha256(Uint8List seed, int maskLen) {
  final out = BytesBuilder(copy: false);
  var counter = 0;
  while (out.length < maskLen) {
    final c = Uint8List(4);
    c[0] = (counter >>> 24) & 0xFF;
    c[1] = (counter >>> 16) & 0xFF;
    c[2] = (counter >>> 8) & 0xFF;
    c[3] = counter & 0xFF;
    final digest = sha256.convert([...seed, ...c]).bytes;
    out.add(digest);
    counter++;
  }
  final all = out.takeBytes();
  return Uint8List.fromList(all.sublist(0, maskLen));
}

Uint8List _xor(Uint8List a, Uint8List b) {
  if (a.length != b.length) {
    throw ArgumentError("length mismatch");
  }
  final out = Uint8List(a.length);
  for (var i = 0; i < a.length; i++) {
    out[i] = a[i] ^ b[i];
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

Uint8List _secureRandomBytes(int len) {
  final out = Uint8List(len);
  final r = Random.secure();
  for (var i = 0; i < len; i++) {
    out[i] = r.nextInt(256);
  }
  return out;
}
