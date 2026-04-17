#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"

#ifdef __cplusplus
extern "C" {
#endif

int p2pws_msg_encode_wrapper(int32_t seq, int32_t command, const uint8_t* data, size_t data_len, p2pws_buf_t* out);

int p2pws_msg_encode_hand(const uint8_t* client_pub_spki_der, size_t client_pub_len, const uint8_t* key_id32, uint32_t max_frame_payload, const char* client_id, p2pws_buf_t* out);

int p2pws_msg_encode_hand_ack_plain(const uint8_t session_id16[16], const uint8_t* selected_key_id32, uint32_t offset, uint32_t max_frame_payload, uint32_t header_policy_id, p2pws_buf_t* out);

int p2pws_msg_encode_center_hello_body(uint64_t node_id64, const uint8_t* pub_spki_der, size_t pub_len, const char* reported_transport, const char* reported_addr, uint32_t max_frame_payload, uint32_t magic, uint32_t version, uint32_t flags_plain, uint32_t flags_encrypted, uint64_t timestamp_ms, const char* crypto_mode, p2pws_buf_t* out);

int p2pws_msg_encode_center_hello(const uint8_t* body, size_t body_len, const uint8_t* sig, size_t sig_len, p2pws_buf_t* out);

int p2pws_msg_encode_get_node(uint64_t node_id64, const uint8_t* node_key32, size_t node_key_len, p2pws_buf_t* out);

int p2pws_msg_encode_peer_hello_body(uint64_t node_id64, const uint8_t* pub_spki_der, size_t pub_len, uint64_t timestamp_ms, const char* crypto_mode, p2pws_buf_t* out);

int p2pws_msg_encode_peer_hello(const uint8_t* body, size_t body_len, const uint8_t* sig, size_t sig_len, p2pws_buf_t* out);

int p2pws_msg_encode_peer_hello_ack(const uint8_t node_key32[32], uint64_t server_time_ms, p2pws_buf_t* out);

int p2pws_msg_encode_file_put_req(const char* file_name, const uint8_t* file_hash32, const uint8_t* content, size_t content_len, p2pws_buf_t* out);

int p2pws_msg_encode_file_put_resp(int success, const uint8_t* file_hash32, p2pws_buf_t* out);

int p2pws_msg_encode_file_get_req(const uint8_t* file_hash32, p2pws_buf_t* out);

int p2pws_msg_encode_file_get_resp(int found, const uint8_t* content, size_t content_len, p2pws_buf_t* out);

int p2pws_msg_encode_relay_data(uint64_t target_node_id64, const uint8_t* target_node_key32, size_t target_node_key_len, uint64_t source_node_id64, const uint8_t* source_node_key32, size_t source_node_key_len, const uint8_t* payload, size_t payload_len, p2pws_buf_t* out);

#ifdef __cplusplus
}
#endif
