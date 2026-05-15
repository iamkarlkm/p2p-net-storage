#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "p2pws_core_compat.h"
#include "p2pws_core_rpc.h"
#include "p2pws_crypto.h"
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

int main(int argc, char** argv) {
  const char* ws_url = argc > 1 ? argv[1] : "ws://127.0.0.1:18089/p2p";
  int32_t magic = argc > 2 ? (int32_t)strtol(argv[2], NULL, 0) : (int32_t)-252702961;
  const char* priv_pem_path = argc > 3 ? argv[3] : NULL;
  const char* user_id = argc > 4 ? argv[4] : "example-user-id";
  const char* msg = argc > 5 ? argv[5] : "hello from c core_compat";

  if (!priv_pem_path) {
    fprintf(stderr, "need private key pem path\n");
    return 2;
  }

  p2pws_ws_url_t u;
  p2pws_ws_client_t ws;
  memset(&ws, 0, sizeof(ws));
  if (p2pws_ws_parse_url(ws_url, &u) != 0) {
    fprintf(stderr, "bad ws url\n");
    return 3;
  }
  if (p2pws_ws_connect(&u, &ws) != 0) {
    fprintf(stderr, "ws connect failed\n");
    return 4;
  }

  p2pws_rsa_t rsa;
  if (p2pws_rsa_load_private_pem(priv_pem_path, &rsa) != 0) {
    fprintf(stderr, "load private key failed\n");
    p2pws_ws_close(&ws);
    return 5;
  }

  const int xor_len = 4096;
  uint8_t* xor_key = (uint8_t*)malloc((size_t)xor_len);
  if (!xor_key) {
    p2pws_rsa_free(&rsa);
    p2pws_ws_close(&ws);
    return 6;
  }
  if (p2pws_rand_bytes(xor_key, (size_t)xor_len) != 0) {
    free(xor_key);
    p2pws_rsa_free(&rsa);
    p2pws_ws_close(&ws);
    return 7;
  }

  uint64_t ts = now_ms();
  uint8_t ts_be[8];
  be_i64(ts, ts_be);
  uint8_t* user_bytes = (uint8_t*)user_id;
  size_t user_len = strlen(user_id);
  p2pws_buf_t nonce_in;
  p2pws_buf_init(&nonce_in);
  p2pws_buf_append(&nonce_in, ts_be, 8);
  p2pws_buf_append(&nonce_in, user_bytes, user_len);
  uint8_t nonce32[32];
  p2pws_sha256_bytes(nonce_in.data, nonce_in.len, nonce32);
  p2pws_buf_free(&nonce_in);
  uint8_t nonce16[16];
  memcpy(nonce16, nonce32, 16);

  p2pws_buf_t encrypted_xor_key;
  p2pws_buf_init(&encrypted_xor_key);
  if (p2pws_rsa_pkcs1v15_private_encrypt_large(&rsa, xor_key, (size_t)xor_len, &encrypted_xor_key) != 0) {
    p2pws_buf_free(&encrypted_xor_key);
    free(xor_key);
    p2pws_rsa_free(&rsa);
    p2pws_ws_close(&ws);
    return 8;
  }

  uint8_t key_hash[32];
  p2pws_sha256_bytes(encrypted_xor_key.data, encrypted_xor_key.len, key_hash);

  p2pws_buf_t sig_payload;
  p2pws_buf_init(&sig_payload);
  uint8_t xor_len_be[4];
  be_i32((uint32_t)xor_len, xor_len_be);
  p2pws_buf_append(&sig_payload, ts_be, 8);
  p2pws_buf_append(&sig_payload, xor_len_be, 4);
  p2pws_buf_append(&sig_payload, user_bytes, user_len);
  p2pws_buf_append(&sig_payload, nonce16, 16);
  p2pws_buf_append(&sig_payload, key_hash, 32);

  p2pws_buf_t sig;
  p2pws_buf_init(&sig);
  if (p2pws_rsa_sign_sha256(&rsa, sig_payload.data, sig_payload.len, &sig) != 0) {
    p2pws_buf_free(&sig_payload);
    p2pws_buf_free(&sig);
    p2pws_buf_free(&encrypted_xor_key);
    free(xor_key);
    p2pws_rsa_free(&rsa);
    p2pws_ws_close(&ws);
    return 9;
  }
  p2pws_buf_free(&sig_payload);

  p2pws_buf_t hand_req;
  p2pws_buf_init(&hand_req);
  p2pws_core_encode_handshake_request(user_id, ts, nonce16, xor_len, encrypted_xor_key.data, encrypted_xor_key.len, sig.data, sig.len, &hand_req);

  p2pws_buf_t frame_payload;
  p2pws_buf_init(&frame_payload);
  p2pws_wrapper_view_t resp;
  int rc = p2pws_core_request(&ws, magic, NULL, 0, 0, 1, P2PWS_P2P_COMMAND_ORDINAL_HAND, hand_req.data, hand_req.len, &frame_payload, &resp);
  if (rc != 0) {
    fprintf(stderr, "HAND request failed: %d\n", rc);
    goto done;
  }
  if (resp.command != P2PWS_P2P_COMMAND_ORDINAL_STD_OK) {
    fprintf(stderr, "HAND response cmd=%d\n", resp.command);
    goto done;
  }
  int ok_hand = 0;
  char err_hand[256];
  p2pws_core_decode_handshake_response(resp.data.p, resp.data.n, &ok_hand, err_hand, sizeof(err_hand));
  if (!ok_hand) {
    fprintf(stderr, "HAND rejected: %s\n", err_hand);
    goto done;
  }
  printf("HAND ok\n");

  uint64_t lts = now_ms();
  uint8_t lts_be[8];
  be_i64(lts, lts_be);
  p2pws_buf_t login_sig_payload;
  p2pws_buf_init(&login_sig_payload);
  p2pws_buf_append(&login_sig_payload, lts_be, 8);
  p2pws_buf_append(&login_sig_payload, user_bytes, user_len);
  p2pws_pb_reset(&sig);
  if (p2pws_rsa_sign_sha256(&rsa, login_sig_payload.data, login_sig_payload.len, &sig) != 0) {
    p2pws_buf_free(&login_sig_payload);
    goto done;
  }
  p2pws_buf_free(&login_sig_payload);

  p2pws_buf_t login_req;
  p2pws_buf_init(&login_req);
  p2pws_core_encode_login_request(user_id, lts, sig.data, sig.len, &login_req);

  rc = p2pws_core_request(&ws, magic, xor_key, (size_t)xor_len, 1, 2, P2PWS_P2P_COMMAND_ORDINAL_LOGIN, login_req.data, login_req.len, &frame_payload, &resp);
  if (rc != 0) {
    fprintf(stderr, "LOGIN request failed: %d\n", rc);
    goto done;
  }
  if (resp.command != P2PWS_P2P_COMMAND_ORDINAL_STD_OK) {
    fprintf(stderr, "LOGIN response cmd=%d\n", resp.command);
    goto done;
  }
  int ok_login = 0;
  char err_login[256];
  p2pws_core_decode_login_response(resp.data.p, resp.data.n, &ok_login, err_login, sizeof(err_login));
  if (!ok_login) {
    fprintf(stderr, "LOGIN rejected: %s\n", err_login);
    goto done;
  }
  printf("LOGIN ok\n");

  p2pws_buf_t meta;
  p2pws_buf_t req;
  p2pws_buf_t rpc_open;
  p2pws_buf_init(&meta);
  p2pws_buf_init(&req);
  p2pws_buf_init(&rpc_open);

  p2pws_core_rpc_encode_discover_request("", 1, &req);
  p2pws_core_rpc_encode_meta(1, "", "Discover", 1, &meta);
  p2pws_core_rpc_encode_open_unary(meta.data, meta.len, req.data, req.len, &rpc_open);
  rc = p2pws_core_request(&ws, magic, xor_key, (size_t)xor_len, 1, 3, P2PWS_P2P_COMMAND_ORDINAL_RPC_DISCOVER, rpc_open.data, rpc_open.len, &frame_payload, &resp);
  if (rc == 0) {
    int ft = 0, sc = 0;
    p2pws_pb_slice_t pl;
    if (p2pws_core_rpc_decode_frame_basic(resp.data.p, resp.data.n, &ft, &sc, &pl) == 0) {
      printf("RPC_DISCOVER frameType=%d status=%d payload=%zu\n", ft, sc, pl.n);
    }
  }

  p2pws_core_rpc_encode_health_request("p2p.rpc.echo.v1.EchoService", &req);
  p2pws_core_rpc_encode_meta(2, "p2p.rpc.echo.v1.EchoService", "Check", 1, &meta);
  p2pws_core_rpc_encode_open_unary(meta.data, meta.len, req.data, req.len, &rpc_open);
  rc = p2pws_core_request(&ws, magic, xor_key, (size_t)xor_len, 1, 4, P2PWS_P2P_COMMAND_ORDINAL_RPC_HEALTH, rpc_open.data, rpc_open.len, &frame_payload, &resp);
  if (rc == 0) {
    int ft = 0, sc = 0;
    p2pws_pb_slice_t pl;
    if (p2pws_core_rpc_decode_frame_basic(resp.data.p, resp.data.n, &ft, &sc, &pl) == 0) {
      printf("RPC_HEALTH frameType=%d status=%d payload=%zu\n", ft, sc, pl.n);
    }
  }

  p2pws_core_rpc_encode_echo_request(msg, &req);
  p2pws_core_rpc_encode_meta(3, "p2p.rpc.echo.v1.EchoService", "Echo", 1, &meta);
  p2pws_core_rpc_encode_open_unary(meta.data, meta.len, req.data, req.len, &rpc_open);
  rc = p2pws_core_request(&ws, magic, xor_key, (size_t)xor_len, 1, 5, P2PWS_P2P_COMMAND_ORDINAL_RPC_UNARY, rpc_open.data, rpc_open.len, &frame_payload, &resp);
  if (rc == 0) {
    int ft = 0, sc = 0;
    p2pws_pb_slice_t pl;
    if (p2pws_core_rpc_decode_frame_basic(resp.data.p, resp.data.n, &ft, &sc, &pl) == 0) {
      char echo_msg[256];
      uint64_t server_time = 0;
      if (p2pws_core_rpc_decode_echo_response(pl.p, pl.n, echo_msg, sizeof(echo_msg), &server_time) == 0) {
        printf("ECHO msg=%s server_time=%llu\n", echo_msg, (unsigned long long)server_time);
      }
    }
  }

  p2pws_buf_free(&meta);
  p2pws_buf_free(&req);
  p2pws_buf_free(&rpc_open);

done:
  p2pws_buf_free(&hand_req);
  p2pws_buf_free(&login_req);
  p2pws_buf_free(&sig);
  p2pws_buf_free(&encrypted_xor_key);
  p2pws_buf_free(&frame_payload);
  p2pws_rsa_free(&rsa);
  p2pws_ws_close(&ws);
  free(xor_key);
  return 0;
}
