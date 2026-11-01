/* Tiny core loader for tools/build_windows_cores.sh: loads a libretro core (DLL on Windows/Wine,
 * .so on Linux), calls retro_api_version and retro_get_system_info, prints the result.
 * Exit 0 only when the core loads and reports API version 1. No game is loaded. */
#include <stdio.h>
#include <stdbool.h>
#ifdef _WIN32
#include <windows.h>
#define OPEN(p) (void *)LoadLibraryA(p)
#define SYM(h, n) (void *)GetProcAddress((HMODULE)(h), n)
#else
#include <dlfcn.h>
#define OPEN(p) dlopen(p, RTLD_NOW | RTLD_LOCAL)
#define SYM(h, n) dlsym(h, n)
#endif

struct retro_system_info {
  const char *library_name, *library_version, *valid_extensions;
  bool need_fullpath, block_extract;
};

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: loadcore <core>\n"); return 2; }
  void *h = OPEN(argv[1]);
  if (!h) {
#ifdef _WIN32
    fprintf(stderr, "LOAD FAILED: error %lu\n", (unsigned long)GetLastError());
#else
    fprintf(stderr, "LOAD FAILED: %s\n", dlerror());
#endif
    return 1;
  }
  unsigned (*api)(void) = (unsigned (*)(void))SYM(h, "retro_api_version");
  void (*info)(struct retro_system_info *) = (void (*)(struct retro_system_info *))SYM(h, "retro_get_system_info");
  if (!api || !info) { fprintf(stderr, "MISSING EXPORT retro_api_version/retro_get_system_info\n"); return 1; }
  struct retro_system_info si = {0};
  info(&si);
  unsigned v = api();
  printf("OK api=%u name=%s version=%s ext=%s\n", v, si.library_name ? si.library_name : "?",
         si.library_version ? si.library_version : "?", si.valid_extensions ? si.valid_extensions : "?");
  fflush(stdout);
  return v == 1 ? 0 : 1;
}
