/*
 * Copyright (C) 2009 Grigori Goronzy <greg@geekmind.org>
 *
 * This file is part of libass.
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

#ifndef LIBASS_PARSE_H
#define LIBASS_PARSE_H

#include "ass_render.h"

#define BLUR_MAX_RADIUS 100.0

#define _r(c)   ((c) >> 24)
#define _g(c)   (((c) >> 16) & 0xFF)
#define _b(c)   (((c) >> 8) & 0xFF)
#define _a(c)   ((c) & 0xFF)

enum ass_tag_id {
    TAG_UNKNOWN,
    TAG_XBORD,
    TAG_YBORD,
    TAG_XSHAD,
    TAG_YSHAD,
    TAG_FAX,
    TAG_FAY,
    TAG_ICLIP,
    TAG_BLUR,
    TAG_FSCX,
    TAG_FSCY,
    TAG_FSC,
    TAG_FSP,
    TAG_FS,
    TAG_BORD,
    TAG_MOVE,
    TAG_FRX,
    TAG_FRY,
    TAG_FRZ,
    TAG_FR,
    TAG_FN,
    TAG_ALPHA,
    TAG_AN,
    TAG_A,
    TAG_POS,
    TAG_FADE,
    TAG_FAD,
    TAG_ORG,
    TAG_T,
    TAG_CLIP,
    TAG_C,
    TAG_1C,
    TAG_2C,
    TAG_3C,
    TAG_4C,
    TAG_1A,
    TAG_2A,
    TAG_3A,
    TAG_4A,
    TAG_R,
    TAG_BE,
    TAG_B,
    TAG_I,
    TAG_KT,
    TAG_KF,
    TAG_CAPITAL_K,
    TAG_KO,
    TAG_K,
    TAG_SHAD,
    TAG_S,
    TAG_U,
    TAG_PBO,
    TAG_P,
    TAG_Q,
    TAG_FE,
};

typedef struct {
    size_t start, end;
    // Each argument's numeric kind depends only on its tag and argument count.
    union {
        double number;
        int32_t integer;
        uint32_t hex;
    } value;
    bool valid;
} ParsedTagArg;

typedef struct {
    size_t args, next;
    enum ass_tag_id id;
    unsigned char nargs;
    bool has_backslash_arg;
} ParsedTag;

typedef struct {
    size_t start, end, override_end;
    size_t first, last;
} ParsedTextRun;

typedef struct parsed_event {
    ParsedTag *tags;
    ParsedTagArg *args;
    ParsedTextRun *runs;
    size_t n_tags, max_tags, n_args, max_args, n_runs, max_runs;
    bool valid;
} ParsedEvent;

void ass_apply_tags(RenderContext *state, char *text, const ParsedEvent *parsed,
                    const ParsedTextRun *run);
size_t ass_parsed_event_construct(void *key, void *value, void *priv);
void ass_parsed_event_scratch_free(ParsedEvent *parsed);

void ass_update_font(RenderContext *state);
void ass_apply_transition_effects(RenderContext *state);
void ass_process_karaoke_effects(RenderContext *state);
unsigned ass_get_next_char(RenderContext *state, char **str);
char *ass_parse_tags(RenderContext *state, char *p, char *end, double pwr,
                     bool nested);
int ass_event_has_hard_overrides(char *str);
void ass_apply_fade(uint32_t *clr, int fade);


#endif /* LIBASS_PARSE_H */
