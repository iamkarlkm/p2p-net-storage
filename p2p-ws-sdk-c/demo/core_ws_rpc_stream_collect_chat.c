#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "p2pws_core_compat.h"
#include "p2pws_core_rpc.h"
#include "p2pws_core_stream.h"
#include "p2pws_crypto.h"
#include "p2pws_messages.h"
#include "p2pws_p2p_command_ordinals.h"

static void be_i64(uint64_t v, uint8_t out[8]) {
  out[0] = (uint8_t)((v >> 56) & 0xFF);
  out[1] = (uint8_t)((v >> 48) & 0xFF);
  out[2] = (uint8_t)((v >> 40) & 0xFF);
  out[3] = (uint8_t)((v >> 32) & 0xFF);
  out[4] = (uint8_t)((v >> 24) & 0xFF);
  out[5] = (uint8_t)((v >> 16) & 0xFF);
  out[6] = (uint8_t)((v >> 8) & 0xFF);
  out[7] = (uint8_t)((v)&0xFF);
}

static void be_i32(uint32_t v, uint8_t out[4]) {
  out[0] = (uint8_t)((v >> 24) & 0xFF);
  out[1] = (uint8_t)((v >> 16) & 0xFF);
  out[2] = (uint8_t)((v >> 8) & 0xFF);
  out[3] = (uint8_t)((v)&0xFF);
}

static uint64_t now_ms(void) {
  return (uint64_t)time(NULL) * 1000ULL;
}

static int connect_and_login(
  const char* ws_url,
  int32_t magic,
  const char* priv_pem_path,
  const char* user_id,
  p2pws_ws_client_t* out_ws,
  p2pws_rsa_t* out_rsa,
  uint8_t** out_xor_key,
  int* out_xor_len,
  p2pws_buf_t* io_frame_payload) {
  if (!ws_url || !priv_pem_path || !user_id || !out_ws || !out_rsa || !out_xor_key || !out_xor_len || !io_frame_payload) return -1;
  memset(out_ws, 0, sizeof(*out_ws));
  p2pws_ws_url_t u;
  int rc = p2pws_ws_parse_url(ws_url, &u);
  if (rc != 0) return -2000 + rc;
  rc = p2pws_ws_connect(&u, out_ws);
  if (rc != 0) return -3000 + rc;
  rc = p2pws_rsa_load_private_pem(priv_pem_path, out_rsa);
  if (rc != 0) return -4000 + rc;

  const int xor_len = 4096;
  uint8_t* xor_key = (uint8_t*)malloc((size_t)xor_len);
  if (!xor_key) return -5;
  if (p2pws_rand_bytes(xor_key, (size_t)xor_len) != 0) {
    free(xor_key);
    return -6;
  }

  uint64_t ts = now_ms();
  uint8_t ts_be[8];
  be_i64(ts, ts_be);
  size_t user_len = strlen(user_id);
  p2pws_buf_t nonce_in;
  p2pws_buf_init(&nonce_in);
  p2pws_buf_append(&nonce_in, ts_be, 8);
  p2pws_buf_append(&nonce_in, user_id, user_len);
  uint8_t nonce32[32];
  p2pws_sha256_bytes(nonce_in.data, nonce_in.len, nonce32);
  p2pws_buf_free(&nonce_in);
  uint8_t nonce16[16];
  memcpy(nonce16, nonce32, 16);

  p2pws_buf_t encrypted_xor_key;
  p2pws_buf_init(&encrypted_xor_key);
  if (p2pws_rsa_pkcs1v15_private_encrypt_large(out_rsa, xor_key, (size_t)xor_len, &encrypted_xor_key) != 0) {
    p2pws_buf_free(&encrypted_xor_key);
    free(xor_key);
    return -7;
  }

  uint8_t key_hash[32];
  p2pws_sha256_bytes(encrypted_xor_key.data, encrypted_xor_key.len, key_hash);

  p2pws_buf_t sig_payload;
  p2pws_buf_init(&sig_payload);
  uint8_t xor_len_be[4];
  be_i32((uint32_t)xor_len, xor_len_be);
  p2pws_buf_append(&sig_payload, ts_be, 8);
  p2pws_buf_append(&sig_payload, xor_len_be, 4);
  p2pws_buf_append(&sig_payload, user_id, user_len);
  p2pws_buf_append(&sig_payload, nonce16, 16);
  p2pws_buf_append(&sig_payload, key_hash, 32);

  p2pws_buf_t sig;
  p2pws_buf_init(&sig);
  if (p2pws_rsa_sign_sha256(out_rsa, sig_payload.data, sig_payload.len, &sig) != 0) {
    p2pws_buf_free(&sig_payload);
    p2pws_buf_free(&sig);
    p2pws_buf_free(&encrypted_xor_key);
    free(xor_key);
    return -8;
  }
  p2pws_buf_free(&sig_payload);

  p2pws_buf_t hand_req;
  p2pws_buf_init(&hand_req);
  p2pws_core_encode_handshake_request(user_id, ts, nonce16, xor_len, encrypted_xor_key.data, encrypted_xor_key.len, sig.data, sig.len, &hand_req);

  p2pws_wrapper_view_t resp;
  rc = p2pws_core_request(out_ws, magic, NULL, 0, 0, 1, P2PWS_P2P_COMMAND_ORDINAL_HAND, hand_req.data, hand_req.len, io_frame_payload, &resp);
  p2pws_buf_free(&hand_req);
  p2pws_buf_free(&encrypted_xor_key);
  if (rc != 0) {
    p2pws_buf_free(&sig);
    free(xor_key);
    return -9;
  }
  if (resp.command != P2PWS_P2P_COMMAND_ORDINAL_STD_OK) {
    p2pws_buf_free(&sig);
    free(xor_key);
    return -10;
  }
  int ok_hand = 0;
  char err_hand[256];
  p2pws_core_decode_handshake_response(resp.data.p, resp.data.n, &ok_hand, err_hand, sizeof(err_hand));
  if (!ok_hand) {
    p2pws_buf_free(&sig);
    free(xor_key);
    return -11;
  }

  uint64_t lts = now_ms();
  uint8_t lts_be[8];
  be_i64(lts, lts_be);
  p2pws_buf_t login_sig_payload;
  p2pws_buf_init(&login_sig_payload);
  p2pws_buf_append(&login_sig_payload, lts_be, 8);
  p2pws_buf_append(&login_sig_payload, user_id, user_len);
  p2pws_pb_reset(&sig);
  if (p2pws_rsa_sign_sha256(out_rsa, login_sig_payload.data, login_sig_payload.len, &sig) != 0) {
    p2pws_buf_free(&login_sig_payload);
    p2pws_buf_free(&sig);
    free(xor_key);
    return -12;
  }
  p2pws_buf_free(&login_sig_payload);

  p2pws_buf_t login_req;
  p2pws_buf_init(&login_req);
  p2pws_core_encode_login_request(user_id, lts, sig.data, sig.len, &login_req);
  rc = p2pws_core_request(out_ws, magic, xor_key, (size_t)xor_len, 1, 2, P2PWS_P2P_COMMAND_ORDINAL_LOGIN, login_req.data, login_req.len, io_frame_payload, &resp);
  p2pws_buf_free(&login_req);
  p2pws_buf_free(&sig);
  if (rc != 0) {
    free(xor_key);
    return -13;
  }
  if (resp.command != P2PWS_P2P_COMMAND_ORDINAL_STD_OK) {
    free(xor_key);
    return -14;
  }
  int ok_login = 0;
  char err_login[256];
  p2pws_core_decode_login_response(resp.data.p, resp.data.n, &ok_login, err_login, sizeof(err_login));
  if (!ok_login) {
    free(xor_key);
    return -15;
  }

  *out_xor_key = xor_key;
  *out_xor_len = xor_len;
  return 0;
}

static int encode_stream_collect_request(const char* msg, p2pws_buf_t* out) {
  if (!out) return -1;
  p2pws_pb_reset(out);
  return p2pws_pb_write_string(out, 1, msg ? msg : "");
}

static int decode_stream_collect_response(const uint8_t* p, size_t n, char* out_joined, size_t out_cap, uint32_t* out_count) {
  if (!p || !out_joined || out_cap == 0 || !out_count) return -1;
  out_joined[0] = 0;
  *out_count = 0;
  size_t off = 0;
  while (off < n) {
    int ok = 0;
    uint64_t key = 0;
    size_t so = off;
    while (so < n) {
      uint8_t b = p[so++];
      key |= (uint64_t)(b & 0x7F) << ((so - off - 1) * 7);
      if ((b & 0x80) == 0) break;
    }
    off = so;
    uint32_t field = (uint32_t)(key >> 3);
    uint32_t wt = (uint32_t)(key & 7);
    if (field == 2 && wt == 0) {
      uint64_t v = 0;
      size_t vo = off;
      while (vo < n) {
        uint8_t b = p[vo++];
        v |= (uint64_t)(b & 0x7F) << ((vo - off - 1) * 7);
        if ((b & 0x80) == 0) break;
      }
      off = vo;
      *out_count = (uint32_t)v;
      continue;
    }
    if (field == 3 && wt == 2) {
      uint64_t len = 0;
      size_t lo = off;
      while (lo < n) {
        uint8_t b = p[lo++];
        len |= (uint64_t)(b & 0x7F) << ((lo - off - 1) * 7);
        if ((b & 0x80) == 0) break;
      }
      off = lo;
      if (off + (size_t)len > n) return -2;
      size_t cp = (size_t)len;
      if (cp + 1 > out_cap) cp = out_cap - 1;
      memcpy(out_joined, p + off, cp);
      out_joined[cp] = 0;
      off += (size_t)len;
      continue;
    }
    if (wt == 0) {
      while (off < n) {
        uint8_t b = p[off++];
        if ((b & 0x80) == 0) break;
      }
      continue;
    }
    if (wt == 2) {
      uint64_t len = 0;
      size_t lo = off;
      while (lo < n) {
        uint8_t b = p[lo++];
        len |= (uint64_t)(b & 0x7F) << ((lo - off - 1) * 7);
        if ((b & 0x80) == 0) break;
      }
      off = lo + (size_t)len;
      if (off > n) return -3;
      continue;
    }
    return -4;
  }
  return 0;
}

static int is_hex_64(const char* s) {
  if (!s) return 0;
  if (strlen(s) != 64) return 0;
  for (int i = 0; i < 64; i++) {
    const char c = s[i];
    const int ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    if (!ok) return 0;
  }
  return 1;
}

int main(int argc, char** argv) {
  const char* ws_url = argc > 1 ? argv[1] : "ws://127.0.0.1:18089/p2p";
  int32_t magic = argc > 2 ? (int32_t)strtol(argv[2], NULL, 0) : (int32_t)-252702961;
  const char* priv_pem_path = argc > 3 ? argv[3] : NULL;
  const char* user_id = argc > 4 ? argv[4] : "example-user-id";

  if (!priv_pem_path) {
    fprintf(stderr, "need private key pem path\n");
    return 2;
  }

  p2pws_ws_client_t ws;
  p2pws_rsa_t rsa;
  uint8_t* xor_key = NULL;
  int xor_len = 0;
  p2pws_buf_t frame_payload;
  p2pws_buf_init(&frame_payload);

  char user_hex[65];
  const char* user_for_auth = user_id;
  if (!is_hex_64(user_id)) {
    p2pws_sha256_hex((const uint8_t*)user_id, strlen(user_id), user_hex);
    user_for_auth = user_hex;
  }

  int rc = connect_and_login(ws_url, magic, priv_pem_path, user_for_auth, &ws, &rsa, &xor_key, &xor_len, &frame_payload);
  if (rc != 0) {
    fprintf(stderr, "connect/login failed: %d\n", rc);
    fprintf(stderr, "hint: -3002 means websocket tcp connect failed (server not running or blocked)\n");
    fprintf(stderr, "hint: user_id expects sha256 hex, current=%s\n", user_for_auth);
    return 3;
  }

  p2pws_buf_t req;
  p2pws_buf_t rpc_frame;
  p2pws_buf_init(&req);
  p2pws_buf_init(&rpc_frame);

  p2pws_core_stream_t s;
  uint64_t request_id = 100;
  rc = p2pws_core_stream_open(
    &s,
    &ws,
    magic,
    xor_key,
    (size_t)xor_len,
    P2PWS_P2P_COMMAND_ORDINAL_RPC_STREAM,
    request_id,
    "p2p.rpc.stream.v1.StreamService",
    "Collect",
    3,
    NULL,
    0,
    2,
    0,
    1024,
    0,
    &frame_payload);
  if (rc != 0) {
    fprintf(stderr, "open client stream failed: %d\n", rc);
    goto done;
  }

  encode_stream_collect_request("a", &req);
  rc = p2pws_core_stream_send_message(&s, req.data, req.len, &rpc_frame);
  if (rc != 0) fprintf(stderr, "send a: %d\n", rc);
  encode_stream_collect_request("b", &req);
  rc = p2pws_core_stream_send_message(&s, req.data, req.len, &rpc_frame);
  if (rc != 0) fprintf(stderr, "send b: %d\n", rc);
  encode_stream_collect_request("c", &req);
  rc = p2pws_core_stream_send_message(&s, req.data, req.len, &rpc_frame);
  if (rc != 0) fprintf(stderr, "send c: %d\n", rc);

  rc = p2pws_core_stream_half_close(&s, &rpc_frame);
  if (rc != 0) fprintf(stderr, "half close: %d\n", rc);

  for (;;) {
    p2pws_stream_wrapper_view_t w;
    p2pws_core_rpc_frame_ex_t fx;
    rc = p2pws_core_stream_recv_next(&s, &frame_payload, &w, &fx);
    if (rc != 0) {
      fprintf(stderr, "recv failed: %d\n", rc);
      break;
    }
    if (fx.frame_type == 2 && fx.end_of_message) {
      char joined[256];
      uint32_t cnt = 0;
      if (decode_stream_collect_response(fx.payload.p, fx.payload.n, joined, sizeof(joined), &cnt) == 0) {
        printf("CollectResponse count=%u joined=%s\n", cnt, joined);
      }
      continue;
    }
    if (fx.frame_type == 3 || fx.end_of_stream) {
      printf("Collect completed\n");
      break;
    }
    if (fx.frame_type == 5) {
      printf("Collect error\n");
      break;
    }
  }

done:
  p2pws_buf_free(&req);
  p2pws_buf_free(&rpc_frame);
  p2pws_buf_free(&frame_payload);
  p2pws_rsa_free(&rsa);
  p2pws_ws_close(&ws);
  free(xor_key);
  return 0;
}
