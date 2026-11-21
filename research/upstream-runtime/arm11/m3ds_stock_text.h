#pragma once

#include <stdbool.h>
#include <stdint.h>

#define M3DS_STOCK_TEXT_GLYPHS 512
#define M3DS_STOCK_TEXT_FIELDS 7

/* Java supplies codepoint, ABGR color, atlas page, x, y, width and height. */
typedef void (*M3DS_StockArtDraw)(const int32_t *glyph, float x, float y, float width, float height);

typedef struct {
    struct {
        uint16_t slot, line;
        int32_t x;
        const int32_t *glyph;
    } placements[M3DS_STOCK_TEXT_GLYPHS];
    int32_t widths[M3DS_STOCK_TEXT_GLYPHS];
    int32_t glyphs, lines, width, height;
} M3DS_StockText;

/* ARM11 GPU_RGBA8 texture bytes are A, B, G, R in increasing address order. */
static inline uint32_t m3ds_stock_text_texel(uint8_t gray, uint8_t alpha) {
    return (uint32_t)gray << 24 | (uint32_t)gray << 16 | (uint32_t)gray << 8 | alpha;
}

bool m3ds_stock_text_open(bool japanese);
void m3ds_stock_text_close(void);
void m3ds_stock_text_begin(void);
bool m3ds_stock_text_layout(M3DS_StockText *layout, const int32_t *characters, int32_t count, bool outline);
void m3ds_stock_text_draw(const M3DS_StockText *layout, float x, float y, float sx, float sy,
                          int32_t align, M3DS_StockArtDraw draw_art);
