#include "p2pws_crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "p2pws_pb.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <bcrypt.h>
#include <ncrypt.h>
#include <wincrypt.h>

#pragma comment(lib, "bcrypt.lib")
#pragma comment(lib, "ncrypt.lib")
#pragma comment(lib, "crypt32.lib")

static int sha256_bytes(const uint8_t* data, size_t len, uint8_t out32[32]) {
  BCRYPT_ALG_HANDLE hAlg = NULL;
  BCRYPT_HASH_HANDLE hHash = NULL;
  DWORD objLen = 0, cb = 0, hashLen = 0;
  uint8_t* obj = NULL;
  int rc = -1;

  if (BCryptOpenAlgorithmProvider(&hAlg, BCRYPT_SHA256_ALGORITHM, NULL, 0) != 0) goto done;
  if (BCryptGetProperty(hAlg, BCRYPT_OBJECT_LENGTH, (PUCHAR)&objLen, sizeof(objLen), &cb, 0) != 0) goto done;
  if (BCryptGetProperty(hAlg, BCRYPT_HASH_LENGTH, (PUCHAR)&hashLen, sizeof(hashLen), &cb, 0) != 0) goto done;
  if (hashLen != 32) goto done;
  obj = (uint8_t*)malloc(objLen);
  if (!obj) goto done;
  if (BCryptCreateHash(hAlg, &hHash, obj, objLen, NULL, 0, 0) != 0) goto done;
  if (BCryptHashData(hHash, (PUCHAR)data, (ULONG)len, 0) != 0) goto done;
  if (BCryptFinishHash(hHash, out32, 32, 0) != 0) goto done;
  rc = 0;
done:
  if (hHash) BCryptDestroyHash(hHash);
  if (hAlg) BCryptCloseAlgorithmProvider(hAlg, 0);
  free(obj);
  return rc;
}

static void hex_encode(const uint8_t* in, size_t n, char* out) {
  static const char* h = "0123456789abcdef";
  for (size_t i = 0; i < n; i++) {
    out[i * 2] = h[(in[i] >> 4) & 0xF];
    out[i * 2 + 1] = h[in[i] & 0xF];
  }
  out[n * 2] = 0;
}

static int base64_decode(const char* in, uint8_t** out, size_t* out_len) {
  static const int8_t T[256] = {
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-2,-1,-1,
      -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
      -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
      -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1};
  if (!in || !out || !out_len) return -1;
  size_t n = strlen(in);
  uint8_t* buf = (uint8_t*)malloc((n / 4 + 1) * 3);
  if (!buf) return -2;
  size_t o = 0;
  uint32_t acc = 0;
  int bits = 0;
  for (size_t i = 0; i < n; i++) {
    unsigned char c = (unsigned char)in[i];
    if (c == '\r' || c == '\n' || c == ' ' || c == '\t') continue;
    int8_t v = T[c];
    if (v == -1) continue;
    if (v == -2) break;
    acc = (acc << 6) | (uint32_t)v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      buf[o++] = (uint8_t)((acc >> bits) & 0xFF);
    }
  }
  *out = buf;
  *out_len = o;
  return 0;
}

static int pem_extract_pkcs8(const uint8_t* pem, size_t pem_len, uint8_t** out_der, size_t* out_der_len) {
  const char* begin = "-----BEGIN PRIVATE KEY-----";
  const char* end = "-----END PRIVATE KEY-----";
  const char* s = (const char*)pem;
  const char* p1 = strstr(s, begin);
  if (!p1) return -1;
  p1 += strlen(begin);
  const char* p2 = strstr(p1, end);
  if (!p2) return -2;
  size_t b64_len = (size_t)(p2 - p1);
  char* b64 = (char*)malloc(b64_len + 1);
  if (!b64) return -3;
  memcpy(b64, p1, b64_len);
  b64[b64_len] = 0;
  int r = base64_decode(b64, out_der, out_der_len);
  free(b64);
  return r;
}

int p2pws_read_file_all(const char* path, p2pws_buf_t* out) {
  if (!path || !out) return -1;
  FILE* f = fopen(path, "rb");
  if (!f) return -2;
  if (fseek(f, 0, SEEK_END) != 0) {
    fclose(f);
    return -3;
  }
  long sz = ftell(f);
  if (sz < 0) {
    fclose(f);
    return -4;
  }
  if (fseek(f, 0, SEEK_SET) != 0) {
    fclose(f);
    return -5;
  }
  p2pws_pb_reset(out);
  if (p2pws_buf_reserve(out, (size_t)sz + 1) != 0) {
    fclose(f);
    return -6;
  }
  size_t got = fread(out->data, 1, (size_t)sz, f);
  fclose(f);
  if (got != (size_t)sz) return -7;
  out->len = (size_t)sz;
  return 0;
}

int p2pws_keyfile_load(const char* path, p2pws_keyfile_t* out) {
  if (!path || !out) return -1;
  memset(out, 0, sizeof(*out));
  p2pws_buf_t b;
  p2pws_buf_init(&b);
  int r = p2pws_read_file_all(path, &b);
  if (r != 0) {
    p2pws_buf_free(&b);
    return r;
  }
  out->data = b.data;
  out->len = b.len;
  b.data = NULL;
  b.len = 0;
  b.cap = 0;
  return 0;
}

void p2pws_keyfile_free(p2pws_keyfile_t* k) {
  if (!k) return;
  free(k->data);
  k->data = NULL;
  k->len = 0;
}

int p2pws_sha256_hex(const uint8_t* data, size_t len, char* out_hex65) {
  if (!data || !out_hex65) return -1;
  uint8_t md[32];
  if (sha256_bytes(data, len, md) != 0) return -2;
  hex_encode(md, 32, out_hex65);
  return 0;
}

int p2pws_sha256_bytes(const uint8_t* data, size_t len, uint8_t out32[32]) {
  if (!data || !out32) return -1;
  return sha256_bytes(data, len, out32);
}

int p2pws_rsa_load_private_pem(const char* path, p2pws_rsa_t* out) {
  if (!path || !out) return -1;
  memset(out, 0, sizeof(*out));
  p2pws_buf_t pem;
  p2pws_buf_init(&pem);
  if (p2pws_read_file_all(path, &pem) != 0) {
    p2pws_buf_free(&pem);
    return -2;
  }
  uint8_t* der = NULL;
  size_t der_len = 0;
  if (pem_extract_pkcs8(pem.data, pem.len, &der, &der_len) != 0) {
    p2pws_buf_free(&pem);
    return -3;
  }
  p2pws_buf_free(&pem);

  NCRYPT_PROV_HANDLE prov = 0;
  NCRYPT_KEY_HANDLE key = 0;
  if (NCryptOpenStorageProvider(&prov, MS_KEY_STORAGE_PROVIDER, 0) != 0) {
    free(der);
    return -4;
  }
  SECURITY_STATUS s = NCryptImportKey(prov, 0, NCRYPT_PKCS8_PRIVATE_KEY_BLOB, NULL, &key, (PBYTE)der, (DWORD)der_len, 0);
  free(der);
  if (s != 0) {
    NCryptFreeObject(prov);
    return -5;
  }
  out->prov = (void*)prov;
  out->key = (void*)key;
  return 0;
}

int p2pws_rsa_set_public_spki_der_base64(p2pws_rsa_t* r, const char* base64) {
  if (!r || !base64) return -1;
  uint8_t* der = NULL;
  size_t der_len = 0;
  if (base64_decode(base64, &der, &der_len) != 0) return -2;
  free(r->pub_spki_der);
  r->pub_spki_der = der;
  r->pub_spki_der_len = der_len;
  if (sha256_bytes(der, der_len, r->node_key32) != 0) return -3;
  return 0;
}

void p2pws_rsa_free(p2pws_rsa_t* r) {
  if (!r) return;
  if (r->key) NCryptFreeObject((NCRYPT_KEY_HANDLE)r->key);
  if (r->prov) NCryptFreeObject((NCRYPT_PROV_HANDLE)r->prov);
  free(r->pub_spki_der);
  r->prov = NULL;
  r->key = NULL;
  r->pub_spki_der = NULL;
  r->pub_spki_der_len = 0;
  memset(r->node_key32, 0, sizeof(r->node_key32));
}

int p2pws_rsa_oaep_sha256_decrypt(p2pws_rsa_t* r, const uint8_t* cipher, size_t cipher_len, p2pws_buf_t* out_plain) {
  if (!r || !r->key || !cipher || !out_plain) return -1;
  BCRYPT_OAEP_PADDING_INFO pi;
  pi.pszAlgId = BCRYPT_SHA256_ALGORITHM;
  pi.pbLabel = NULL;
  pi.cbLabel = 0;
  DWORD out_len = 0;
  SECURITY_STATUS s = NCryptDecrypt((NCRYPT_KEY_HANDLE)r->key, (PBYTE)cipher, (DWORD)cipher_len, &pi, NULL, 0, &out_len, NCRYPT_PAD_OAEP_FLAG);
  if (s != 0 || out_len == 0) return -2;
  p2pws_pb_reset(out_plain);
  if (p2pws_buf_reserve(out_plain, out_len + 1) != 0) return -3;
  s = NCryptDecrypt((NCRYPT_KEY_HANDLE)r->key, (PBYTE)cipher, (DWORD)cipher_len, &pi, out_plain->data, out_len, &out_len, NCRYPT_PAD_OAEP_FLAG);
  if (s != 0) return -4;
  out_plain->len = out_len;
  return 0;
}

int p2pws_rsa_oaep_sha256_encrypt_spki_der(const uint8_t* pub_spki_der, size_t pub_spki_len, const uint8_t* plain, size_t plain_len, p2pws_buf_t* out_cipher) {
  if (!pub_spki_der || !plain || !out_cipher) return -1;
  CERT_PUBLIC_KEY_INFO* info = NULL;
  DWORD info_len = 0;
  if (!CryptDecodeObjectEx(X509_ASN_ENCODING, X509_PUBLIC_KEY_INFO, pub_spki_der, (DWORD)pub_spki_len, CRYPT_DECODE_ALLOC_FLAG, NULL, &info, &info_len)) {
    return -2;
  }
  BCRYPT_KEY_HANDLE pub = NULL;
  if (!CryptImportPublicKeyInfoEx2(X509_ASN_ENCODING, info, 0, NULL, &pub)) {
    LocalFree(info);
    return -3;
  }
  LocalFree(info);
  BCRYPT_OAEP_PADDING_INFO pi;
  pi.pszAlgId = BCRYPT_SHA256_ALGORITHM;
  pi.pbLabel = NULL;
  pi.cbLabel = 0;
  ULONG out_len = 0;
  NTSTATUS s = BCryptEncrypt(pub, (PUCHAR)plain, (ULONG)plain_len, &pi, NULL, 0, NULL, 0, &out_len, BCRYPT_PAD_OAEP);
  if (s != 0 || out_len == 0) {
    BCryptDestroyKey(pub);
    return -4;
  }
  p2pws_pb_reset(out_cipher);
  if (p2pws_buf_reserve(out_cipher, out_len + 1) != 0) {
    BCryptDestroyKey(pub);
    return -5;
  }
  s = BCryptEncrypt(pub, (PUCHAR)plain, (ULONG)plain_len, &pi, NULL, 0, out_cipher->data, out_len, &out_len, BCRYPT_PAD_OAEP);
  if (s != 0) {
    BCryptDestroyKey(pub);
    return -6;
  }
  out_cipher->len = out_len;
  BCryptDestroyKey(pub);
  return 0;
}

int p2pws_rsa_sign_sha256(p2pws_rsa_t* r, const uint8_t* msg, size_t msg_len, p2pws_buf_t* out_sig) {
  if (!r || !r->key || !msg || !out_sig) return -1;
  uint8_t h32[32];
  if (sha256_bytes(msg, msg_len, h32) != 0) return -2;
  BCRYPT_PKCS1_PADDING_INFO pi;
  pi.pszAlgId = BCRYPT_SHA256_ALGORITHM;
  DWORD sig_len = 0;
  SECURITY_STATUS s = NCryptSignHash((NCRYPT_KEY_HANDLE)r->key, &pi, h32, 32, NULL, 0, &sig_len, NCRYPT_PAD_PKCS1_FLAG);
  if (s != 0 || sig_len == 0) return -3;
  p2pws_pb_reset(out_sig);
  if (p2pws_buf_reserve(out_sig, sig_len + 1) != 0) return -4;
  s = NCryptSignHash((NCRYPT_KEY_HANDLE)r->key, &pi, h32, 32, out_sig->data, sig_len, &sig_len, NCRYPT_PAD_PKCS1_FLAG);
  if (s != 0) return -5;
  out_sig->len = sig_len;
  return 0;
}
