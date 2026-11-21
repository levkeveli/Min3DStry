#include "m3ds_stock_font.h"
#include "m3ds_stock_text.h"

#include <assert.h>
#include <stdio.h>

static uint32_t mask_hash(const M3DS_GlyphMask *mask) {
    uint32_t hash = 2166136261u;
    for (int y = 0; y < mask->height; ++y) {
        const uint8_t *row = mask->pixels + y * mask->pitch;
        for (int x = 0; x < mask->width; ++x) hash = (hash ^ row[x]) * 16777619u;
    }
    return hash;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "Usage: %s <stock fonts directory>\n", argv[0]);
        return 2;
    }
    assert(m3ds_stock_text_texel(0x60, 0x80) == 0x60606080u);
    assert(m3ds_stock_text_texel(0xff, 0x40) == 0xffffff40u);
    M3DS_StockFont *base = m3ds_stock_font_open(argv[1], false);
    M3DS_StockFont *japanese = m3ds_stock_font_open(argv[1], true);
    assert(base && japanese);
    M3DS_StockGlyph glyph, other;
    assert(m3ds_stock_font_glyph(base, 'A', false, &glyph));
    assert(glyph.face == M3DS_FONT_DEFAULT && glyph.advance > 0);
    assert(glyph.fill.width > 0 && glyph.fill.height > 0 && !glyph.border.pixels);
    uint32_t latin = mask_hash(&glyph.fill);

    assert(m3ds_stock_font_glyph(japanese, 'A', true, &glyph));
    assert(glyph.face == M3DS_FONT_DEFAULT && latin == mask_hash(&glyph.fill));
    assert(glyph.border.width > glyph.fill.width && glyph.border.height > glyph.fill.height);
    assert(m3ds_stock_font_glyph(japanese, ' ', true, &glyph));
    assert(glyph.advance > 0 && !glyph.fill.width && !glyph.border.width);

    assert(m3ds_stock_font_glyph(base, 0x63a1, false, &other)); // 採
    assert(m3ds_stock_font_glyph(japanese, 0x63a1, false, &glyph));
    assert(other.face == M3DS_FONT_DEFAULT && glyph.face == M3DS_FONT_JAPANESE);
    assert(m3ds_stock_font_kerning(japanese, &glyph, &other) == 0);
    uint32_t kanji = mask_hash(&glyph.fill);

    assert(m3ds_stock_font_glyph(japanese, 0xe875, false, &glyph)); // Stock Iconc.tree
    assert(glyph.face == M3DS_FONT_ICON && glyph.fill.width > 0 && glyph.fill.height > 0);
    uint32_t tree = mask_hash(&glyph.fill);
    assert(!m3ds_stock_font_glyph(base, 0x10fffd, false, &glyph));
    assert(m3ds_stock_font_glyph(base, 'A', false, &glyph));
    assert(latin == mask_hash(&glyph.fill));
    m3ds_stock_font_close(japanese);
    m3ds_stock_font_close(base);

    printf("UPSTREAM_STOCK_FONT latin=%08x japanese=%08x tree=%08x outline=ok fallback=ok texel=ok\n",
        latin, kanji, tree);
    return 0;
}
