#pragma once

#include <stdbool.h>
#include <stdint.h>

#define M3DS_STOCK_TEXT_GLYPHS 512

typedef struct {
    struct {
        uint16_t slot, line;
        int32_t x;
    } placements[M3DS_STOCK_TEXT_GLYPHS];
    int32_t widths[M3DS_STOCK_TEXT_GLYPHS];
    int32_t glyphs, lines, width, height;
} M3DS_StockText;

bool m3ds_stock_text_open(bool japanese);
void m3ds_stock_text_close(void);
void m3ds_stock_text_begin(void);
bool m3ds_stock_text_layout(M3DS_StockText *layout, const char *text, bool outline);
void m3ds_stock_text_draw(const M3DS_StockText *layout, float x, float y, float sx, float sy, int32_t align);
