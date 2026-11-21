#include "m3ds_stock_text.h"
#include "m3ds_stock_font.h"

#include <3ds.h>
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
static unsigned raster_budget;

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
    // FreeType builds the face-wide auto-hinter tables on first use. Do that
    // here while loading graphics, before the live client's socket is open.
    M3DS_StockGlyph prime;
    if (!m3ds_stock_font_glyph(font, 'A', true, &prime)
            || !m3ds_stock_font_glyph(font, 0xe875, false, &prime)
            || (japanese && !m3ds_stock_font_glyph(font, 0x63a1, true, &prime))
            || !C3D_TexInit(&atlas, atlas_size, atlas_size, GPU_RGBA8)) {
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
    // Cold outline glyphs can individually be expensive on ARM11. Let the
    // live loop send input and keepalives between batches of new glyphs.
    raster_budget = 2;
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
    if (available < 0 || raster_budget == 0) return -1;
    --raster_budget;
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

bool m3ds_stock_text_layout(M3DS_StockText *layout, const int32_t *characters, int32_t count, bool outline) {
    if (!font || count < 1 || count > M3DS_STOCK_TEXT_GLYPHS) return false;
    layout->glyphs = layout->width = 0;
    layout->lines = 1;
    int previous = -1, x = 0;
    for (int i = 0; i < count; ++i) {
        const int32_t *glyph = characters + i * M3DS_STOCK_TEXT_FIELDS;
        uint32_t codepoint = glyph[0];
        if (codepoint == '\n') {
            if (layout->lines == M3DS_STOCK_TEXT_GLYPHS) return false;
            layout->widths[layout->lines - 1] = x;
            if (x > layout->width) layout->width = x;
            x = 0;
            previous = -1;
            ++layout->lines;
            continue;
        }
        int slot = glyph[2] < 0 ? slot_for(codepoint, outline) : slots;
        if (slot < 0) return false;
        if (previous >= 0 && slot != slots) x += m3ds_stock_font_kerning(font, &cache[previous].glyph, &cache[slot].glyph);
        layout->placements[layout->glyphs].slot = slot;
        layout->placements[layout->glyphs].line = layout->lines - 1;
        layout->placements[layout->glyphs].x = x;
        layout->placements[layout->glyphs].glyph = glyph;
        ++layout->glyphs;
        previous = slot == slots ? -1 : slot;
        x += slot == slots ? line_height : cache[slot].glyph.advance;
    }
    layout->widths[layout->lines - 1] = x;
    if (x > layout->width) layout->width = x;
    layout->height = layout->lines * line_height;
    return true;
}

void m3ds_stock_text_draw(const M3DS_StockText *layout, float x, float y, float sx, float sy,
                          int32_t align, M3DS_StockArtDraw draw_art) {
    if (dirty) {
        C3D_TexFlush(&atlas);
        dirty = false;
    }
    for (int i = 0; i < layout->glyphs; ++i) {
        const int32_t *glyph = layout->placements[i].glyph;
        uint16_t slot = layout->placements[i].slot;
        int line = layout->placements[i].line;
        float offset = (layout->width - layout->widths[line]) * (1 + align) * 0.5f;
        float gx = x + (offset + layout->placements[i].x) * sx;
        float gy = y + (ascent + line * line_height) * sy;
        if (slot == slots) {
            float image_scale = line_height / fmaxf(glyph[5], glyph[6]);
            float width = glyph[5] * image_scale, height = glyph[6] * image_scale;
            draw_art(glyph, gx + (line_height - width) * sx * 0.5f,
                gy - (line_height + height) * sy * 0.5f, width * sx, height * sy);
            continue;
        }
        const GlyphSlot *entry = &cache[slot];
        if (!entry->region.width || !entry->region.height) continue;
        C2D_Image image = {&atlas, &entry->region};
        C2D_ImageTint tint;
        C2D_PlainImageTint(&tint, (uint32_t)glyph[1], 1.0f);
        C2D_DrawImageAt(image, gx + entry->left * sx, gy - entry->top * sy, 0.5f, &tint, sx, sy);
    }
}
