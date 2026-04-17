#pragma once

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int p2pws_mkdirs(const char* path);
int p2pws_file_write_all(const char* path, const void* data, size_t len);
int p2pws_file_read_all(const char* path, void** out_data, size_t* out_len);
int p2pws_file_exists(const char* path);

#ifdef __cplusplus
}
#endif

