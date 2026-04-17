#include "p2pws_fs.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

static int mkdir_one(const char* p) {
  if (!p || !p[0]) return -1;
  if (CreateDirectoryA(p, NULL)) return 0;
  DWORD e = GetLastError();
  if (e == ERROR_ALREADY_EXISTS) return 0;
  return -2;
}

int p2pws_mkdirs(const char* path) {
  if (!path || !path[0]) return -1;
  char tmp[MAX_PATH];
  size_t n = strlen(path);
  if (n >= sizeof(tmp)) return -2;
  memcpy(tmp, path, n + 1);
  for (size_t i = 0; i < n; i++) {
    if (tmp[i] == '/' || tmp[i] == '\\') {
      char old = tmp[i];
      tmp[i] = 0;
      if (tmp[0]) mkdir_one(tmp);
      tmp[i] = old;
    }
  }
  return mkdir_one(tmp);
}

int p2pws_file_write_all(const char* path, const void* data, size_t len) {
  if (!path) return -1;
  FILE* f = fopen(path, "wb");
  if (!f) return -2;
  if (len) {
    if (fwrite(data, 1, len, f) != len) {
      fclose(f);
      return -3;
    }
  }
  fclose(f);
  return 0;
}

int p2pws_file_read_all(const char* path, void** out_data, size_t* out_len) {
  if (!path || !out_data || !out_len) return -1;
  *out_data = NULL;
  *out_len = 0;
  FILE* f = fopen(path, "rb");
  if (!f) return -2;
  if (fseek(f, 0, SEEK_END) != 0) {
    fclose(f);
    return -3;
  }
  long sz = ftell(f);
  if (sz < 0) {
    fclose(f);
    return -4;
  }
  if (fseek(f, 0, SEEK_SET) != 0) {
    fclose(f);
    return -5;
  }
  void* buf = malloc((size_t)sz + 1);
  if (!buf) {
    fclose(f);
    return -6;
  }
  size_t got = fread(buf, 1, (size_t)sz, f);
  fclose(f);
  if (got != (size_t)sz) {
    free(buf);
    return -7;
  }
  *out_data = buf;
  *out_len = (size_t)sz;
  return 0;
}

int p2pws_file_exists(const char* path) {
  DWORD a = GetFileAttributesA(path);
  return (a != INVALID_FILE_ATTRIBUTES) ? 1 : 0;
}

