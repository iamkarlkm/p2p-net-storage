#pragma once

#include <stddef.h>
#include <stdint.h>

#include "p2pws_buf.h"
#include "p2pws_core_rpc.h"
#include "p2pws_pb.h"
#include "p2pws_ws.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_core_stream {
  p2pws_ws_client_t* ws;
  int32_t magic;
  const uint8_t* xor_key;
  size_t xor_key_len;
  uint64_t request_id;
  int32_t next_index;
  int32_t outbound_permits;
  int32_t max_frame_bytes;
  int32_t command_rpc_stream;
  int32_t command_rpc_event;
  int32_t command_rpc_control;
  int32_t command_stream_ack;
} p2pws_core_stream_t;

int p2pws_core_stream_open(
  p2pws_core_stream_t* out,
  p2pws_ws_client_t* ws,
  int32_t magic,
  const uint8_t* xor_key,
  size_t xor_key_len,
  int32_t command_ordinal,
  uint64_t request_id,
  const char* service,
  const char* method,
  int call_type,
  const uint8_t* request_payload,
  size_t request_payload_len,
  int permits,
  int max_inflight_frames,
  int max_frame_bytes,
  int open_end_of_stream,
  p2pws_buf_t* io_frame_payload);

int p2pws_core_stream_send_message(p2pws_core_stream_t* s, const uint8_t* payload, size_t payload_len, p2pws_buf_t* io_rpc_frame);

int p2pws_core_stream_half_close(p2pws_core_stream_t* s, p2pws_buf_t* io_rpc_frame);

int p2pws_core_stream_cancel(p2pws_core_stream_t* s, p2pws_buf_t* io_rpc_frame);

int p2pws_core_stream_recv_next(
  p2pws_core_stream_t* s,
  p2pws_buf_t* io_frame_payload,
  p2pws_stream_wrapper_view_t* out_wrapper,
  p2pws_core_rpc_frame_ex_t* out_frame);

#ifdef __cplusplus
}
#endif

