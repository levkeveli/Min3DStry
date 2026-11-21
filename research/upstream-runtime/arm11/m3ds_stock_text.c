#include "m3ds_stock_text.h"
#include "m3ds_stock_font.h"

#include <3ds.h>
#include <3ds/util/utf.h>
#include <citro2d.h>
#include <math.h>
#include <string.h>

enum { atlas_size = 512, cell = 32, columns = atlas_size / cell, slots = columns * columns };
static C3D_Tex atlas;
static M3DS_StockFont *font;
static uint32_t frame;
static int32_t ascent, line_height;
static uint8_t coverage[256];
static bool dirty;

typedef struct {
    uint32_t codepoint, touched;
    bool outline;
    M3DS_StockGlyph glyph;
    Tex3DS_SubTexture region;
    int32_t left, top;
} GlyphSlot;

static GlyphSlot cache[slots];

bool m3ds_stock_text_open(bool japanese) {
    if (font) return true;
    font = m3ds_stock_font_open("romfs:/fonts", japanese);
    if (!font) return false;
    if (!C3D_TexInit(&atlas, atlas_size, atlas_size, GPU_RGBA8)) {
        m3ds_stock_font_close(font);
        font = NULL;
        return false;
    }
    C3D_TexSetFilter(&atlas, GPU_NEAREST, GPU_NEAREST);
    m3ds_stock_font_metrics(font, &ascent, &line_height);
    for (int i = 0; i < 256; ++i) coverage[i] = (uint8_t)lroundf(255.0f * powf(i / 255.0f, 1.8f));
    return true;
}

void m3ds_stock_text_close(void) {
    if (!font) return;
    C3D_TexDelete(&atlas);
    m3ds_stock_font_close(font);
    font = NULL;
    memset(cache, 0, sizeof(cache));
}

void m3ds_stock_text_begin(void) {
    // C3D_FrameBegin(C3D_FRAME_SYNCDRAW) has finished the preceding GPU frame.
    ++frame;
}

static uint8_t sample(const M3DS_GlyphMask *mask, int x, int y) {
    x -= mask->left;
    y += mask->top;
    if (x < 0 || y < 0 || x >= mask->width || y >= mask->height) return 0;
    return coverage[mask->pixels[y * mask->pitch + x]];
}

static uint32_t texel_offset(int x, int y) {
    // The same 8x8 tile layout and RGBA8 byte order as UpstreamSpriteAtlas.writeTexture.
    unsigned morton = 0;
    for (int bit = 0; bit < 3; ++bit) {
        morton |= ((x >> bit) & 1) << (2 * bit);
        morton |= ((y >> bit) & 1) << (2 * bit + 1);
    }
    return ((y / 8) * (atlas_size / 8) + x / 8) * 64 + morton;
}

static bool upload_glyph(GlyphSlot *entry, int index, const M3DS_StockGlyph *glyph, bool outline) {
    const M3DS_GlyphMask *fill = &glyph->fill;
    const M3DS_GlyphMask *border = &glyph->border;
    const M3DS_GlyphMask *bounds = border->pixels ? border : fill;
    int shadow = !outline && glyph->face != M3DS_FONT_ICON ? 2 : 0;
    int width = bounds->width, height = bounds->height + (bounds->height ? shadow : 0);
    if (width > cell || height > cell) return false;
    int start_x = (index % columns) * cell, start_y = (index / columns) * cell;
    uint32_t *pixels = atlas.data;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int gx = bounds->left + x, gy = y - bounds->top;
            unsigned face = sample(fill, gx, gy);
            // Arc draws the face twice on top of its darkGray border or two-pixel shadow.
            face += (face * (255 - face) + 127) / 255;
            unsigned dark = border->pixels ? sample(border, gx, gy) : shadow ? sample(fill, gx, gy - shadow) : 0;
            dark = (dark * (255 - face) + 127) / 255;
            unsigned alpha = face + dark;
            unsigned rgb = alpha ? (255 * face + 63 * dark + alpha / 2) / alpha : 0;
            pixels[texel_offset(start_x + x, start_y + y)] = rgb * 0x01010100u | alpha;
        }
    }
    entry->left = bounds->left;
    entry->top = bounds->top;
    entry->region = (Tex3DS_SubTexture){
        .width = width, .height = height,
        .left = start_x / (float)atlas_size, .top = 1.0f - start_y / (float)atlas_size,
        .right = (start_x + width) / (float)atlas_size, .bottom = 1.0f - (start_y + height) / (float)atlas_size,
    };
    dirty |= width > 0 && height > 0;
    return true;
}

static int slot_for(uint32_t codepoint, bool outline) {
    int available = -1;
    for (int i = 0; i < slots; ++i) {
        GlyphSlot *entry = &cache[i];
        if (entry->touched && entry->codepoint == codepoint && entry->outline == outline) {
            entry->touched = frame;
            return i;
        }
        if (entry->touched != frame && (available < 0 || entry->touched < cache[available].touched)) available = i;
    }
    if (available < 0) return -1;
    M3DS_StockGlyph glyph;
    if (!m3ds_stock_font_glyph(font, codepoint, outline, &glyph)
            && !m3ds_stock_font_glyph(font, '?', outline, &glyph)) return -1;
    GlyphSlot *entry = &cache[available];
    if (!upload_glyph(entry, available, &glyph, outline)) return -1;
    entry->glyph = (M3DS_StockGlyph){.face = glyph.face, .index = glyph.index, .advance = glyph.advance};
    entry->codepoint = codepoint;
    entry->outline = outline;
    entry->touched = frame;
    return available;
}

bool m3ds_stock_text_layout(M3DS_StockText *layout, const char *text, bool outline) {
    if (!font) return false;
    layout->glyphs = layout->width = 0;
    layout->lines = 1;
    int previous = -1, x = 0;
    while (*text) {
        uint32_t codepoint;
        ssize_t bytes = decode_utf8(&codepoint, (const uint8_t *)text);
        if (bytes <= 0) return false;
        text += bytes;
        if (codepoint == '\n') {
            if (layout->lines == M3DS_STOCK_TEXT_GLYPHS) return false;
            layout->widths[layout->lines - 1] = x;
            if (x > layout->width) layout->width = x;
            x = 0;
            previous = -1;
            ++layout->lines;
            continue;
        }
        if (layout->glyphs == M3DS_STOCK_TEXT_GLYPHS) return false;
        int slot = slot_for(codepoint, outline);
        if (slot < 0) return false;
        if (previous >= 0) x += m3ds_stock_font_kerning(font, &cache[previous].glyph, &cache[slot].glyph);
        layout->placements[layout->glyphs].slot = slot;
        layout->placements[layout->glyphs].line = layout->lines - 1;
        layout->placements[layout->glyphs].x = x;
        ++layout->glyphs;
        previous = slot;
        x += cache[slot].glyph.advance;
    }
    layout->widths[layout->lines - 1] = x;
    if (x > layout->width) layout->width = x;
    layout->height = layout->lines * line_height;
    return true;
}

void m3ds_stock_text_draw(const M3DS_StockText *layout, float x, float y, float sx, float sy, int32_t align) {
    if (dirty) {
        C3D_TexFlush(&atlas);
        dirty = false;
    }
    for (int i = 0; i < layout->glyphs; ++i) {
        const GlyphSlot *entry = &cache[layout->placements[i].slot];
        if (!entry->region.width || !entry->region.height) continue;
        int line = layout->placements[i].line;
        float offset = (layout->width - layout->widths[line]) * (1 + align) * 0.5f;
        float gx = x + (offset + layout->placements[i].x + entry->left) * sx;
        float gy = y + (ascent + line * line_height - entry->top) * sy;
        C2D_Image image = {&atlas, &entry->region};
        C2D_DrawImageAt(image, gx, gy, 0.5f, NULL, sx, sy);
    }
}
