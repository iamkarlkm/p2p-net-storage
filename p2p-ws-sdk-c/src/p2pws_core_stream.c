#include "p2pws_core_stream.h"

#include <string.h>

#include "p2pws_core_compat.h"
#include "p2pws_p2p_command_ordinals.h"

static int must_init(p2pws_core_stream_t* s, p2pws_ws_client_t* ws, int32_t magic, const uint8_t* xor_key, size_t xor_key_len) {
  if (!s || !ws) return -1;
  memset(s, 0, sizeof(*s));
  s->ws = ws;
  s->magic = magic;
  s->xor_key = xor_key;
  s->xor_key_len = xor_key_len;
  s->next_index = 1;
  s->command_rpc_stream = P2PWS_P2P_COMMAND_ORDINAL_RPC_STREAM;
  s->command_rpc_event = P2PWS_P2P_COMMAND_ORDINAL_RPC_EVENT;
  s->command_rpc_control = P2PWS_P2P_COMMAND_ORDINAL_RPC_CONTROL;
  s->command_stream_ack = P2PWS_P2P_COMMAND_ORDINAL_STREAM_ACK;
  return 0;
}

static int recv_ack(p2pws_core_stream_t* s, p2pws_buf_t* io_frame_payload) {
  for (;;) {
    p2pws_stream_wrapper_view_t w;
    int r = p2pws_core_recv_stream_wrapper(s->ws, s->magic, s->xor_key, s->xor_key_len, io_frame_payload, &w);
    if (r != 0) return r;
    if ((uint64_t)w.seq != s->request_id) continue;
    if (w.command == s->command_stream_ack) return 0;
    if (w.command == P2PWS_P2P_COMMAND_ORDINAL_STD_ERROR) return -2;
    if (w.command == P2PWS_P2P_COMMAND_ORDINAL_INVALID_PROTOCOL) return -3;
  }
}

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
  p2pws_buf_t* io_frame_payload) {
  if (!out || !ws || !io_frame_payload) return -1;
  int r = must_init(out, ws, magic, xor_key, xor_key_len);
  if (r != 0) return r;
  out->request_id = request_id;
  out->outbound_permits = permits > 0 ? permits : 0;
  out->max_frame_bytes = max_frame_bytes > 0 ? max_frame_bytes : 0;

  p2pws_buf_t meta;
  p2pws_buf_t open;
  p2pws_buf_init(&meta);
  p2pws_buf_init(&open);

  r = p2pws_core_rpc_encode_meta_ex(request_id, service, method, call_type, 0, "protobuf", &meta);
  if (r != 0) goto done;
  r = p2pws_core_rpc_encode_open_stream(
    meta.data,
    meta.len,
    request_payload,
    request_payload_len,
    permits,
    max_inflight_frames,
    max_frame_bytes,
    open_end_of_stream ? 1 : 0,
    &open);
  if (r != 0) goto done;

  r = p2pws_core_send_stream_wrapper(
    ws,
    magic,
    xor_key,
    xor_key_len,
    1,
    (int32_t)request_id,
    command_ordinal,
    open.data,
    open.len,
    0,
    0,
    0);
  if (r != 0) goto done;

  r = recv_ack(out, io_frame_payload);

done:
  p2pws_buf_free(&meta);
  p2pws_buf_free(&open);
  return r;
}

int p2pws_core_stream_send_message(p2pws_core_stream_t* s, const uint8_t* payload, size_t payload_len, p2pws_buf_t* io_rpc_frame) {
  if (!s || !io_rpc_frame) return -1;
  if (s->outbound_permits <= 0) return -11;
  if (!payload && payload_len) return -2;
  if (s->max_frame_bytes <= 0 || payload_len <= (size_t)s->max_frame_bytes) {
    int r = p2pws_core_rpc_encode_data(s->request_id, payload, payload_len, 0, 1, io_rpc_frame);
    if (r != 0) return r;
    r = p2pws_core_send_stream_wrapper(
      s->ws,
      s->magic,
      s->xor_key,
      s->xor_key_len,
      1,
      (int32_t)s->request_id,
      s->command_rpc_stream,
      io_rpc_frame->data,
      io_rpc_frame->len,
      s->next_index++,
      0,
      0);
    if (r != 0) return r;
    s->outbound_permits -= 1;
    return 0;
  }
  int32_t idx = 0;
  size_t off = 0;
  while (off < payload_len) {
    if (s->outbound_permits <= 0) return -11;
    size_t n = payload_len - off;
    if (n > (size_t)s->max_frame_bytes) n = (size_t)s->max_frame_bytes;
    int end = (off + n >= payload_len) ? 1 : 0;
    int r = p2pws_core_rpc_encode_data(s->request_id, payload + off, n, idx, end, io_rpc_frame);
    if (r != 0) return r;
    r = p2pws_core_send_stream_wrapper(
      s->ws,
      s->magic,
      s->xor_key,
      s->xor_key_len,
      1,
      (int32_t)s->request_id,
      s->command_rpc_stream,
      io_rpc_frame->data,
      io_rpc_frame->len,
      s->next_index++,
      0,
      0);
    if (r != 0) return r;
    s->outbound_permits -= 1;
    idx++;
    off += n;
  }
  return 0;
}

int p2pws_core_stream_half_close(p2pws_core_stream_t* s, p2pws_buf_t* io_rpc_frame) {
  if (!s || !io_rpc_frame) return -1;
  int r = p2pws_core_rpc_encode_close(s->request_id, io_rpc_frame);
  if (r != 0) return r;
  return p2pws_core_send_stream_wrapper(
    s->ws,
    s->magic,
    s->xor_key,
    s->xor_key_len,
    1,
    (int32_t)s->request_id,
    s->command_rpc_stream,
    io_rpc_frame->data,
    io_rpc_frame->len,
    s->next_index++,
    1,
    0);
}

int p2pws_core_stream_cancel(p2pws_core_stream_t* s, p2pws_buf_t* io_rpc_frame) {
  if (!s || !io_rpc_frame) return -1;
  int r = p2pws_core_rpc_encode_cancel(s->request_id, io_rpc_frame);
  if (r != 0) return r;
  return p2pws_core_send_wrapper(
    s->ws,
    s->magic,
    s->xor_key,
    s->xor_key_len,
    1,
    (int32_t)s->request_id,
    s->command_rpc_control,
    io_rpc_frame->data,
    io_rpc_frame->len);
}

int p2pws_core_stream_recv_next(
  p2pws_core_stream_t* s,
  p2pws_buf_t* io_frame_payload,
  p2pws_stream_wrapper_view_t* out_wrapper,
  p2pws_core_rpc_frame_ex_t* out_frame) {
  if (!s || !io_frame_payload || !out_wrapper || !out_frame) return -1;
  for (;;) {
    p2pws_stream_wrapper_view_t w;
    int r = p2pws_core_recv_stream_wrapper(s->ws, s->magic, s->xor_key, s->xor_key_len, io_frame_payload, &w);
    if (r != 0) return r;
    if ((uint64_t)w.seq != s->request_id) continue;
    if (w.command != s->command_rpc_stream && w.command != s->command_rpc_event) continue;
    r = p2pws_core_rpc_decode_frame_ex(w.data.p, w.data.n, out_frame);
    if (r != 0) return r;
    if (out_frame->frame_type == 6 && out_frame->permits > 0) {
      s->outbound_permits += out_frame->permits;
      continue;
    }
    *out_wrapper = w;
    return 0;
  }
}
