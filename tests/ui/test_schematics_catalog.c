#include "ui/schematics_catalog.h"

#include <ctype.h>
#include <stdio.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond)                                                        \
    do {                                                                   \
        if (!(cond)) {                                                     \
            printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);         \
            ++failures;                                                    \
        }                                                                  \
    } while (0)

static int lower_compare(const char *a, const char *b) {
    while (*a && *b) {
        int ca = tolower((unsigned char)*a);
        int cb = tolower((unsigned char)*b);
        if (ca != cb) {
            return ca - cb;
        }
        ++a;
        ++b;
    }
    return (unsigned char)*a - (unsigned char)*b;
}

static const M3dsSchematicRow *find_row(const char *id) {
    size_t count = m3ds_schematic_count();
    for (size_t i = 0; i < count; ++i) {
        const M3dsSchematicRow *row = m3ds_schematic_at(i);
        if (row && strcmp(row->id, id) == 0) {
            return row;
        }
    }
    return NULL;
}

static void test_counts_and_known_entries(void) {
    CHECK(m3ds_schematic_count() == 210u);
    CHECK(m3ds_schematic_at(210u) == NULL);

    const M3dsSchematicRow *power = find_row("a single large power node");
    CHECK(power != NULL);
    if (power) {
        CHECK(power->width == 2u);
        CHECK(power->height == 2u);
        CHECK(power->blocks == 1u);
        CHECK(strstr(power->summary, "power-node-large") != NULL);
    }
    const M3dsSchematicRow *battery = find_row("battery node");
    CHECK(battery != NULL);
    if (battery) {
        CHECK(battery->width == 5u);
        CHECK(battery->height == 3u);
        CHECK(battery->blocks == 2u);
    }
}

static int digit_first(const char *text) {
    return isdigit((unsigned char)text[0]) ? 1 : 0;
}

static void test_sorted_and_nonempty(void) {
    size_t count = m3ds_schematic_count();
    CHECK(count > 0u);
    for (size_t i = 0; i < count; ++i) {
        const M3dsSchematicRow *row = m3ds_schematic_at(i);
        CHECK(row != NULL);
        if (!row) {
            continue;
        }
        CHECK(row->id[0] != '\0');
        CHECK(row->name[0] != '\0');
        CHECK(row->width > 0u);
        CHECK(row->height > 0u);
        CHECK(row->blocks > 0u);
        CHECK(row->summary[0] != '\0');
        if (i > 0u) {
            const M3dsSchematicRow *prev = m3ds_schematic_at(i - 1u);
            int prev_digit = digit_first(prev->name);
            int row_digit = digit_first(row->name);
            CHECK(prev_digit <= row_digit);
            if (prev_digit == row_digit) {
                CHECK(lower_compare(prev->name, row->name) <= 0);
            }
        }
    }
}

int main(void) {
    test_counts_and_known_entries();
    test_sorted_and_nonempty();
    if (failures != 0) {
        printf("SCHEMATICS_CATALOG FAIL failures=%d\n", failures);
        return 1;
    }
    printf("SCHEMATICS_CATALOG PASS\n");
    return 0;
}
