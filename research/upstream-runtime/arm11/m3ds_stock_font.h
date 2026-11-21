#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef struct M3DS_StockFont M3DS_StockFont;

typedef enum {
    M3DS_FONT_DEFAULT,
    M3DS_FONT_JAPANESE,
    M3DS_FONT_ICON,
} M3DS_FontFace;

typedef struct {
    const uint8_t *pixels;
    int32_t width, height, pitch;
    int32_t left, top;
} M3DS_GlyphMask;

typedef struct {
    M3DS_GlyphMask fill, border;
    M3DS_FontFace face;
    uint32_t index;
    int32_t advance;
} M3DS_StockGlyph;

/* The Japanese face is loaded only for the upstream Japanese locale. */
M3DS_StockFont *m3ds_stock_font_open(const char *directory, bool japanese);
void m3ds_stock_font_close(M3DS_StockFont *font);

/* Mask pixels are borrowed until the next glyph request on this font. */
bool m3ds_stock_font_glyph(M3DS_StockFont *font, uint32_t codepoint, bool outline, M3DS_StockGlyph *glyph);
int32_t m3ds_stock_font_kerning(const M3DS_StockFont *font, const M3DS_StockGlyph *left, const M3DS_StockGlyph *right);
void m3ds_stock_font_metrics(const M3DS_StockFont *font, int32_t *ascent, int32_t *line_height);
