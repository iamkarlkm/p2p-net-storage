#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_cfg {
  char user_id[32];
  char ws_url[512];
  char keyfile_path[512];
  char key_id_sha256_hex[80];
  char rsa_private_key_pem_path[512];
  char pubkey_spki_der_base64[4096];
  char crypto_mode[64];
  char reported_transport[16];
  char reported_addr[128];
  uint32_t listen_port;
  uint32_t renew_seconds;
  uint32_t magic;
  uint32_t version;
  uint32_t flags_plain;
  uint32_t flags_encrypted;
  uint32_t max_frame_payload;
} p2pws_cfg_t;

int p2pws_cfg_load_yaml(const char* path, p2pws_cfg_t* out);

#ifdef __cplusplus
}
#endif
