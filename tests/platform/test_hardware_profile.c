#include "platform/hardware_profile.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void test_old_3ds_models(void) {
  M3dsHardwareProfile o3ds = m3ds_hardware_profile_from_raw(
      0, false, true, 64U * 1024U * 1024U, 0x02380000U, "mock");
  assert(o3ds.model == M3DS_MODEL_O3DS);
  assert(!o3ds.is_new_3ds);
  assert(!o3ds.has_c_stick_and_zl_zr);
  assert(!o3ds.l2_cache_enabled);
  assert(o3ds.cpu_clock_mhz == 268U);
  assert(o3ds.app_mem_bytes == 64U * 1024U * 1024U);
  assert(o3ds.os_version_major == 2U);
  assert(o3ds.os_version_minor == 56U);
  assert(strcmp(m3ds_hardware_model_name(o3ds.model), "Original 3DS") == 0);

  M3dsHardwareProfile o2ds = m3ds_hardware_profile_from_raw(
      3, false, false, 64U * 1024U * 1024U, 0x02380000U, "mock");
  assert(o2ds.model == M3DS_MODEL_O2DS);
  assert(!o2ds.is_new_3ds);
  assert(strcmp(m3ds_hardware_model_name(o2ds.model), "Original 2DS") == 0);
}

static void test_new_3ds_models(void) {
  M3dsHardwareProfile n3ds_xl = m3ds_hardware_profile_from_raw(
      4, true, true, 124U * 1024U * 1024U, 0x02380000U, "mock");
  assert(n3ds_xl.model == M3DS_MODEL_N3DS_XL);
  assert(n3ds_xl.is_new_3ds);
  assert(n3ds_xl.has_c_stick_and_zl_zr);
  assert(n3ds_xl.l2_cache_enabled);
  assert(n3ds_xl.cpu_clock_mhz == 804U);
  assert(n3ds_xl.app_mem_bytes == 124U * 1024U * 1024U);
  assert(strcmp(m3ds_hardware_model_name(n3ds_xl.model), "New 3DS XL") == 0);

  M3dsHardwareProfile n2ds_xl = m3ds_hardware_profile_from_raw(
      5, false, true, 124U * 1024U * 1024U, 0x02380000U, "mock");
  assert(n2ds_xl.model == M3DS_MODEL_N2DS_XL);
  assert(n2ds_xl.is_new_3ds);
  assert(n2ds_xl.l2_cache_enabled);
  assert(strcmp(m3ds_hardware_model_name(n2ds_xl.model), "New 2DS XL") == 0);
}

static void test_unknown_and_format(void) {
  M3dsHardwareProfile unknown = m3ds_hardware_profile_from_raw(
      99, false, false, 0U, 0U, NULL);
  assert(unknown.model == M3DS_MODEL_UNKNOWN);
  assert(strcmp(m3ds_hardware_model_name(unknown.model), "Unknown 3DS") == 0);
  assert(strcmp(unknown.source_name, "unknown") == 0);

  M3dsHardwareProfile n3ds = m3ds_hardware_profile_from_raw(
      2, true, true, 124U * 1024U * 1024U, 0x02380000U, "mock");
  char buf[160];
  size_t len = m3ds_hardware_profile_format(&n3ds, buf, sizeof(buf));
  assert(len > 0U);
  assert(strstr(buf, "New 3DS") != NULL);
  assert(strstr(buf, "804 MHz") != NULL);
  assert(strstr(buf, "L2:on") != NULL);
  assert(strstr(buf, "AppMem:124 MiB") != NULL);

  assert(m3ds_hardware_profile_format(NULL, buf, sizeof(buf)) == 0U);
  assert(m3ds_hardware_profile_format(&n3ds, NULL, 16U) == 0U);
  assert(m3ds_hardware_profile_format(&n3ds, buf, 0U) == 0U);
}

int main(void) {
  test_old_3ds_models();
  test_new_3ds_models();
  test_unknown_and_format();
  printf("test_hardware_profile: all assertions passed.\n");
  return 0;
}
