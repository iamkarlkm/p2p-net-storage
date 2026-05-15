#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"
#include "p2pws_pb.h"

#ifdef __cplusplus
extern "C" {
#endif

int p2pws_core_rpc_encode_discover_request(const char* service, int include_methods, p2pws_buf_t* out);
int p2pws_core_rpc_encode_health_request(const char* service, p2pws_buf_t* out);
int p2pws_core_rpc_encode_echo_request(const char* message, p2pws_buf_t* out);

int p2pws_core_rpc_encode_meta(uint64_t request_id, const char* service, const char* method, int call_type, p2pws_buf_t* out);
int p2pws_core_rpc_encode_open_unary(const uint8_t* meta, size_t meta_len, const uint8_t* payload, size_t payload_len, p2pws_buf_t* out);

int p2pws_core_rpc_decode_frame_basic(const uint8_t* p, size_t n, int* out_frame_type, int* out_status_code, p2pws_pb_slice_t* out_payload);
int p2pws_core_rpc_decode_echo_response(const uint8_t* p, size_t n, char* out_msg, size_t out_cap, uint64_t* out_server_time);

#ifdef __cplusplus
}
#endif

