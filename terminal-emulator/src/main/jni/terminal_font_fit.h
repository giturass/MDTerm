#ifndef TERMUX_TERMINAL_FONT_FIT_H
#define TERMUX_TERMINAL_FONT_FIT_H

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_OUTLINE_H
#include <hb.h>

#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace termux {

constexpr FT_Fixed FONT_SCALE_IDENTITY = 0x10000L;

// The caller supplies one terminal cell's complete grapheme. Use HarfBuzz's
// Unicode scripts so supplementary-plane ideographs and their variants work
// with the same Unicode data as shaping.
inline bool is_cjk_grapheme(const std::vector<uint32_t> &codepoints) {
    hb_unicode_funcs_t *unicode = hb_unicode_funcs_get_default();
    bool has_base = false;
    for (uint32_t cp : codepoints) {
        // Emoji presentation, keycaps and the emoji-capable CJK symbols must
        // keep the renderer's existing emoji sizing, including text variants.
        if (cp == 0xfe0f || cp == 0x20e3 || cp == 0x3030 || cp == 0x303d ||
            cp == 0x3297 || cp == 0x3299)
            return false;

        switch (hb_unicode_general_category(unicode, cp)) {
            case HB_UNICODE_GENERAL_CATEGORY_NON_SPACING_MARK:
            case HB_UNICODE_GENERAL_CATEGORY_SPACING_MARK:
            case HB_UNICODE_GENERAL_CATEGORY_ENCLOSING_MARK:
                continue;
            default:
                break;
        }

        switch (hb_unicode_script(unicode, cp)) {
            case HB_SCRIPT_HAN:
            case HB_SCRIPT_HIRAGANA:
            case HB_SCRIPT_KATAKANA:
            case HB_SCRIPT_HANGUL:
            case HB_SCRIPT_BOPOMOFO:
            case HB_SCRIPT_YI:
                has_base = true;
                continue;
            default:
                break;
        }

        // Fullwidth punctuation/forms and kana sound marks often have the
        // Common script rather than their Script_Extensions values.
        if (cp == 0x309b || cp == 0x309c || cp == 0x30fc ||
            (cp >= 0x3000 && cp <= 0x303f) || (cp >= 0xfe10 && cp <= 0xfe19) ||
            (cp >= 0xfe30 && cp <= 0xfe6f) || (cp >= 0xff01 && cp <= 0xff60) ||
            (cp >= 0xffe0 && cp <= 0xffe6)) {
            has_base = true;
            continue;
        }
        // This also excludes joiners, emoji modifiers and tag sequences.
        return false;
    }
    return has_base;
}

inline FT_Fixed cjk_horizontal_scale(const std::vector<uint32_t> &codepoints,
                                     uint32_t cell_width, uint32_t cell_columns,
                                     const hb_glyph_position_t *positions,
                                     unsigned int count) {
    if (cell_columns != 2 || cell_width == 0 || !positions || count == 0 ||
        !is_cjk_grapheme(codepoints))
        return FONT_SCALE_IDENTITY;

    int64_t advance = 0;
    for (unsigned int i = 0; i < count; ++i)
        advance += positions[i].x_advance;
    if (advance <= 0) return FONT_SCALE_IDENTITY;

    // Fit the layout advance, not the ink bounding box: punctuation and
    // normal side bearings must retain their intended share of the cell.
    double fixed_scale = static_cast<double>(cell_width) * cell_columns * 64.0 *
                         FONT_SCALE_IDENTITY / static_cast<double>(advance);
    if (!std::isfinite(fixed_scale) || fixed_scale < 1 ||
        fixed_scale > std::numeric_limits<int32_t>::max())
        return FONT_SCALE_IDENTITY;
    return static_cast<FT_Fixed>(std::llround(fixed_scale));
}

// origin_x is the unscaled HarfBuzz pen + glyph offset, in 26.6 pixels.
// Transform only the freshly loaded slot, never the shared FT_Face: changing
// the face transform can affect later shaping and hb-ft's cached advances.
inline bool fit_cjk_glyph_outline(FT_GlyphSlot glyph, FT_Fixed scale,
                                  FT_Pos origin_x) {
    if (!glyph || glyph->format != FT_GLYPH_FORMAT_OUTLINE || scale <= 0 ||
        scale == FONT_SCALE_IDENTITY)
        return false;
    const FT_Matrix transform{scale, 0, 0, FONT_SCALE_IDENTITY};
    FT_Outline_Transform(&glyph->outline, &transform);
    FT_Outline_Translate(&glyph->outline, FT_MulFix(origin_x, scale), 0);
    return true;
}

} // namespace termux

#endif
