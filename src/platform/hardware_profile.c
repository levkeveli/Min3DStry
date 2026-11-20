#include "platform/hardware_profile.h"

#include <stdio.h>

static M3dsConsoleModel classify_cfgu_model(int raw_cfgu_model) {
  switch (raw_cfgu_model) {
    case 0:
      return M3DS_MODEL_O3DS;
    case 1:
      return M3DS_MODEL_O3DS_XL;
    case 2:
      return M3DS_MODEL_N3DS;
    case 3:
      return M3DS_MODEL_O2DS;
    case 4:
      return M3DS_MODEL_N3DS_XL;
    case 5:
      return M3DS_MODEL_N2DS_XL;
    default:
      return M3DS_MODEL_UNKNOWN;
  }
}

M3dsHardwareProfile m3ds_hardware_profile_from_raw(
    int raw_cfgu_model,
    bool apt_is_new_3ds,
    bool l2_cache_enabled,
    uint32_t app_mem_bytes,
    uint32_t os_firm_version,
    const char *source_name) {
  M3dsHardwareProfile profile;
  M3dsConsoleModel model = classify_cfgu_model(raw_cfgu_model);
  bool model_is_new =
      (model == M3DS_MODEL_N3DS || model == M3DS_MODEL_N3DS_XL ||
       model == M3DS_MODEL_N2DS_XL);

  profile.model = model;
  profile.is_new_3ds = apt_is_new_3ds || model_is_new;
  profile.has_c_stick_and_zl_zr = profile.is_new_3ds;
  profile.l2_cache_enabled = profile.is_new_3ds && l2_cache_enabled;
  profile.cpu_clock_mhz = profile.is_new_3ds ? 804U : 268U;
  profile.app_mem_bytes = app_mem_bytes;
  profile.os_version_major = (uint8_t)((os_firm_version >> 24) & 0xFFU);
  profile.os_version_minor = (uint8_t)((os_firm_version >> 16) & 0xFFU);
  profile.os_version_build = (uint8_t)((os_firm_version >> 8) & 0xFFU);
  profile.source_name = source_name ? source_name : "unknown";
  return profile;
}

const char *m3ds_hardware_model_name(M3dsConsoleModel model) {
  switch (model) {
    case M3DS_MODEL_O3DS:
      return "Original 3DS";
    case M3DS_MODEL_O3DS_XL:
      return "Original 3DS XL";
    case M3DS_MODEL_N3DS:
      return "New 3DS";
    case M3DS_MODEL_O2DS:
      return "Original 2DS";
    case M3DS_MODEL_N3DS_XL:
      return "New 3DS XL";
    case M3DS_MODEL_N2DS_XL:
      return "New 2DS XL";
    case M3DS_MODEL_UNKNOWN:
    default:
      return "Unknown 3DS";
  }
}

size_t m3ds_hardware_profile_format(
    const M3dsHardwareProfile *profile,
    char *out_buf,
    size_t out_buf_size) {
  if (!profile || !out_buf || out_buf_size == 0U) {
    return 0U;
  }

  unsigned int mem_mib = (unsigned int)(profile->app_mem_bytes / (1024U * 1024U));
  int written = snprintf(
      out_buf,
      out_buf_size,
      "%s (%s, %u MHz, L2:%s, AppMem:%u MiB, FIRM:%u.%u.%u)",
      m3ds_hardware_model_name(profile->model),
      profile->is_new_3ds ? "N3DS" : "O3DS",
      (unsigned int)profile->cpu_clock_mhz,
      profile->l2_cache_enabled ? "on" : "off",
      mem_mib,
      (unsigned int)profile->os_version_major,
      (unsigned int)profile->os_version_minor,
      (unsigned int)profile->os_version_build);
  if (written < 0) {
    out_buf[0] = '\0';
    return 0U;
  }
  return (size_t)written;
}
