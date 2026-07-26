#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2p_ws.h"
#include "p2pws_buf.h"
#include "p2pws_crypto.h"
#include "p2pws_messages.h"
#include "p2pws_pb.h"
#include "p2pws_yaml.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_cipher_ctx {
  char crypto_mode[64];
  uint32_t offset;
  uint8_t repeat_key[64];
  size_t repeat_key_len;
} p2pws_cipher_ctx_t;

int p2pws_cipher_prepare_hand(const p2pws_cfg_t* cfg, const p2pws_rsa_t* rsa, const uint8_t* key_id32, p2pws_cipher_ctx_t* out_ctx, p2pws_buf_t* out_hand);

int p2pws_cipher_apply_hand_ack(const p2pws_hand_ack_plain_view_t* hak, p2pws_cipher_ctx_t* ctx);

int p2pws_cipher_apply(const p2pws_cipher_ctx_t* ctx, const p2pws_keyfile_t* kf, const uint8_t* in, size_t in_len, uint8_t* out);

uint8_t p2pws_cipher_wire_flags(const p2pws_cfg_t* cfg, const p2pws_cipher_ctx_t* ctx);

#ifdef __cplusplus
}
#endif

