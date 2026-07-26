#include <stdio.h>
#include <string.h>

#include "p2pws_buf.h"
#include "p2pws_core_rpc.h"
#include "p2pws_messages.h"
#include "p2pws_pb.h"

static int test_stream_wrapper(void) {
  const uint8_t payload[] = {1, 2, 3};
  p2pws_buf_t b;
  p2pws_buf_init(&b);
  int r = p2pws_msg_encode_stream_wrapper(7, 99, payload, sizeof(payload), 5, 1, 0, &b);
  if (r != 0) {
    p2pws_buf_free(&b);
    return r;
  }
  p2pws_stream_wrapper_view_t v;
  r = p2pws_pb_decode_stream_wrapper(b.data, b.len, &v);
  if (r != 0) {
    p2pws_buf_free(&b);
    return r;
  }
  if (v.seq != 7 || v.command != 99 || v.index != 5 || v.completed != 1 || v.canceled != 0) {
    p2pws_buf_free(&b);
    return -10;
  }
  if (v.data.n != sizeof(payload) || memcmp(v.data.p, payload, sizeof(payload)) != 0) {
    p2pws_buf_free(&b);
    return -11;
  }
  p2pws_buf_free(&b);
  return 0;
}

static int test_rpc_frames(void) {
  p2pws_buf_t meta;
  p2pws_buf_t frame;
  p2pws_buf_init(&meta);
  p2pws_buf_init(&frame);

  int r = p2pws_core_rpc_encode_meta_minimal(123, &meta);
  if (r != 0) goto done;
  r = p2pws_core_rpc_encode_data(123, (const uint8_t*)"abc", 3, 0, 1, &frame);
  if (r != 0) goto done;

  p2pws_core_rpc_frame_ex_t fx;
  r = p2pws_core_rpc_decode_frame_ex(frame.data, frame.len, &fx);
  if (r != 0) goto done;
  if (fx.frame_type != 2 || fx.end_of_message != 1) {
    r = -20;
    goto done;
  }
  if (fx.payload.n != 3 || memcmp(fx.payload.p, "abc", 3) != 0) {
    r = -21;
    goto done;
  }

done:
  p2pws_buf_free(&meta);
  p2pws_buf_free(&frame);
  return r;
}

int main(void) {
  int r = test_stream_wrapper();
  if (r != 0) {
    printf("fail stream wrapper: %d\n", r);
    return 1;
  }
  r = test_rpc_frames();
  if (r != 0) {
    printf("fail rpc frames: %d\n", r);
    return 2;
  }
  printf("ok=1\n");
  return 0;
}

