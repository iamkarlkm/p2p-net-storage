#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_keyfile {
  uint8_t* data;
  size_t len;
} p2pws_keyfile_t;

int p2pws_read_file_all(const char* path, p2pws_buf_t* out);
int p2pws_keyfile_load(const char* path, p2pws_keyfile_t* out);
void p2pws_keyfile_free(p2pws_keyfile_t* k);

int p2pws_sha256_hex(const uint8_t* data, size_t len, char* out_hex65);
int p2pws_sha256_bytes(const uint8_t* data, size_t len, uint8_t out32[32]);

typedef struct p2pws_rsa {
  void* prov;
  void* key;
  uint8_t* pub_spki_der;
  size_t pub_spki_der_len;
  uint8_t node_key32[32];
} p2pws_rsa_t;

int p2pws_rsa_load_private_pem(const char* path, p2pws_rsa_t* out);
int p2pws_rsa_set_public_spki_der_base64(p2pws_rsa_t* r, const char* base64);
void p2pws_rsa_free(p2pws_rsa_t* r);

int p2pws_rsa_oaep_sha256_decrypt(p2pws_rsa_t* r, const uint8_t* cipher, size_t cipher_len, p2pws_buf_t* out_plain);
int p2pws_rsa_oaep_sha256_encrypt_spki_der(const uint8_t* pub_spki_der, size_t pub_spki_len, const uint8_t* plain, size_t plain_len, p2pws_buf_t* out_cipher);
int p2pws_rsa_sign_sha256(p2pws_rsa_t* r, const uint8_t* msg, size_t msg_len, p2pws_buf_t* out_sig);

#ifdef __cplusplus
}
#endif
