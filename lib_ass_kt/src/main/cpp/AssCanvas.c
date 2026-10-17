#include "AssCanvas.h"
#include <string.h>

// Alpha products are coverage * opacity, both 0-255.
#define ALPHA_ONE (255 * 255)
#define ALPHA_HALF (ALPHA_ONE / 2)

static inline int max_int(int a, int b) { return a > b ? a : b; }
static inline int min_int(int a, int b) { return a < b ? a : b; }

CanvasBox canvas_box(const ASS_Image *images, int width, int height) {
    int x0 = width, y0 = height, x1 = 0, y1 = 0;
    for (const ASS_Image *img = images; img; img = img->next) {
        if (img->w <= 0 || img->h <= 0 || (img->color & 0xFF) == 0xFF) continue;
        int left = max_int(img->dst_x, 0), right = min_int(img->dst_x + img->w, width);
        int top = max_int(img->dst_y, 0), bottom = min_int(img->dst_y + img->h, height);
        if (right <= left || bottom <= top) continue;
        x0 = min_int(x0, left);
        y0 = min_int(y0, top);
        x1 = max_int(x1, right);
        y1 = max_int(y1, bottom);
    }
    if (x1 <= x0 || y1 <= y0) return (CanvasBox) { 0, 0, 0, 0 };
    return (CanvasBox) { x0, y0, x1 - x0, y1 - y0 };
}

void canvas_blend(const ASS_Image *images, CanvasBox box, uint8_t *rgba) {
    for (const ASS_Image *img = images; img; img = img->next) {
        unsigned opacity = 0xFF - (img->color & 0xFF);
        if (img->w <= 0 || img->h <= 0 || opacity == 0) continue;
        unsigned color[3] = { img->color >> 24, (img->color >> 16) & 0xFF, (img->color >> 8) & 0xFF };
        int x0 = max_int(img->dst_x, box.x), x1 = min_int(img->dst_x + img->w, box.x + box.w);
        int y0 = max_int(img->dst_y, box.y), y1 = min_int(img->dst_y + img->h, box.y + box.h);
        if (x1 <= x0 || y1 <= y0) continue;
        for (int y = y0; y < y1; y++) {
            const uint8_t *src = img->bitmap + (y - img->dst_y) * img->stride + (x0 - img->dst_x);
            uint8_t *dst = rgba + ((size_t) (y - box.y) * box.w + (x0 - box.x)) * 4;
            for (int x = x0; x < x1; x++, src++, dst += 4) {
                if (!*src) continue;
                // One division per channel, so alpha is rounded a single time.
                unsigned a = *src * opacity;
                unsigned keep = ALPHA_ONE - a;
                dst[0] = (color[0] * a + dst[0] * keep + ALPHA_HALF) / ALPHA_ONE;
                dst[1] = (color[1] * a + dst[1] * keep + ALPHA_HALF) / ALPHA_ONE;
                dst[2] = (color[2] * a + dst[2] * keep + ALPHA_HALF) / ALPHA_ONE;
                dst[3] = (255 * a + dst[3] * keep + ALPHA_HALF) / ALPHA_ONE;
            }
        }
    }
}

int canvas_into(const ASS_Image *images, CanvasBox box, int changed, int force,
                uint8_t *rgba, size_t capacity) {
    uint64_t required = (uint64_t) box.w * box.h * 4;
    if (required > capacity || (required && !rgba)) return -1;
    size_t size = (size_t) required;
    if (changed == 0 && !force) return 0;
    if (size) {
        memset(rgba, 0, size);
        canvas_blend(images, box, rgba);
    }
    return 1;
}
