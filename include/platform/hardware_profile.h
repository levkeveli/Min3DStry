#ifndef MIN3DSTRY_PLATFORM_HARDWARE_PROFILE_H
#define MIN3DSTRY_PLATFORM_HARDWARE_PROFILE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef enum M3dsConsoleModel {
  M3DS_MODEL_UNKNOWN = -1,
  M3DS_MODEL_O3DS = 0,
  M3DS_MODEL_O3DS_XL = 1,
  M3DS_MODEL_N3DS = 2,
  M3DS_MODEL_O2DS = 3,
  M3DS_MODEL_N3DS_XL = 4,
  M3DS_MODEL_N2DS_XL = 5
} M3dsConsoleModel;

typedef struct M3dsHardwareProfile {
  M3dsConsoleModel model;
  bool is_new_3ds;
  bool has_c_stick_and_zl_zr;
  bool l2_cache_enabled;
  uint32_t cpu_clock_mhz;
  uint32_t app_mem_bytes;
  uint8_t os_version_major;
  uint8_t os_version_minor;
  uint8_t os_version_build;
  const char *source_name;
} M3dsHardwareProfile;

/* Build a normalized profile from raw system values (host-testable). */
M3dsHardwareProfile m3ds_hardware_profile_from_raw(
    int raw_cfgu_model,
    bool apt_is_new_3ds,
    bool l2_cache_enabled,
    uint32_t app_mem_bytes,
    uint32_t os_firm_version,
    const char *source_name);

/* Return a human-readable console model name. */
const char *m3ds_hardware_model_name(M3dsConsoleModel model);

/* Format a concise one-line summary for console/log output. */
size_t m3ds_hardware_profile_format(
    const M3dsHardwareProfile *profile,
    char *out_buf,
    size_t out_buf_size);

/* Probe the active platform hardware (3DS via libctru, host stub otherwise). */
bool m3ds_hardware_probe(M3dsHardwareProfile *out_profile);

#endif
