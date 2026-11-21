#include "m3ds_stock_font.h"

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_STROKER_H
#include <stdio.h>
#include <stdlib.h>

struct M3DS_StockFont {
    FT_Library library;
    FT_Face faces[3];
    FT_Stroker stroker;
    FT_Glyph fill, border;
};

static bool open_face(M3DS_StockFont *font, M3DS_FontFace id, const char *directory, const char *name, int pixels) {
    char path[512];
    int length = snprintf(path, sizeof(path), "%s/%s", directory, name);
    if (length < 0 || (size_t)length >= sizeof(path)) return false;
    FT_Face *face = &font->faces[id];
    return FT_New_Face(font->library, path, 0, face) == 0
        && FT_Set_Pixel_Sizes(*face, 0, pixels) == 0;
}

M3DS_StockFont *m3ds_stock_font_open(const char *directory, bool japanese) {
    M3DS_StockFont *font = calloc(1, sizeof(*font));
    if (!font) return NULL;
    // v160.8 Fonts uses 18 px game fonts, a 30 px icon face and a rounded 2 px outline at UI scale 1.
    if (FT_Init_FreeType(&font->library)
            || !open_face(font, M3DS_FONT_DEFAULT, directory, "font.woff", 18)
            || (japanese && !open_face(font, M3DS_FONT_JAPANESE, directory, "font_jp.woff", 18))
            || !open_face(font, M3DS_FONT_ICON, directory, "icon.ttf", 30)
            || FT_Stroker_New(font->library, &font->stroker)) {
        m3ds_stock_font_close(font);
        return NULL;
    }
    FT_Stroker_Set(font->stroker, 2 * 64, FT_STROKER_LINECAP_ROUND, FT_STROKER_LINEJOIN_ROUND, 0);
    return font;
}

static void release_glyphs(M3DS_StockFont *font) {
    if (font->fill) FT_Done_Glyph(font->fill);
    if (font->border) FT_Done_Glyph(font->border);
    font->fill = font->border = NULL;
}

void m3ds_stock_font_close(M3DS_StockFont *font) {
    if (!font) return;
    release_glyphs(font);
    if (font->stroker) FT_Stroker_Done(font->stroker);
    for (size_t i = 0; i < sizeof(font->faces) / sizeof(font->faces[0]); ++i) {
        if (font->faces[i]) FT_Done_Face(font->faces[i]);
    }
    if (font->library) FT_Done_FreeType(font->library);
    free(font);
}

static bool render_mask(FT_Glyph *glyph, M3DS_GlyphMask *mask) {
    if (FT_Glyph_To_Bitmap(glyph, FT_RENDER_MODE_NORMAL, NULL, true)) return false;
    FT_BitmapGlyph bitmap = (FT_BitmapGlyph)*glyph;
    if (bitmap->bitmap.width && bitmap->bitmap.rows && bitmap->bitmap.pixel_mode != FT_PIXEL_MODE_GRAY) return false;
    *mask = (M3DS_GlyphMask){
        .pixels = bitmap->bitmap.buffer, .width = bitmap->bitmap.width, .height = bitmap->bitmap.rows,
        .pitch = bitmap->bitmap.pitch, .left = bitmap->left, .top = bitmap->top,
    };
    return true;
}

bool m3ds_stock_font_glyph(M3DS_StockFont *font, uint32_t codepoint, bool outline, M3DS_StockGlyph *glyph) {
    release_glyphs(font);
    *glyph = (M3DS_StockGlyph){0};
    M3DS_FontFace preference[] = {M3DS_FONT_JAPANESE, M3DS_FONT_DEFAULT};
    if (codepoint >= 0xe000 && codepoint <= 0xf8ff) preference[0] = M3DS_FONT_ICON;
    FT_Face face = NULL;
    for (size_t i = 0; i < sizeof(preference) / sizeof(preference[0]); ++i) {
        FT_Face candidate = font->faces[preference[i]];
        if (!candidate) continue;
        glyph->index = FT_Get_Char_Index(candidate, codepoint);
        if (glyph->index) {
            glyph->face = preference[i];
            face = candidate;
            break;
        }
    }
    // The default hinting mode in the pinned Arc FreeTypeFontGenerator is autoMedium.
    if (!face || FT_Load_Glyph(face, glyph->index, FT_LOAD_FORCE_AUTOHINT | FT_LOAD_TARGET_NORMAL)
            || FT_Get_Glyph(face->glyph, &font->fill)) return false;
    glyph->advance = (face->glyph->metrics.horiAdvance + 63) >> 6;
    if (!render_mask(&font->fill, &glyph->fill)) return false;
    if (outline && glyph->face != M3DS_FONT_ICON && glyph->fill.width && glyph->fill.height) {
        if (FT_Get_Glyph(face->glyph, &font->border)
                || FT_Glyph_StrokeBorder(&font->border, font->stroker, false, true)
                || !render_mask(&font->border, &glyph->border)) return false;
    }
    return true;
}

int32_t m3ds_stock_font_kerning(const M3DS_StockFont *font, const M3DS_StockGlyph *left, const M3DS_StockGlyph *right) {
    if (left->face != right->face || !left->index || !right->index) return 0;
    FT_Vector distance;
    if (FT_Get_Kerning(font->faces[left->face], left->index, right->index, FT_KERNING_DEFAULT, &distance)) return 0;
    return (distance.x + 63) >> 6;
}

void m3ds_stock_font_metrics(const M3DS_StockFont *font, int32_t *ascent, int32_t *line_height) {
    const FT_Size_Metrics *metrics = &font->faces[M3DS_FONT_DEFAULT]->size->metrics;
    *ascent = (metrics->ascender + 63) >> 6;
    *line_height = (metrics->height + 63) >> 6;
}
