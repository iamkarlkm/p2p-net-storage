#include "p2pws_buf.h"

#include <stdlib.h>
#include <string.h>

void p2pws_buf_init(p2pws_buf_t* b) {
  if (!b) return;
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
}

void p2pws_buf_free(p2pws_buf_t* b) {
  if (!b) return;
  free(b->data);
  b->data = NULL;
  b->len = 0;
  b->cap = 0;
}

int p2pws_buf_reserve(p2pws_buf_t* b, size_t cap) {
  if (!b) return -1;
  if (cap <= b->cap) return 0;
  size_t new_cap = b->cap ? b->cap : 256;
  while (new_cap < cap) {
    size_t next = new_cap * 2;
    if (next <= new_cap) return -2;
    new_cap = next;
  }
  uint8_t* p = (uint8_t*)realloc(b->data, new_cap);
  if (!p) return -3;
  b->data = p;
  b->cap = new_cap;
  return 0;
}

int p2pws_buf_append(p2pws_buf_t* b, const void* data, size_t len) {
  if (!b) return -1;
  if (len == 0) return 0;
  if (!data) return -2;
  if (b->len + len < b->len) return -3;
  int r = p2pws_buf_reserve(b, b->len + len);
  if (r != 0) return r;
  memcpy(b->data + b->len, data, len);
  b->len += len;
  return 0;
}

int p2pws_buf_append_u8(p2pws_buf_t* b, uint8_t v) {
  return p2pws_buf_append(b, &v, 1);
}

