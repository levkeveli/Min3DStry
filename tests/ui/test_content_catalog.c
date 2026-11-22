#include "ui/content_catalog.h"

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

static const M3dsCatalogEntry *find_entry(M3dsCatalogCategory category, const char *id) {
    size_t count = m3ds_catalog_count(category);
    for (size_t i = 0; i < count; ++i) {
        const M3dsCatalogEntry *entry = m3ds_catalog_entry(category, i);
        if (entry && strcmp(entry->id, id) == 0) {
            return entry;
        }
    }
    return NULL;
}

static void test_counts_and_names(void) {
    CHECK(m3ds_catalog_count(M3DS_CATALOG_ITEMS) == 22u);
    CHECK(m3ds_catalog_count(M3DS_CATALOG_BLOCKS) == 258u);
    CHECK(strcmp(m3ds_catalog_category_name(M3DS_CATALOG_ITEMS), "Items") == 0);
    CHECK(strcmp(m3ds_catalog_category_name(M3DS_CATALOG_BLOCKS), "Blocks") == 0);
    CHECK(m3ds_catalog_entry(M3DS_CATALOG_ITEMS, 22u) == NULL);
    CHECK(m3ds_catalog_entry(M3DS_CATALOG_BLOCKS, 258u) == NULL);

    const M3dsCatalogEntry *copper = find_entry(M3DS_CATALOG_ITEMS, "copper");
    CHECK(copper != NULL);
    if (copper) {
        CHECK(strcmp(copper->name, "Copper") == 0);
    }
    const M3dsCatalogEntry *conveyor = find_entry(M3DS_CATALOG_BLOCKS, "conveyor");
    CHECK(conveyor != NULL);
    if (conveyor) {
        CHECK(strcmp(conveyor->name, "Conveyor") == 0);
        CHECK(strstr(conveyor->description, "Transports") != NULL);
        CHECK(strcmp(conveyor->category, "distribution") == 0);
    }
    const M3dsCatalogEntry *duo = find_entry(M3DS_CATALOG_BLOCKS, "duo");
    CHECK(duo != NULL);
    if (duo) {
        CHECK(strcmp(duo->category, "turret") == 0);
    }
    const M3dsCatalogEntry *copper_desc = find_entry(M3DS_CATALOG_ITEMS, "copper");
    CHECK(copper_desc != NULL);
    if (copper_desc) {
        CHECK(strstr(copper_desc->description, "construction") != NULL);
        CHECK(copper_desc->category[0] == '\0');
    }
}

static void test_filters(void) {
    CHECK(m3ds_catalog_filter_count(M3DS_CATALOG_ITEMS) == 1u);
    CHECK(m3ds_catalog_filter_count(M3DS_CATALOG_BLOCKS) > 1u);
    CHECK(strcmp(m3ds_catalog_filter_name(M3DS_CATALOG_ITEMS, 0u), "All") == 0);
    CHECK(strcmp(m3ds_catalog_filter_name(M3DS_CATALOG_BLOCKS, 0u), "All") == 0);
    CHECK(m3ds_catalog_filtered_count(M3DS_CATALOG_BLOCKS, 0u) == 258u);

    size_t filters = m3ds_catalog_filter_count(M3DS_CATALOG_BLOCKS);
    size_t sum = 0u;
    for (size_t filter = 1u; filter < filters; ++filter) {
        const char *name =
            m3ds_catalog_filter_name(M3DS_CATALOG_BLOCKS, filter);
        CHECK(name[0] != '\0');
        size_t count =
            m3ds_catalog_filtered_count(M3DS_CATALOG_BLOCKS, filter);
        CHECK(count > 0u);
        sum += count;
        for (size_t i = 0; i < count; ++i) {
            const M3dsCatalogEntry *entry = m3ds_catalog_filtered_entry(
                M3DS_CATALOG_BLOCKS, filter, i);
            CHECK(entry != NULL);
            if (entry) {
                CHECK(strcmp(entry->category, name) == 0);
            }
        }
        CHECK(m3ds_catalog_filtered_entry(M3DS_CATALOG_BLOCKS, filter, count) ==
              NULL);
    }
    CHECK(sum == m3ds_catalog_count(M3DS_CATALOG_BLOCKS));

    const M3dsCatalogEntry *first_distribution = m3ds_catalog_filtered_entry(
        M3DS_CATALOG_BLOCKS, 1u, 0u);
    CHECK(first_distribution != NULL);
    CHECK(m3ds_catalog_filtered_count(M3DS_CATALOG_BLOCKS, 99u) == 0u);
    CHECK(m3ds_catalog_filtered_entry(M3DS_CATALOG_BLOCKS, 99u, 0u) == NULL);
}

static void test_wrap(void) {
    char out[64];
    size_t lines = m3ds_catalog_wrap("one two three four", 7u, out, sizeof(out));
    CHECK(lines == 3u);
    CHECK(strcmp(out, "one two\nthree\nfour") == 0);

    CHECK(m3ds_catalog_wrap("", 10u, out, sizeof(out)) == 0u);
    CHECK(out[0] == '\0');

    lines = m3ds_catalog_wrap("abcdefghij", 4u, out, sizeof(out));
    CHECK(lines == 3u);
    CHECK(strcmp(out, "abcd\nefgh\nij") == 0);

    lines = m3ds_catalog_wrap("short", 20u, out, sizeof(out));
    CHECK(lines == 1u);
    CHECK(strcmp(out, "short") == 0);
}

static void test_sorted_and_nonempty(void) {
    for (int category = 0; category < 2; ++category) {
        M3dsCatalogCategory cat = (M3dsCatalogCategory)category;
        size_t count = m3ds_catalog_count(cat);
        CHECK(count > 0u);
        for (size_t i = 0; i < count; ++i) {
            const M3dsCatalogEntry *entry = m3ds_catalog_entry(cat, i);
            CHECK(entry != NULL);
            if (!entry) {
                continue;
            }
            CHECK(entry->id[0] != '\0');
            CHECK(entry->name[0] != '\0');
            if (i > 0u) {
                const M3dsCatalogEntry *prev = m3ds_catalog_entry(cat, i - 1u);
                CHECK(lower_compare(prev->name, entry->name) <= 0);
            }
        }
    }
}

int main(void) {
    test_counts_and_names();
    test_sorted_and_nonempty();
    test_filters();
    test_wrap();
    if (failures != 0) {
        printf("CONTENT_CATALOG FAIL failures=%d\n", failures);
        return 1;
    }
    printf("CONTENT_CATALOG PASS\n");
    return 0;
}
