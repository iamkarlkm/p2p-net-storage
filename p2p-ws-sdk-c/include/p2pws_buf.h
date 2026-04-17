#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct p2pws_buf {
  uint8_t* data;
  size_t len;
  size_t cap;
} p2pws_buf_t;

void p2pws_buf_init(p2pws_buf_t* b);
void p2pws_buf_free(p2pws_buf_t* b);
int p2pws_buf_reserve(p2pws_buf_t* b, size_t cap);
int p2pws_buf_append(p2pws_buf_t* b, const void* data, size_t len);
int p2pws_buf_append_u8(p2pws_buf_t* b, uint8_t v);

#ifdef __cplusplus
}
#endif

