#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_pb_slice {
  const uint8_t* p;
  size_t n;
} p2pws_pb_slice_t;

typedef struct p2pws_wrapper_view {
  int32_t seq;
  int32_t command;
  p2pws_pb_slice_t data;
} p2pws_wrapper_view_t;

void p2pws_pb_reset(p2pws_buf_t* b);

int p2pws_pb_write_varint_u64(p2pws_buf_t* b, uint64_t v);
int p2pws_pb_write_varint_i32(p2pws_buf_t* b, int32_t v);
int p2pws_pb_write_key(p2pws_buf_t* b, uint32_t field_no, uint32_t wire_type);
int p2pws_pb_write_bytes(p2pws_buf_t* b, uint32_t field_no, const void* p, size_t n);
int p2pws_pb_write_string(p2pws_buf_t* b, uint32_t field_no, const char* s);
int p2pws_pb_write_fixed64(p2pws_buf_t* b, uint32_t field_no, uint64_t v);
int p2pws_pb_write_bool(p2pws_buf_t* b, uint32_t field_no, int v);

int p2pws_pb_decode_wrapper(const uint8_t* p, size_t n, p2pws_wrapper_view_t* out);

typedef struct p2pws_hand_ack_plain_view {
  uint32_t offset;
  uint32_t max_frame_payload;
  uint32_t header_policy_id;
  p2pws_pb_slice_t selected_key_id;
  p2pws_pb_slice_t session_id;
} p2pws_hand_ack_plain_view_t;

typedef struct p2pws_hand_view {
  p2pws_pb_slice_t client_pubkey;
  p2pws_pb_slice_t key_id;
  uint32_t max_frame_payload;
  char client_id[64];
} p2pws_hand_view_t;

typedef struct p2pws_peer_hello_view {
  p2pws_pb_slice_t body;
  p2pws_pb_slice_t signature;
} p2pws_peer_hello_view_t;

typedef struct p2pws_peer_hello_body_view {
  uint64_t node_id64;
  p2pws_pb_slice_t pubkey_spki_der;
  uint64_t timestamp_ms;
  char crypto_mode[64];
} p2pws_peer_hello_body_view_t;

typedef struct p2pws_endpoint_view {
  char transport[16];
  char addr[128];
} p2pws_endpoint_view_t;

typedef struct p2pws_center_hello_ack_view {
  p2pws_pb_slice_t node_key;
  p2pws_endpoint_view_t observed_endpoint;
  uint32_t ttl_seconds;
  uint64_t server_time_ms;
  int has_observed;
} p2pws_center_hello_ack_view_t;

typedef struct p2pws_get_node_ack_view {
  int found;
  p2pws_pb_slice_t node_key;
  uint64_t node_id64;
  p2pws_endpoint_view_t endpoints[4];
  size_t endpoints_len;
} p2pws_get_node_ack_view_t;

typedef struct p2pws_peer_hello_ack_view {
  p2pws_pb_slice_t node_key;
  uint64_t server_time_ms;
} p2pws_peer_hello_ack_view_t;

typedef struct p2pws_relay_data_view {
  uint64_t target_node_id64;
  p2pws_pb_slice_t target_node_key;
  uint64_t source_node_id64;
  p2pws_pb_slice_t source_node_key;
  p2pws_pb_slice_t payload;
} p2pws_relay_data_view_t;

typedef struct p2pws_file_put_resp_view {
  int success;
  p2pws_pb_slice_t file_hash_sha256;
} p2pws_file_put_resp_view_t;

typedef struct p2pws_file_put_req_view {
  char file_name[128];
  uint64_t file_size;
  p2pws_pb_slice_t file_hash_sha256;
  p2pws_pb_slice_t content;
} p2pws_file_put_req_view_t;

typedef struct p2pws_file_get_resp_view {
  int found;
  p2pws_pb_slice_t content;
} p2pws_file_get_resp_view_t;

typedef struct p2pws_file_get_req_view {
  p2pws_pb_slice_t file_hash_sha256;
} p2pws_file_get_req_view_t;

int p2pws_pb_decode_hand(const uint8_t* p, size_t n, p2pws_hand_view_t* out);
int p2pws_pb_decode_hand_ack_plain(const uint8_t* p, size_t n, p2pws_hand_ack_plain_view_t* out);
int p2pws_pb_decode_center_hello_ack(const uint8_t* p, size_t n, p2pws_center_hello_ack_view_t* out);
int p2pws_pb_decode_get_node_ack(const uint8_t* p, size_t n, p2pws_get_node_ack_view_t* out);
int p2pws_pb_decode_peer_hello(const uint8_t* p, size_t n, p2pws_peer_hello_view_t* out);
int p2pws_pb_decode_peer_hello_body(const uint8_t* p, size_t n, p2pws_peer_hello_body_view_t* out);
int p2pws_pb_decode_peer_hello_ack(const uint8_t* p, size_t n, p2pws_peer_hello_ack_view_t* out);
int p2pws_pb_decode_relay_data(const uint8_t* p, size_t n, p2pws_relay_data_view_t* out);
int p2pws_pb_decode_file_put_req(const uint8_t* p, size_t n, p2pws_file_put_req_view_t* out);
int p2pws_pb_decode_file_put_resp(const uint8_t* p, size_t n, p2pws_file_put_resp_view_t* out);
int p2pws_pb_decode_file_get_req(const uint8_t* p, size_t n, p2pws_file_get_req_view_t* out);
int p2pws_pb_decode_file_get_resp(const uint8_t* p, size_t n, p2pws_file_get_resp_view_t* out);

#ifdef __cplusplus
}
#endif
