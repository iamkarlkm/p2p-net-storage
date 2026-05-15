#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"
#include "p2pws_pb.h"
#include "p2pws_ws.h"

#ifdef __cplusplus
extern "C" {
#endif

int p2pws_core_encode_header_be(int32_t payload_len, int32_t magic, uint8_t out8[8]);
int p2pws_core_decode_header_be(const uint8_t in8[8], int32_t* out_payload_len, int32_t* out_magic);

int p2pws_core_send_wrapper(
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int encrypt,
  int32_t seq,
  int32_t command_ordinal,
  const uint8_t* data,
  size_t data_len);

int p2pws_core_recv_wrapper(
  p2pws_ws_client_t* ws,
  int32_t expected_magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  p2pws_buf_t* io_frame_payload,
  p2pws_wrapper_view_t* out_view);

int p2pws_core_request(
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int encrypt,
  int32_t seq,
  int32_t command_ordinal,
  const uint8_t* data,
  size_t data_len,
  p2pws_buf_t* io_frame_payload,
  p2pws_wrapper_view_t* out_view);

int p2pws_core_encode_handshake_request(
  const char* user_id,
  uint64_t timestamp_ms,
  const uint8_t nonce16[16],
  int32_t xor_key_length,
  const uint8_t* encrypted_xor_key,
  size_t encrypted_xor_key_len,
  const uint8_t* signature,
  size_t signature_len,
  p2pws_buf_t* out);

int p2pws_core_decode_handshake_response(
  const uint8_t* p,
  size_t n,
  int* out_ok,
  char* out_error,
  size_t out_error_cap);

int p2pws_core_encode_login_request(
  const char* user_id,
  uint64_t timestamp_ms,
  const uint8_t* signature,
  size_t signature_len,
  p2pws_buf_t* out);

int p2pws_core_decode_login_response(
  const uint8_t* p,
  size_t n,
  int* out_ok,
  char* out_error,
  size_t out_error_cap);

#ifdef __cplusplus
}
#endif

