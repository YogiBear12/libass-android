#ifndef ASS_CANVAS_H
#define ASS_CANVAS_H

#include <stdint.h>
#include <stddef.h>
#include "ass/ass.h"

typedef struct {
    int x, y, w, h;
} CanvasBox;

// The box covering every image, clipped to width x height; w or h is 0 when nothing shows.
CanvasBox canvas_box(const ASS_Image *images, int width, int height);

// Blends images back to front into rgba: premultiplied RGBA, box.w x box.h, rows from the top,
// zeroed by the caller.
void canvas_blend(const ASS_Image *images, CanvasBox box, uint8_t *rgba);

// Returns -1 for insufficient storage, 0 for unchanged, or 1 after clearing and blending.
int canvas_into(const ASS_Image *images, CanvasBox box, int changed, int force,
                uint8_t *rgba, size_t capacity);

#endif
