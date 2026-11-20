#include "platform/hardware_profile.h"

#include <3ds.h>

bool m3ds_hardware_probe(M3dsHardwareProfile *out_profile) {
  if (!out_profile) {
    return false;
  }

  bool is_new_3ds = false;
  APT_CheckNew3DS(&is_new_3ds);

  int raw_model = -1;
  u8 cfgu_model = 0xFFU;
  if (R_SUCCEEDED(cfguInit())) {
    if (R_SUCCEEDED(CFGU_GetSystemModel(&cfgu_model))) {
      raw_model = (int)cfgu_model;
    }
    cfguExit();
  }

  /* Speedup (804 MHz + L2 cache) is only valid on New 3DS/2DS. */
  bool l2_enabled = false;
  if (is_new_3ds) {
    osSetSpeedupEnable(true);
    l2_enabled = true;
  }

  uint32_t app_mem_bytes = osGetMemRegionSize(MEMREGION_APPLICATION);
  uint32_t firm_version = osGetFirmVersion();

  *out_profile = m3ds_hardware_profile_from_raw(
      raw_model,
      is_new_3ds,
      l2_enabled,
      app_mem_bytes,
      firm_version,
      "libctru");
  return true;
}
