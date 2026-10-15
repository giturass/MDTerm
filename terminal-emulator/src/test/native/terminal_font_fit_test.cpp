#include "terminal_font_fit.h"

#include <hb-ft.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <memory>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

// Unlike assert(), these checks also run in the CI Release build.
#define CHECK(condition)                                                       \
    do {                                                                       \
        if (!(condition))                                                      \
            throw std::runtime_error(std::string(__FILE__) + ":" +             \
                                     std::to_string(__LINE__) +                \
                                     ": " #condition);                         \
    } while (false)

constexpr FT_Fixed identity = termux::FONT_SCALE_IDENTITY;
using Codepoints = std::vector<uint32_t>;

struct Font {
    FT_Face ft = nullptr;
    hb_font_t *hb = nullptr;

    Font(FT_Library library, const char *path, unsigned int pixels) {
        CHECK(FT_New_Face(library, path, 0, &ft) == 0);
        CHECK(FT_Set_Pixel_Sizes(ft, 0, pixels) == 0);
        hb = hb_ft_font_create_referenced(ft);
        CHECK(hb != hb_font_get_empty());
        hb_font_set_scale(hb, static_cast<int>(pixels * 64),
                          static_cast<int>(pixels * 64));
    }
    ~Font() {
        hb_font_destroy(hb);
        FT_Done_Face(ft);
    }
    Font(const Font &) = delete;
    Font &operator=(const Font &) = delete;
};

struct Run {
    std::vector<hb_codepoint_t> glyphs;
    std::vector<hb_glyph_position_t> positions;

    FT_Pos advance() const {
        return std::accumulate(positions.begin(), positions.end(), FT_Pos{0},
                               [](FT_Pos sum, const hb_glyph_position_t &p) {
                                   return sum + p.x_advance;
                               });
    }
};

Run shape(Font &font, const Codepoints &codepoints) {
    std::unique_ptr<hb_buffer_t, decltype(&hb_buffer_destroy)> buffer(
        hb_buffer_create(), hb_buffer_destroy);
    hb_buffer_add_utf32(buffer.get(), codepoints.data(),
                        static_cast<int>(codepoints.size()), 0, -1);
    hb_buffer_guess_segment_properties(buffer.get());
    hb_shape(font.hb, buffer.get(), nullptr, 0);
    unsigned int count = 0;
    const hb_glyph_info_t *infos =
        hb_buffer_get_glyph_infos(buffer.get(), &count);
    const hb_glyph_position_t *positions =
        hb_buffer_get_glyph_positions(buffer.get(), &count);
    CHECK(count > 0);
    Run result;
    result.positions.assign(positions, positions + count);
    for (unsigned int i = 0; i < count; ++i) {
        CHECK(infos[i].codepoint !=
              0); // A missing fixture glyph must fail the test.
        result.glyphs.push_back(infos[i].codepoint);
    }
    return result;
}

void same_run(const Run &a, const Run &b) {
    CHECK(a.glyphs == b.glyphs);
    CHECK(a.positions.size() == b.positions.size());
    for (size_t i = 0; i < a.positions.size(); ++i) {
        CHECK(a.positions[i].x_advance == b.positions[i].x_advance);
        CHECK(a.positions[i].y_advance == b.positions[i].y_advance);
        CHECK(a.positions[i].x_offset == b.positions[i].x_offset);
        CHECK(a.positions[i].y_offset == b.positions[i].y_offset);
    }
}

uint32_t cell_width(Font &primary) {
    const FT_UInt glyph = FT_Get_Char_Index(primary.ft, 'M');
    CHECK(glyph != 0);
    CHECK(FT_Load_Glyph(primary.ft, glyph, FT_LOAD_DEFAULT) == 0);
    return std::max<uint32_t>(1, (primary.ft->glyph->advance.x + 63) / 64);
}

FT_Fixed scale_for(const Codepoints &codepoints, uint32_t width, const Run &run,
                   uint32_t columns = 2) {
    return termux::cjk_horizontal_scale(
        codepoints, width, columns, run.positions.data(),
        static_cast<unsigned int>(run.positions.size()));
}

struct Raster {
    std::vector<FT_Vector> points;
    FT_BBox box{};
    FT_Vector advance{};
    int left = 0;
    int top = 0;
    unsigned int width = 0;
    unsigned int rows = 0;
    std::vector<uint8_t> pixels;

    uint64_t coverage() const {
        return std::accumulate(pixels.begin(), pixels.end(), uint64_t{0});
    }
};

Raster rasterize(Font &font, hb_codepoint_t glyph, FT_Fixed scale = identity,
                 FT_Pos origin_x = 0) {
    CHECK(FT_Load_Glyph(font.ft, glyph,
                        FT_LOAD_DEFAULT | FT_LOAD_COLOR | FT_LOAD_NO_BITMAP) ==
          0);
    FT_GlyphSlot slot = font.ft->glyph;
    CHECK(slot->format == FT_GLYPH_FORMAT_OUTLINE);
    CHECK(termux::fit_cjk_glyph_outline(slot, scale, origin_x) ==
          (scale != identity));
    Raster result;
    if (slot->outline.n_points > 0)
        result.points.assign(slot->outline.points,
                             slot->outline.points + slot->outline.n_points);
    FT_Outline_Get_CBox(&slot->outline, &result.box);
    result.advance = slot->advance;
    CHECK(FT_Render_Glyph(slot, FT_RENDER_MODE_NORMAL) == 0);
    result.left = slot->bitmap_left;
    result.top = slot->bitmap_top;
    result.width = slot->bitmap.width;
    result.rows = slot->bitmap.rows;
    CHECK(slot->bitmap.pixel_mode == FT_PIXEL_MODE_GRAY || result.rows == 0);
    for (unsigned int row = 0; row < result.rows; ++row) {
        const uint8_t *start =
            slot->bitmap.buffer + row * std::abs(slot->bitmap.pitch);
        result.pixels.insert(result.pixels.end(), start, start + result.width);
    }
    // A rendered bitmap cannot be transformed as an outline.
    CHECK(!termux::fit_cjk_glyph_outline(slot, 2 * identity, 17));
    return result;
}

void same_vertical_geometry(const Raster &a, const Raster &b) {
    CHECK(a.points.size() == b.points.size());
    for (size_t i = 0; i < a.points.size(); ++i)
        CHECK(a.points[i].y == b.points[i].y);
    CHECK(a.box.yMin == b.box.yMin);
    CHECK(a.box.yMax == b.box.yMax);
    CHECK(a.top == b.top);
    CHECK(a.rows == b.rows);
    CHECK(a.advance.x == b.advance.x);
    CHECK(a.advance.y == b.advance.y);
}

void same_raster(const Raster &a, const Raster &b) {
    same_vertical_geometry(a, b);
    for (size_t i = 0; i < a.points.size(); ++i)
        CHECK(a.points[i].x == b.points[i].x);
    CHECK(a.left == b.left);
    CHECK(a.width == b.width);
    CHECK(a.pixels == b.pixels);
}

void classification_and_guards() {
    const std::vector<Codepoints> cjk = {
        {0x4e2d},         {0x20000},       {0x304b, 0x3099}, {0x30ab},
        {0xac00},         {0x3105},        {0xa000},         {0x3000},
        {0x3001},         {0xff0c},        {0xfe35},         {0xff21},
        {0x309b},         {0x309c},        {0x30fc},         {0x4e2d, 0xe0100},
        {0x4e2d, 0xfe00}, {0x4e2d, 0x0301}};
    const std::vector<Codepoints> unchanged = {{},
                                               {'A'},
                                               {' '},
                                               {0x3099},
                                               {0x1f600},
                                               {0x1f1e8, 0x1f1f3},
                                               {0x1f469, 0x200d, 0x1f4bb},
                                               {'1', 0xfe0f, 0x20e3},
                                               {0x3030, 0xfe0e},
                                               {0x303d},
                                               {0x3297},
                                               {0x3299},
                                               {0x4e2d, 0xfe0f},
                                               {0x4e2d, 'A'},
                                               {0x4e2d, 0x200d}};
    hb_glyph_position_t position{};
    position.x_advance = 16 * 64;
    for (const auto &cp : cjk) {
        CHECK(termux::is_cjk_grapheme(cp));
        CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 1) > identity);
    }
    for (const auto &cp : unchanged) {
        CHECK(!termux::is_cjk_grapheme(cp));
        CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 1) ==
              identity);
    }
    const Codepoints cp{0x4e2d};
    CHECK(termux::cjk_horizontal_scale(cp, 12, 1, &position, 1) == identity);
    CHECK(termux::cjk_horizontal_scale({0xff76}, 12, 1, &position, 1) ==
          identity);
    CHECK(termux::cjk_horizontal_scale(cp, 12, 0, &position, 1) == identity);
    CHECK(termux::cjk_horizontal_scale(cp, 0, 2, &position, 1) == identity);
    CHECK(termux::cjk_horizontal_scale(cp, 12, 2, nullptr, 1) == identity);
    CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 0) == identity);
    position.x_advance = 24 * 64;
    CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 1) == identity);
    position.x_advance = 0;
    CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 1) == identity);
    position.x_advance = -64;
    CHECK(termux::cjk_horizontal_scale(cp, 12, 2, &position, 1) == identity);
    CHECK(!termux::fit_cjk_glyph_outline(nullptr, 2 * identity, 0));
}

void fit_font(FT_Library library, const char *primary_path,
              const char *fallback_path, uint32_t cp,
              unsigned int primary_pixels, unsigned int fallback_pixels) {
    std::cout << "Fit U+" << std::hex << cp << std::dec << " at "
              << primary_pixels << '/' << fallback_pixels << " px\n";
    Font primary(library, primary_path, primary_pixels);
    Font fallback(library, fallback_path, fallback_pixels);
    const uint32_t width = cell_width(primary);
    const Run run = shape(fallback, {cp});
    const Run ascii = shape(fallback, {'A'});
    CHECK(run.glyphs.size() == 1);
    const Raster original = rasterize(fallback, run.glyphs[0]);
    const Raster original_ascii = rasterize(fallback, ascii.glyphs[0]);
    const FT_Fixed scale = scale_for({cp}, width, run);
    CHECK(scale > 0 && scale != identity);
    const double fitted_advance =
        static_cast<double>(run.advance()) * scale / identity;
    CHECK(std::abs(fitted_advance - width * 2.0 * 64) <= 1.0);
    const Raster fitted = rasterize(fallback, run.glyphs[0], scale);
    same_vertical_geometry(original, fitted);
    CHECK(original.coverage() > 0);
    if (primary_pixels == fallback_pixels) {
        CHECK(scale > identity);
        CHECK(fitted.width > original.width);
        CHECK(fitted.coverage() > original.coverage());
    } else {
        CHECK(scale < identity);
        CHECK(fitted.width < original.width);
        CHECK(fitted.coverage() < original.coverage());
    }
    // Reloading a slot and reusing hb-ft must retain the original metrics and
    // pixels.
    same_raster(original, rasterize(fallback, run.glyphs[0]));
    same_run(run, shape(fallback, {cp}));
    same_run(ascii, shape(fallback, {'A'}));
    CHECK(scale_for({'A'}, width, ascii) == identity);
    same_raster(original_ascii, rasterize(fallback, ascii.glyphs[0],
                                          scale_for({'A'}, width, ascii)));
    CHECK(scale_for({cp}, width, run, 1) == identity);
    same_raster(original, rasterize(fallback, run.glyphs[0],
                                    scale_for({cp}, width, run, 1)));
}

void punctuation(FT_Library library, const char *primary_path,
                 const char *mplus_path) {
    Font primary(library, primary_path, 32);
    Font fallback(library, mplus_path, 32);
    const uint32_t width = cell_width(primary);
    for (uint32_t cp : {0x3001u, 0x3002u, 0xff0cu}) {
        const Run run = shape(fallback, {cp});
        CHECK(run.glyphs.size() == 1);
        const Raster original = rasterize(fallback, run.glyphs[0]);
        const Raster fitted =
            rasterize(fallback, run.glyphs[0], scale_for({cp}, width, run));
        same_vertical_geometry(original, fitted);
        CHECK(fitted.coverage() > original.coverage());
        // A comma/full stop must keep its wide right bearing, not fill two
        // cells with ink.
        CHECK(fitted.width < width);
        CHECK(fitted.left + static_cast<int>(fitted.width) <
              static_cast<int>(width));
    }
    const Run space = shape(fallback, {0x3000});
    CHECK(space.glyphs.size() == 1 && space.advance() > 0);
    const FT_Fixed scale = scale_for({0x3000}, width, space);
    CHECK(scale > identity);
    const Raster blank = rasterize(fallback, space.glyphs[0], scale);
    CHECK(blank.points.empty() && blank.pixels.empty());
    CHECK(blank.width == 0 && blank.rows == 0);
}

void check_origin(Font &font, hb_codepoint_t glyph, FT_Fixed scale,
                  FT_Pos origin) {
    const Raster at_zero = rasterize(font, glyph, scale);
    const Raster shifted = rasterize(font, glyph, scale, origin);
    same_vertical_geometry(at_zero, shifted);
    const FT_Pos shift =
        std::llround(static_cast<double>(origin) * scale / identity);
    for (size_t i = 0; i < at_zero.points.size(); ++i)
        CHECK(shifted.points[i].x - at_zero.points[i].x == shift);
    CHECK(shifted.left ==
          static_cast<int>(std::floor(shifted.box.xMin / 64.0)));
    if (shift % 64 == 0) {
        CHECK(shifted.left - at_zero.left == shift / 64);
        CHECK(shifted.pixels == at_zero.pixels);
    }
}

void origins_and_graphemes(FT_Library library, const char *primary_path,
                           const char *mplus_path) {
    Font primary(library, primary_path, 32);
    Font fallback(library, mplus_path, 32);
    const uint32_t width = cell_width(primary);
    const Run han = shape(fallback, {0x4e2d});
    const FT_Fixed han_scale = scale_for({0x4e2d}, width, han);
    CHECK(han_scale > identity);
    for (FT_Pos origin : {-65L, -33L, -1L, 1L, 17L, 63L, 65L})
        check_origin(fallback, han.glyphs[0], han_scale, origin);
    check_origin(fallback, han.glyphs[0], han_scale, han.advance());
    check_origin(fallback, han.glyphs[0], han_scale, -han.advance());
    const Raster zero = rasterize(fallback, han.glyphs[0], han_scale);
    const Raster fractional =
        rasterize(fallback, han.glyphs[0], han_scale, -33);
    CHECK(zero.pixels !=
          fractional.pixels); // Subpixel origins reach the rasterizer.

    // Canonical composition and the fixture's ka + handakuten GSUB ligature.
    // A + handakuten has neither a precomposed scalar nor a ligature in the
    // pinned Mplus font, so it also exercises a real multi-glyph grapheme.
    same_run(shape(fallback, {0x304b, 0x3099}), shape(fallback, {0x304c}));
    for (const Codepoints &cp :
         {Codepoints{0x304b, 0x3099}, Codepoints{0x304b, 0x309a},
          Codepoints{0x3042, 0x309a}}) {
        const Run run = shape(fallback, cp);
        const FT_Fixed scale = scale_for(cp, width, run);
        CHECK(scale > 0 && scale != identity);
        CHECK(std::abs(static_cast<double>(run.advance()) * scale / identity -
                       width * 128.0) <= 1.0);
        if (cp.front() == 0x3042) {
            CHECK(run.glyphs.size() >= 2);
            // This fixture's mark can have its own advance. Fit the complete
            // shaped run rather than assuming every combining glyph is
            // zero-width.
        }
        FT_Pos pen_x = 0;
        for (size_t i = 0; i < run.glyphs.size(); ++i) {
            check_origin(fallback, run.glyphs[i], scale,
                         pen_x + run.positions[i].x_offset);
            same_vertical_geometry(
                rasterize(fallback, run.glyphs[i]),
                rasterize(fallback, run.glyphs[i], scale,
                          pen_x + run.positions[i].x_offset));
            pen_x += run.positions[i].x_advance;
        }
        same_run(run, shape(fallback, cp));
    }
}

} // namespace

int main(int argc, char **argv) {
    FT_Library library = nullptr;
    try {
        CHECK(argc == 4);
        CHECK(FT_Init_FreeType(&library) == 0);
        classification_and_guards();
        for (unsigned int size : {12u, 17u, 24u, 40u}) {
            fit_font(library, argv[1], argv[2], 0x4c2e, size, size);
            fit_font(library, argv[1], argv[3], 0x4e2d, size, size);
        }
        // A wider fallback must also fit into a narrower primary font's
        // measured cells.
        fit_font(library, argv[1], argv[2], 0x4c2e, 16, 32);
        punctuation(library, argv[1], argv[3]);
        origins_and_graphemes(library, argv[1], argv[3]);
        FT_Done_FreeType(library);
        std::cout << "CJK font fitting tests passed\n";
        return 0;
    } catch (const std::exception &error) {
        if (library) FT_Done_FreeType(library);
        std::cerr << error.what() << '\n';
        return 1;
    }
}
