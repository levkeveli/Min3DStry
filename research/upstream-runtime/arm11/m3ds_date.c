#include <stdint.h>
#include <time.h>

static time_t m3ds_timegm(const struct tm* date) {
    int64_t year = INT64_C(1900) + date->tm_year;
    int64_t month = date->tm_mon;
    year += month / 12;
    month %= 12;
    if (month < 0) {
        month += 12;
        year--;
    }

    // Convert to days since the Unix epoch using Howard Hinnant's civil-date formula.
    year -= month < 2;
    int64_t era = (year >= 0 ? year : year - 399) / 400;
    int64_t year_in_era = year - era * 400;
    int64_t month_from_march = month < 2 ? month + 10 : month - 2;
    int64_t days = era * 146097 + year_in_era * 365 + year_in_era / 4 - year_in_era / 100
        + (153 * month_from_march + 2) / 5 + date->tm_mday - 1 - 719468;
    return (time_t)(days * 86400 + (int64_t)date->tm_hour * 3600
        + (int64_t)date->tm_min * 60 + date->tm_sec);
}

#define timegm m3ds_timegm
#include "date.c"
#undef timegm
