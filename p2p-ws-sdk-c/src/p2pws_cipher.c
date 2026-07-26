#include "p2pws_cipher.h"

#include <string.h>

static int streq(const char* a, const char* b) {
  if (!a || !b) return 0;
  return strcmp(a, b) == 0;
}

static void set_str(char* dst, size_t cap, const char* v) {
  if (!dst || cap == 0) return;
  if (!v) v = "";
  size_t n = strlen(v);
  if (n >= cap) n = cap - 1;
  memcpy(dst, v, n);
  dst[n] = 0;
}

int p2pws_cipher_prepare_hand(const p2pws_cfg_t* cfg, const p2pws_rsa_t* rsa, const uint8_t* key_id32, p2pws_cipher_ctx_t* out_ctx, p2pws_buf_t* out_hand) {
  if (!cfg || !rsa || !out_ctx || !out_hand) return -1;
  memset(out_ctx, 0, sizeof(*out_ctx));

  const char* req = cfg->crypto_mode[0] ? cfg->crypto_mode : "KEYFILE_XOR_RSA_OAEP";
  set_str(out_ctx->crypto_mode, sizeof(out_ctx->crypto_mode), req);

  const uint8_t* client_random_key = NULL;
  size_t client_random_key_len = 0;
  const uint8_t* key_id_ptr = key_id32;

  if (streq(out_ctx->crypto_mode, "PLAIN")) {
    key_id_ptr = NULL;
  } else if (streq(out_ctx->crypto_mode, "CLIENT_RANDOM_XOR_RSA_OAEP")) {
    out_ctx->repeat_key_len = 32;
    if (p2pws_rand_bytes(out_ctx->repeat_key, out_ctx->repeat_key_len) != 0) return -2;
    client_random_key = out_ctx->repeat_key;
    client_random_key_len = out_ctx->repeat_key_len;
    key_id_ptr = NULL;
  } else if (streq(out_ctx->crypto_mode, "SERVER_RANDOM_XOR_RSA_OAEP")) {
    key_id_ptr = NULL;
  } else {
    set_str(out_ctx->crypto_mode, sizeof(out_ctx->crypto_mode), "KEYFILE_XOR_RSA_OAEP");
    if (!key_id_ptr) return -3;
  }

  return p2pws_msg_encode_hand(
      rsa->pub_spki_der,
      rsa->pub_spki_der_len,
      key_id_ptr,
      cfg->max_frame_payload,
      cfg->user_id,
      out_ctx->crypto_mode,
      client_random_key,
      client_random_key_len,
      out_hand);
}

int p2pws_cipher_apply_hand_ack(const p2pws_hand_ack_plain_view_t* hak, p2pws_cipher_ctx_t* ctx) {
  if (!hak || !ctx) return -1;
  if (hak->crypto_mode[0]) {
    set_str(ctx->crypto_mode, sizeof(ctx->crypto_mode), hak->crypto_mode);
  }

  ctx->offset = 0;
  if (streq(ctx->crypto_mode, "PLAIN")) {
    ctx->repeat_key_len = 0;
    return 0;
  }
  if (streq(ctx->crypto_mode, "KEYFILE_XOR_RSA_OAEP")) {
    ctx->offset = hak->offset;
    ctx->repeat_key_len = 0;
    return 0;
  }
  if (streq(ctx->crypto_mode, "CLIENT_RANDOM_XOR_RSA_OAEP")) {
    if (ctx->repeat_key_len == 0) return -2;
    return 0;
  }
  if (streq(ctx->crypto_mode, "SERVER_RANDOM_XOR_RSA_OAEP")) {
    ctx->repeat_key_len = hak->server_random_key.n < sizeof(ctx->repeat_key) ? hak->server_random_key.n : sizeof(ctx->repeat_key);
    if (ctx->repeat_key_len == 0) return -3;
    memcpy(ctx->repeat_key, hak->server_random_key.p, ctx->repeat_key_len);
    return 0;
  }
  return -4;
}

int p2pws_cipher_apply(const p2pws_cipher_ctx_t* ctx, const p2pws_keyfile_t* kf, const uint8_t* in, size_t in_len, uint8_t* out) {
  if (!ctx || (!in && in_len) || (!out && in_len)) return -1;
  if (streq(ctx->crypto_mode, "PLAIN")) {
    if (in_len) memcpy(out, in, in_len);
    return 0;
  }
  if (streq(ctx->crypto_mode, "KEYFILE_XOR_RSA_OAEP")) {
    if (!kf) return -2;
    return p2pws_xor_no_wrap(in, in_len, kf->data, kf->len, ctx->offset, out);
  }
  if (ctx->repeat_key_len == 0) return -3;
  return p2pws_xor_repeat(in, in_len, ctx->repeat_key, ctx->repeat_key_len, out);
}

uint8_t p2pws_cipher_wire_flags(const p2pws_cfg_t* cfg, const p2pws_cipher_ctx_t* ctx) {
  if (!cfg || !ctx) return 0;
  return streq(ctx->crypto_mode, "PLAIN") ? (uint8_t)cfg->flags_plain : (uint8_t)cfg->flags_encrypted;
}

