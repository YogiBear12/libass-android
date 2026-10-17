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

#include "config.h"
#include "ass_compat.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "ass_library.h"
#include "ass_render.h"
#include "ass_parse.h"

#define PARSED_EVENT_INITIAL_CAPACITY 16

#define MAX_VALID_NARGS 7
#define MAX_BE 127
#define NBSP 0xa0   // unicode non-breaking space character

struct arg {
    char *start, *end;
    ParsedTagArg *cached;
};

static inline int32_t argtoi32(struct arg arg)
{
    if (arg.cached && arg.cached->valid)
        return arg.cached->value.integer;
    int32_t value;
    mystrtoi32(&arg.start, 10, &value);
    if (arg.cached) {
        arg.cached->value.integer = value;
        arg.cached->valid = true;
    }
    return value;
}

static inline double argtod(struct arg arg)
{
    if (arg.cached && arg.cached->valid)
        return arg.cached->value.number;
    double value;
    mystrtod(&arg.start, &value);
    if (arg.cached) {
        arg.cached->value.number = value;
        arg.cached->valid = true;
    }
    return value;
}

static inline void push_arg(struct arg *args, int *nargs, char *start, char *end)
{
    if (*nargs <= MAX_VALID_NARGS) {
        rskip_spaces(&end, start);
        if (end > start) {
            args[*nargs] = (struct arg) { .start = start, .end = end };
            ++*nargs;
        }
    }
}

/**
 * \brief Check if starting part of (*p) matches sample.
 * If true, shift p to the first symbol after the matching part.
 */
static inline int mystrcmp(char **p, const char *sample)
{
    char *p2;
    for (p2 = *p; *sample != 0 && *p2 == *sample; p2++, sample++)
        ;
    if (*sample == 0) {
        *p = p2;
        return 1;
    }
    return 0;
}

/**
 * \brief Change current font, using setting from render_priv->state.
 */
void ass_update_font(RenderContext *state)
{
    unsigned val;
    ASS_FontDesc desc;

    desc.family = state->family;
    if (!desc.family.str)
        return;
    if (desc.family.len && desc.family.str[0] == '@') {
        desc.vertical = 1;
        desc.family.str++;
        desc.family.len--;
    } else {
        desc.vertical = 0;
    }

    val = state->bold;
    // 0 = normal, 1 = bold, >1 = exact weight
    if (val == 1 || val == -1)
        val = 700;               // bold
    else if (val <= 0)
        val = 400;               // normal
    desc.bold = val;

    val = state->italic;
    if (val == 1)
        val = 100;              // italic
    else if (val <= 0)
        val = 0;                // normal
    desc.italic = val;

    state->font = ass_font_new(state->renderer, &desc);
}

/**
 * \brief Convert double to int32_t without UB
 * on out-of-range values; match x86 behavior
 */
static inline int32_t dtoi32(double val)
{
    if (ass_isnan(val) || val <= INT32_MIN || val >= INT32_MAX + 1LL)
        return INT32_MIN;
    return val;
}

static double calc_anim(double new, double old, double pwr)
{
   return (1 - pwr) * old + new * pwr;
}

static int32_t calc_anim_int32(uint32_t new, uint32_t old, double pwr)
{
    return dtoi32(calc_anim(new, old, pwr));
}

/**
 * \brief Calculate a weighted average of two colors
 * calculates c1*(1-a) + c2*a, but separately for each component except alpha
 */
static void change_color(uint32_t *var, uint32_t new, double pwr)
{
    uint32_t co = ass_bswap32(*var);
    uint32_t cn = ass_bswap32(new);

    uint32_t cc = (calc_anim_int32(cn & 0xff0000, co & 0xff0000, pwr) & 0xff0000) |
                  (calc_anim_int32(cn & 0x00ff00, co & 0x00ff00, pwr) & 0x00ff00) |
                  (calc_anim_int32(cn & 0x0000ff, co & 0x0000ff, pwr) & 0x0000ff);

    (*var) = (ass_bswap32(cc & 0xffffff)) | _a(*var);
}

// like change_color, but for alpha component only
static inline void change_alpha(uint32_t *var, int32_t new, double pwr)
{
    *var = (*var & 0xFFFFFF00) | (uint8_t)calc_anim_int32(new, _a(*var), pwr);
}

/**
 * \brief Multiply two alpha values
 * \param a first value
 * \param b second value
 * \return result of multiplication
 * At least one of the parameters must be less than or equal to 0xFF.
 * The result is less than or equal to max(a, b, 0xFF).
 */
static inline uint32_t mult_alpha(uint32_t a, uint32_t b)
{
    return a - ((uint64_t) a * b + 0x7F) / 0xFF + b;
}

void ass_apply_fade(uint32_t *clr, int fade)
{
    // VSFilter compatibility: apply fade only when it's positive
    if (fade > 0)
        change_alpha(clr, mult_alpha(_a(*clr), fade), 1);
}

/**
 * \brief Calculate alpha value by piecewise linear function
 * Used for \fad, \fade implementation.
 */
static int
interpolate_alpha(long long now, int32_t t1, int32_t t2, int32_t t3,
                  int32_t t4, int a1, int a2, int a3)
{
    int a;
    double cf;

    if (now < t1) {
        a = a1;
    } else if (now < t2) {
        cf = ((double) (int32_t) ((uint32_t) now - t1)) /
                (int32_t) ((uint32_t) t2 - t1);
        a = a1 * (1 - cf) + a2 * cf;
    } else if (now < t3) {
        a = a2;
    } else if (now < t4) {
        cf = ((double) (int32_t) ((uint32_t) now - t3)) /
                (int32_t) ((uint32_t) t4 - t3);
        a = a2 * (1 - cf) + a3 * cf;
    } else {                    // now >= t4
        a = a3;
    }

    return a;
}

/**
 * Parse a vector clip into an outline, using the proper scaling
 * parameters.  Translate it to correct for screen borders, if needed.
 */
static bool parse_vector_clip(RenderContext *state,
                              struct arg *args, int nargs)
{
    if (nargs != 1 && nargs != 2)
        return false;

    int scale = 1;
    if (nargs == 2)
        scale = argtoi32(args[0]);

    struct arg text = args[nargs - 1];
    state->clip_drawing_text.str = text.start;
    state->clip_drawing_text.len = text.end - text.start;
    state->clip_drawing_scale = scale;
    return true;
}

static int32_t parse_alpha_tag(struct arg arg)
{
    if (arg.cached && arg.cached->valid)
        return arg.cached->value.hex;
    char *str = arg.start;
    int32_t alpha = 0;

    while (*str == '&' || *str == 'H')
        ++str;

    mystrtoi32(&str, 16, &alpha);
    if (arg.cached) {
        arg.cached->value.hex = alpha;
        arg.cached->valid = true;
    }
    return alpha;
}

static uint32_t parse_color_tag(struct arg arg)
{
    if (arg.cached && arg.cached->valid)
        return arg.cached->value.hex;
    char *str = arg.start;
    int32_t color = 0;

    while (*str == '&' || *str == 'H')
        ++str;

    mystrtoi32(&str, 16, &color);
    uint32_t value = ass_bswap32((uint32_t) color);
    if (arg.cached) {
        arg.cached->value.hex = value;
        arg.cached->valid = true;
    }
    return value;
}

/**
 * \brief find style by name as in \r
 * \param track track
 * \param name style name
 * \param len style name length
 * \return style in track->styles
 * Returns NULL if no style has the given name.
 */
static ASS_Style *lookup_style_strict(ASS_Track *track, char *name, size_t len)
{
    int i;
    for (i = track->n_styles - 1; i >= 0; --i) {
        if (strncmp(track->styles[i].Name, name, len) == 0 &&
            track->styles[i].Name[len] == '\0')
            return track->styles + i;
    }
    ass_msg(track->library, MSGL_WARN,
            "[%p]: Warning: no style named '%.*s' found",
            track, (int) len, name);
    return NULL;
}

static const struct {
    const char *name;
    enum ass_tag_id id;
    bool complex;
} tag_names[] = {
    { "xbord", TAG_XBORD, false },
    { "ybord", TAG_YBORD, false },
    { "xshad", TAG_XSHAD, false },
    { "yshad", TAG_YSHAD, false },
    { "fax", TAG_FAX, false },
    { "fay", TAG_FAY, false },
    { "iclip", TAG_ICLIP, true },
    { "blur", TAG_BLUR, false },
    { "fscx", TAG_FSCX, false },
    { "fscy", TAG_FSCY, false },
    { "fsc", TAG_FSC, false },
    { "fsp", TAG_FSP, false },
    { "fs", TAG_FS, false },
    { "bord", TAG_BORD, false },
    { "move", TAG_MOVE, true },
    { "frx", TAG_FRX, false },
    { "fry", TAG_FRY, false },
    { "frz", TAG_FRZ, false },
    { "fr", TAG_FR, false },
    { "fn", TAG_FN, false },
    { "alpha", TAG_ALPHA, false },
    { "an", TAG_AN, false },
    { "a", TAG_A, false },
    { "pos", TAG_POS, true },
    { "fade", TAG_FADE, true },
    { "fad", TAG_FAD, true },
    { "org", TAG_ORG, true },
    { "t", TAG_T, true },
    { "clip", TAG_CLIP, true },
    { "c", TAG_C, false },
    { "1c", TAG_1C, false },
    { "2c", TAG_2C, false },
    { "3c", TAG_3C, false },
    { "4c", TAG_4C, false },
    { "1a", TAG_1A, false },
    { "2a", TAG_2A, false },
    { "3a", TAG_3A, false },
    { "4a", TAG_4A, false },
    { "r", TAG_R, false },
    { "be", TAG_BE, false },
    { "b", TAG_B, false },
    { "i", TAG_I, false },
    { "kt", TAG_KT, false },
    { "kf", TAG_KF, false },
    { "K", TAG_CAPITAL_K, false },
    { "ko", TAG_KO, false },
    { "k", TAG_K, false },
    { "shad", TAG_SHAD, false },
    { "s", TAG_S, false },
    { "u", TAG_U, false },
    { "pbo", TAG_PBO, false },
    { "p", TAG_P, false },
    { "q", TAG_Q, false },
    { "fe", TAG_FE, false },
};

static bool lex_tag(char **cursor, char **after, char *end,
                    struct arg *args, int *nargs, bool *has_backslash_arg,
                    enum ass_tag_id *id)
{
    char *p = *cursor;
    while (p < end) {
        while (*p != '\\' && p != end)
            ++p;
        if (*p != '\\') {
            *cursor = p;
            return false;
        }
        ++p;
        if (p != end)
            skip_spaces(&p);

        char *q = p;
        while (*q != '(' && *q != '\\' && q != end)
            ++q;
        if (q == p)
            continue;

        char *name_end = q;

        // Store one extra element to be able to detect excess arguments
        *nargs = 0;
        *has_backslash_arg = false;
        for (int i = 0; i <= MAX_VALID_NARGS; ++i)
            args[i] = (struct arg) { .start = "", .end = "" };

        // Split parenthesized arguments. Do this for all tags and before
        // any non-parenthesized argument because that's what VSFilter does.
        if (*q == '(') {
            ++q;
            while (1) {
                if (q != end)
                    skip_spaces(&q);

                // Split on commas. If there is a backslash, ignore any
                // commas following it and lump everything starting from
                // the last comma, through the backslash and all the way
                // to the end of the argument string into a single argument.

                char *r = q;
                while (*r != ',' && *r != '\\' && *r != ')' && r != end)
                    ++r;

                if (*r == ',') {
                    push_arg(args, nargs, q, r);
                    q = r + 1;
                } else {
                    // Swallow the rest of the parenthesized string. This could
                    // be either a backslash-argument or simply the last argument.
                    if (*r == '\\') {
                        *has_backslash_arg = true;
                        char *paren = memchr(r, ')', end - r);
                        if (paren)
                            r = paren;
                        else
                            r = end;
                    }
                    push_arg(args, nargs, q, r);
                    q = r;
                    // The closing parenthesis could be missing.
                    if (q != end)
                        ++q;
                    break;
                }
            }
        }

        *id = TAG_UNKNOWN;
        for (size_t i = 0; i < sizeof(tag_names) / sizeof(tag_names[0]); i++) {
            if (mystrcmp(&p, tag_names[i].name)) {
                *id = tag_names[i].id;
                if (!tag_names[i].complex)
                    push_arg(args, nargs, p, name_end);
                break;
            }
        }
        *cursor = p;
        *after = q;
        return true;
    }
    *cursor = p;
    return false;
}

static bool lex_tags(ParsedEvent *parsed, char *text, char *p, char *end,
                     bool *drawing)
{
    size_t tail_parent = SIZE_MAX;
    char *q;
    while (p < end) {
        struct arg args[MAX_VALID_NARGS + 1];
        int nargs;
        bool has_backslash_arg;
        enum ass_tag_id id;
        if (!lex_tag(&p, &q, end, args, &nargs, &has_backslash_arg, &id))
            break;
        if (parsed->n_tags == parsed->max_tags) {
            size_t count = parsed->max_tags ? parsed->max_tags * 2 : PARSED_EVENT_INITIAL_CAPACITY;
            if (count < parsed->max_tags || !ASS_REALLOC_ARRAY(parsed->tags, count))
                return false;
            parsed->max_tags = count;
        }
        if (parsed->n_args + nargs > parsed->max_args) {
            size_t count = parsed->max_args ? parsed->max_args * 2 : PARSED_EVENT_INITIAL_CAPACITY;
            if (count < parsed->max_args || !ASS_REALLOC_ARRAY(parsed->args, count))
                return false;
            parsed->max_args = count;
        }
        size_t index = parsed->n_tags++;
        parsed->tags[index] = (ParsedTag) {
            .id = id, .args = parsed->n_args, .nargs = nargs,
            .has_backslash_arg = has_backslash_arg, .next = index + 1,
        };
        for (int i = 0; i < nargs; i++) {
            parsed->args[parsed->n_args++] = (ParsedTagArg) {
                .start = args[i].start - text, .end = args[i].end - text,
            };
        }
        if (id == TAG_P)
            *drawing = argtoi32(*args) > 0;
        int cnt = nargs - 1;
        if (id == TAG_T && cnt >= 0 && cnt <= 3 && has_backslash_arg) {
            if (args[cnt].end < end) {
                if (!lex_tags(parsed, text, args[cnt].start, args[cnt].end, drawing))
                    return false;
                parsed->tags[index].next = parsed->n_tags;
            } else {
                // Tail transforms share the enclosing range and must not recurse.
                parsed->tags[index].next = tail_parent;
                tail_parent = index;
                q = args[cnt].start;
            }
        }
        p = q;
    }
    while (tail_parent != SIZE_MAX) {
        size_t parent = parsed->tags[tail_parent].next;
        parsed->tags[tail_parent].next = parsed->n_tags;
        tail_parent = parent;
    }
    return true;
}

void ass_parsed_event_scratch_free(ParsedEvent *parsed)
{
    if (!parsed)
        return;
    free(parsed->tags);
    free(parsed->args);
    free(parsed->runs);
    free(parsed);
}

ParsedEvent *ass_lex_event(ASS_Renderer *renderer, char *text, size_t len)
{
    if (!renderer->parsed_event_scratch)
        renderer->parsed_event_scratch = calloc(1, sizeof(ParsedEvent));
    ParsedEvent *parsed = renderer->parsed_event_scratch;
    if (!parsed)
        return NULL;
    parsed->n_tags = parsed->n_args = parsed->n_runs = 0;
    bool drawing = false;
    char *p = text, *close = strchr(text, '}');
    while (*p) {
        if (parsed->n_runs == parsed->max_runs) {
            size_t count = parsed->max_runs ? parsed->max_runs * 2 : PARSED_EVENT_INITIAL_CAPACITY;
            if (count < parsed->max_runs || !ASS_REALLOC_ARRAY(parsed->runs, count))
                return NULL;
            parsed->max_runs = count;
        }
        ParsedTextRun run = {
            .start = p - text, .override_end = SIZE_MAX,
            .first = parsed->n_tags,
        };
        char *q = p;
        if (*p == '{') {
            if (close && close < p)
                close = strchr(p, '}');
            if (close) {
                run.override_end = close - text;
                if (!lex_tags(parsed, text, p, close, &drawing))
                    return NULL;
                q = close + 1;
            } else
                q++;
        }
        while (q < text + len && *q != '{') {
            if (!drawing && *q == '\\' && (q[1] == '{' || q[1] == '}'))
                q++;
            q++;
        }
        run.end = q - text;
        run.last = parsed->n_tags;
        parsed->runs[parsed->n_runs++] = run;
        p = q;
    }
    return parsed;
}

size_t ass_parsed_event_construct(void *key, void *value, void *priv)
{
    ParsedEventKey *k = key;
    ParsedEvent *result = value;
    *result = (ParsedEvent) {0};
    ParsedEvent *parsed = ass_lex_event(priv, (char *) k->text.str, k->text.len);
    if (!parsed)
        goto fail;
    size_t tags_size = parsed->n_tags * sizeof(*parsed->tags);
    size_t args_size = parsed->n_args * sizeof(*parsed->args);
    size_t runs_size = parsed->n_runs * sizeof(*parsed->runs);
    size_t args_offset = ass_align(_Alignof(ParsedTagArg), tags_size);
    if (args_offset < tags_size || args_offset > SIZE_MAX - args_size)
        goto fail;
    size_t runs_offset = ass_align(_Alignof(ParsedTextRun), args_offset + args_size);
    if (runs_offset < args_offset + args_size || runs_offset > SIZE_MAX - runs_size)
        goto fail;
    size_t size = runs_offset + runs_size;
    if (size) {
        char *block = malloc(size);
        if (!block)
            goto fail;
        *result = (ParsedEvent) {
            .tags = (ParsedTag *) block,
            .args = (ParsedTagArg *) (block + args_offset),
            .runs = (ParsedTextRun *) (block + runs_offset),
            .n_tags = parsed->n_tags, .max_tags = parsed->n_tags,
            .n_args = parsed->n_args, .max_args = parsed->n_args,
            .n_runs = parsed->n_runs, .max_runs = parsed->n_runs,
        };
        if (tags_size)
            memcpy(result->tags, parsed->tags, tags_size);
        if (args_size)
            memcpy(result->args, parsed->args, args_size);
        if (runs_size)
            memcpy(result->runs, parsed->runs, runs_size);
    }
    return sizeof(*k) + sizeof(*result) + k->text.len + 1 + size;

fail:
    return sizeof(*k) + sizeof(*result) + k->text.len + 1;
}

static char *apply_tags(RenderContext *state, char *text, char *p, char *end,
                        double pwr, bool nested, const ParsedEvent *parsed,
                        size_t first, size_t last)
{
    ASS_Renderer *render_priv = state->renderer;
    size_t next = first;
    char *q = p;
    while (next < last) {
        struct arg args[MAX_VALID_NARGS + 1];
        size_t current = next;
        const ParsedTag *record = &parsed->tags[current];
        next = record->next;
        enum ass_tag_id id = record->id;
        int nargs = record->nargs;
        bool has_backslash_arg = record->has_backslash_arg;
        q = end;
        args[0] = (struct arg) { .start = "", .end = "" };
        for (int i = 0; i < nargs; i++) {
            ParsedTagArg *arg = &parsed->args[record->args + i];
            args[i] = (struct arg) {
                .start = text + arg->start, .end = text + arg->end,
                .cached = arg,
            };
        }
        // New tags introduced in vsfilter 2.39
        switch (id) {
        case TAG_XBORD:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                val = state->border_x * (1 - pwr) + val * pwr;
                val = (val < 0) ? 0 : val;
            } else
                val = state->style->Outline;
            state->border_x = val;
            break;
        }
        case TAG_YBORD:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                val = state->border_y * (1 - pwr) + val * pwr;
                val = (val < 0) ? 0 : val;
            } else
                val = state->style->Outline;
            state->border_y = val;
            break;
        }
        case TAG_XSHAD:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                val = state->shadow_x * (1 - pwr) + val * pwr;
            } else
                val = state->style->Shadow;
            state->shadow_x = val;
            break;
        }
        case TAG_YSHAD:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                val = state->shadow_y * (1 - pwr) + val * pwr;
            } else
                val = state->style->Shadow;
            state->shadow_y = val;
            break;
        }
        case TAG_FAX:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->fax =
                    val * pwr + state->fax * (1 - pwr);
            } else
                state->fax = 0.;
            break;
        }
        case TAG_FAY:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->fay =
                    val * pwr + state->fay * (1 - pwr);
            } else
                state->fay = 0.;
            break;
        }
        case TAG_ICLIP:
        {
            if (nargs == 4) {
                int32_t x0, y0, x1, y1;
                x0 = argtoi32(args[0]);
                y0 = argtoi32(args[1]);
                x1 = argtoi32(args[2]);
                y1 = argtoi32(args[3]);
                state->clip_x0 =
                    state->clip_x0 * (1 - pwr) + x0 * pwr;
                state->clip_x1 =
                    state->clip_x1 * (1 - pwr) + x1 * pwr;
                state->clip_y0 =
                    state->clip_y0 * (1 - pwr) + y0 * pwr;
                state->clip_y1 =
                    state->clip_y1 * (1 - pwr) + y1 * pwr;
                state->clip_mode = 1;
            } else if (!state->clip_drawing_text.str) {
                if (parse_vector_clip(state, args, nargs))
                    state->clip_drawing_mode = 1;
            }
            break;
        }
        case TAG_BLUR:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                val = state->blur * (1 - pwr) + val * pwr;
                val = (val < 0) ? 0 : val;
                val = (val > BLUR_MAX_RADIUS) ? BLUR_MAX_RADIUS : val;
                state->blur = val;
            } else
                state->blur = 0.0;
            // ASS standard tags
            break;
        }
        case TAG_FSCX:
        {
            double val;
            if (nargs) {
                val = argtod(*args) / 100;
                val = state->scale_x * (1 - pwr) + val * pwr;
                val = (val < 0) ? 0 : val;
            } else
                val = state->style->ScaleX;
            state->scale_x = val;
            break;
        }
        case TAG_FSCY:
        {
            double val;
            if (nargs) {
                val = argtod(*args) / 100;
                val = state->scale_y * (1 - pwr) + val * pwr;
                val = (val < 0) ? 0 : val;
            } else
                val = state->style->ScaleY;
            state->scale_y = val;
            break;
        }
        case TAG_FSC:
        {
            state->scale_x = state->style->ScaleX;
            state->scale_y = state->style->ScaleY;
            break;
        }
        case TAG_FSP:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->hspacing =
                    state->hspacing * (1 - pwr) + val * pwr;
            } else
                state->hspacing = state->style->Spacing;
            break;
        }
        case TAG_FS:
        {
            double val = 0;
            if (nargs) {
                val = argtod(*args);
                if (*args->start == '+' || *args->start == '-')
                    val = state->font_size * (1 + pwr * val / 10);
                else
                    val = state->font_size * (1 - pwr) + val * pwr;
            }
            if (val <= 0)
                val = state->style->FontSize;
            state->font_size = val;
            break;
        }
        case TAG_BORD:
        {
            double val, xval, yval;
            if (nargs) {
                val = argtod(*args);
                xval = state->border_x * (1 - pwr) + val * pwr;
                yval = state->border_y * (1 - pwr) + val * pwr;
                xval = (xval < 0) ? 0 : xval;
                yval = (yval < 0) ? 0 : yval;
            } else
                xval = yval = state->style->Outline;
            state->border_x = xval;
            state->border_y = yval;
            break;
        }
        case TAG_MOVE:
        {
            double x1, x2, y1, y2;
            int32_t t1, t2, delta_t, t;
            double x, y;
            double k;
            if (nargs == 4 || nargs == 6) {
                x1 = argtod(args[0]);
                y1 = argtod(args[1]);
                x2 = argtod(args[2]);
                y2 = argtod(args[3]);
                t1 = t2 = 0;
                if (nargs == 6) {
                    t1 = argtoi32(args[4]);
                    t2 = argtoi32(args[5]);
                    if (t1 > t2) {
                        long long tmp = t2;
                        t2 = t1;
                        t1 = tmp;
                    }
                }
            } else
                goto next_tag;
            if (t1 <= 0 && t2 <= 0) {
                t1 = 0;
                t2 = state->event->Duration;
            }
            delta_t = (uint32_t) t2 - t1;
            t = render_priv->time - state->event->Start;
            if (t <= t1)
                k = 0.;
            else if (t >= t2)
                k = 1.;
            else
                k = ((double) (int32_t) ((uint32_t) t - t1)) / delta_t;
            x = k * (x2 - x1) + x1;
            y = k * (y2 - y1) + y1;
            if (!(state->evt_type & EVENT_POSITIONED)) {
                state->pos_x = x;
                state->pos_y = y;
                state->detect_collisions = 0;
                state->evt_type |= EVENT_POSITIONED;
            }
            break;
        }
        case TAG_FRX:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->frx =
                    val * pwr + state->frx * (1 - pwr);
            } else
                state->frx = 0.;
            break;
        }
        case TAG_FRY:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->fry =
                    val * pwr + state->fry * (1 - pwr);
            } else
                state->fry = 0.;
            break;
        }
        case TAG_FRZ:
        case TAG_FR:
        {
            double val;
            if (nargs) {
                val = argtod(*args);
                state->frz =
                    val * pwr + state->frz * (1 - pwr);
            } else
                state->frz =
                    state->style->Angle;
            break;
        }
        case TAG_FN:
        {
            char *start = args->start;
            if (nargs && strncmp(start, "0", args->end - start)) {
                skip_spaces(&start);
                state->family.str = start;
                state->family.len = args->end - start;
            } else {
                state->family.str = state->style->FontName;
                state->family.len = strlen(state->style->FontName);
            }
            ass_update_font(state);
            break;
        }
        case TAG_ALPHA:
        {
            int i;
            if (nargs) {
                int32_t a = parse_alpha_tag(*args);
                for (i = 0; i < 4; ++i)
                    change_alpha(&state->c[i], a, pwr);
            } else {
                change_alpha(&state->c[0],
                             _a(state->style->PrimaryColour), 1);
                change_alpha(&state->c[1],
                             _a(state->style->SecondaryColour), 1);
                change_alpha(&state->c[2],
                             _a(state->style->OutlineColour), 1);
                change_alpha(&state->c[3],
                             _a(state->style->BackColour), 1);
            }
            // FIXME: simplify
            break;
        }
        case TAG_AN:
        {
            int32_t val = argtoi32(*args);
            if ((state->parsed_tags & PARSED_A) == 0) {
                if (val >= 1 && val <= 9)
                    state->alignment = numpad2align(val);
                else
                    state->alignment =
                        state->style->Alignment;
                state->parsed_tags |= PARSED_A;
            }
            break;
        }
        case TAG_A:
        {
            int32_t val = argtoi32(*args);
            if ((state->parsed_tags & PARSED_A) == 0) {
                if (val >= 1 && val <= 11)
                    // take care of a vsfilter quirk:
                    // handle illegal \a8 and \a4 like \a5
                    state->alignment = ((val & 3) == 0) ? 5 : val;
                else
                    state->alignment =
                        state->style->Alignment;
                state->parsed_tags |= PARSED_A;
            }
            break;
        }
        case TAG_POS:
        {
            double v1, v2;
            if (nargs == 2) {
                v1 = argtod(args[0]);
                v2 = argtod(args[1]);
            } else
                goto next_tag;
            if (state->evt_type & EVENT_POSITIONED) {
                ass_msg(render_priv->library, MSGL_V, "Subtitle has a new \\pos "
                       "after \\move or \\pos, ignoring");
            } else {
                state->evt_type |= EVENT_POSITIONED;
                state->detect_collisions = 0;
                state->pos_x = v1;
                state->pos_y = v2;
            }
            break;
        }
        case TAG_FADE:
        case TAG_FAD:
        {
            int32_t a1, a2, a3;
            int32_t t1, t2, t3, t4;
            if (nargs == 2) {
                // 2-argument version (\fad, according to specs)
                a1 = 0xFF;
                a2 = 0;
                a3 = 0xFF;
                t1 = -1;
                t2 = argtoi32(args[0]);
                t3 = argtoi32(args[1]);
                t4 = -1;
            } else if (nargs == 7) {
                // 7-argument version (\fade)
                a1 = argtoi32(args[0]);
                a2 = argtoi32(args[1]);
                a3 = argtoi32(args[2]);
                t1 = argtoi32(args[3]);
                t2 = argtoi32(args[4]);
                t3 = argtoi32(args[5]);
                t4 = argtoi32(args[6]);
            } else
                goto next_tag;
            if (t1 == -1 && t4 == -1) {
                t1 = 0;
                t4 = state->event->Duration;
                t3 = (uint32_t) t4 - t3;
            }
            if ((state->parsed_tags & PARSED_FADE) == 0) {
                state->fade =
                    interpolate_alpha(render_priv->time -
                            state->event->Start, t1, t2,
                            t3, t4, a1, a2, a3);
                state->parsed_tags |= PARSED_FADE;
            }
            break;
        }
        case TAG_ORG:
        {
            double v1, v2;
            if (nargs == 2) {
                v1 = argtod(args[0]);
                v2 = argtod(args[1]);
            } else
                goto next_tag;
            if (!state->have_origin) {
                state->org_x = v1;
                state->org_y = v2;
                state->have_origin = 1;
                state->detect_collisions = 0;
            }
            break;
        }
        case TAG_T:
        {
            double accel;
            int cnt = nargs - 1;
            int32_t t1, t2, t, delta_t;
            double k;
            // VSFilter compatibility (because we can): parse the
            // timestamps differently depending on argument count.
            if (cnt == 3) {
                t1 = argtoi32(args[0]);
                t2 = argtoi32(args[1]);
                accel = argtod(args[2]);
            } else if (cnt == 2) {
                t1 = dtoi32(argtod(args[0]));
                t2 = dtoi32(argtod(args[1]));
                accel = 1.;
            } else if (cnt == 1) {
                t1 = 0;
                t2 = 0;
                accel = argtod(args[0]);
            } else {
                t1 = 0;
                t2 = 0;
                accel = 1.;
            }
            state->detect_collisions = 0;
            if (t2 == 0)
                t2 = state->event->Duration;
            delta_t = (uint32_t) t2 - t1;
            t = render_priv->time - state->event->Start;        // FIXME: move to render_context
            if (t < t1)
                k = 0.;
            else if (t >= t2)
                k = 1.;
            else {
                assert(delta_t != 0.);
                k = pow((double) (int32_t) ((uint32_t) t - t1) / delta_t, accel);
            }
            if (nested)
                pwr = k;
            if (cnt < 0 || cnt > 3)
                goto next_tag;
            // If there's no backslash in the arguments, there are no
            // override tags, so it's pointless to try to parse them.
            if (!has_backslash_arg)
                goto next_tag;
            p = args[cnt].start;
            if (args[cnt].end < end) {
                assert(!nested);
                apply_tags(state, text, p, args[cnt].end, k, true,
                           parsed, current + 1, record->next);
            } else {
                assert(q == end);
                // No other tags can possibly follow this \t tag,
                // so we don't need to restore pwr after parsing \t.
                // The recursive call is now essentially a tail call,
                // so optimize it away.
                pwr = k;
                nested = true;
                q = p;
                next = current + 1;
            }
            break;
        }
        case TAG_CLIP:
        {
            if (nargs == 4) {
                int32_t x0, y0, x1, y1;
                x0 = argtoi32(args[0]);
                y0 = argtoi32(args[1]);
                x1 = argtoi32(args[2]);
                y1 = argtoi32(args[3]);
                state->clip_x0 =
                    state->clip_x0 * (1 - pwr) + x0 * pwr;
                state->clip_x1 =
                    state->clip_x1 * (1 - pwr) + x1 * pwr;
                state->clip_y0 =
                    state->clip_y0 * (1 - pwr) + y0 * pwr;
                state->clip_y1 =
                    state->clip_y1 * (1 - pwr) + y1 * pwr;
                state->clip_mode = 0;
            } else if (!state->clip_drawing_text.str) {
                if (parse_vector_clip(state, args, nargs))
                    state->clip_drawing_mode = 0;
            }
            break;
        }
        case TAG_C:
        case TAG_1C:
        {
            if (nargs) {
                uint32_t val = parse_color_tag(*args);
                change_color(&state->c[0], val, pwr);
            } else
                change_color(&state->c[0],
                             state->style->PrimaryColour, 1);
            break;
        }
        case TAG_2C:
        {
            if (nargs) {
                uint32_t val = parse_color_tag(*args);
                change_color(&state->c[1], val, pwr);
            } else
                change_color(&state->c[1],
                             state->style->SecondaryColour, 1);
            break;
        }
        case TAG_3C:
        {
            if (nargs) {
                uint32_t val = parse_color_tag(*args);
                change_color(&state->c[2], val, pwr);
            } else
                change_color(&state->c[2],
                             state->style->OutlineColour, 1);
            break;
        }
        case TAG_4C:
        {
            if (nargs) {
                uint32_t val = parse_color_tag(*args);
                change_color(&state->c[3], val, pwr);
            } else
                change_color(&state->c[3],
                             state->style->BackColour, 1);
            break;
        }
        case TAG_1A:
        {
            if (nargs) {
                uint32_t val = parse_alpha_tag(*args);
                change_alpha(&state->c[0], val, pwr);
            } else
                change_alpha(&state->c[0],
                             _a(state->style->PrimaryColour), 1);
            break;
        }
        case TAG_2A:
        {
            if (nargs) {
                uint32_t val = parse_alpha_tag(*args);
                change_alpha(&state->c[1], val, pwr);
            } else
                change_alpha(&state->c[1],
                             _a(state->style->SecondaryColour), 1);
            break;
        }
        case TAG_3A:
        {
            if (nargs) {
                uint32_t val = parse_alpha_tag(*args);
                change_alpha(&state->c[2], val, pwr);
            } else
                change_alpha(&state->c[2],
                             _a(state->style->OutlineColour), 1);
            break;
        }
        case TAG_4A:
        {
            if (nargs) {
                uint32_t val = parse_alpha_tag(*args);
                change_alpha(&state->c[3], val, pwr);
            } else
                change_alpha(&state->c[3],
                             _a(state->style->BackColour), 1);
            break;
        }
        case TAG_R:
        {
            if (nargs) {
                int len = args->end - args->start;
                ass_reset_render_context(state,
                        lookup_style_strict(render_priv->track, args->start, len));
            } else
                ass_reset_render_context(state, NULL);
            break;
        }
        case TAG_BE:
        {
            double dval;
            if (nargs) {
                int32_t val;
                dval = argtod(*args);
                // VSFilter always adds +0.5, even if the value is negative
                val = dtoi32(state->be * (1 - pwr) + dval * pwr + 0.5);
                // Clamp to a safe upper limit, since high values need excessive CPU
                val = (val < 0) ? 0 : val;
                val = (val > MAX_BE) ? MAX_BE : val;
                state->be = val;
            } else
                state->be = 0;
            break;
        }
        case TAG_B:
        {
            int32_t val = argtoi32(*args);
            if (!nargs || !(val == 0 || val == 1 || val >= 100))
                val = state->style->Bold;
            state->bold = val;
            ass_update_font(state);
            break;
        }
        case TAG_I:
        {
            int32_t val = argtoi32(*args);
            if (!nargs || !(val == 0 || val == 1))
                val = state->style->Italic;
            state->italic = val;
            ass_update_font(state);
            break;
        }
        case TAG_KT:
        {
            // v4++
            double val = 0;
            if (nargs)
                val = argtod(*args) * 10;
            state->effect_skip_timing = dtoi32(val);
            state->effect_timing = 0;
            state->reset_effect = true;
            break;
        }
        case TAG_KF:
        case TAG_CAPITAL_K:
        {
            double val = 100;
            if (nargs)
                val = argtod(*args);
            state->effect_type = EF_KARAOKE_KF;
            state->effect_skip_timing +=
                    (uint32_t) state->effect_timing;
            state->effect_timing = dtoi32(val * 10);
            break;
        }
        case TAG_KO:
        {
            double val = 100;
            if (nargs)
                val = argtod(*args);
            state->effect_type = EF_KARAOKE_KO;
            state->effect_skip_timing +=
                    (uint32_t) state->effect_timing;
            state->effect_timing = dtoi32(val * 10);
            break;
        }
        case TAG_K:
        {
            double val = 100;
            if (nargs)
                val = argtod(*args);
            state->effect_type = EF_KARAOKE;
            state->effect_skip_timing +=
                    (uint32_t) state->effect_timing;
            state->effect_timing = dtoi32(val * 10);
            break;
        }
        case TAG_SHAD:
        {
            double val, xval, yval;
            if (nargs) {
                val = argtod(*args);
                xval = state->shadow_x * (1 - pwr) + val * pwr;
                yval = state->shadow_y * (1 - pwr) + val * pwr;
                // VSFilter compatibility: clip for \shad but not for \[xy]shad
                xval = (xval < 0) ? 0 : xval;
                yval = (yval < 0) ? 0 : yval;
            } else
                xval = yval = state->style->Shadow;
            state->shadow_x = xval;
            state->shadow_y = yval;
            break;
        }
        case TAG_S:
        {
            int32_t val = argtoi32(*args);
            if (!nargs || !(val == 0 || val == 1))
                val = state->style->StrikeOut;
            if (val)
                state->flags |= DECO_STRIKETHROUGH;
            else
                state->flags &= ~DECO_STRIKETHROUGH;
            break;
        }
        case TAG_U:
        {
            int32_t val = argtoi32(*args);
            if (!nargs || !(val == 0 || val == 1))
                val = state->style->Underline;
            if (val)
                state->flags |= DECO_UNDERLINE;
            else
                state->flags &= ~DECO_UNDERLINE;
            break;
        }
        case TAG_PBO:
        {
            double val = argtod(*args);
            state->pbo = val;
            break;
        }
        case TAG_P:
        {
            int32_t val = argtoi32(*args);
            val = (val < 0) ? 0 : val;
            state->drawing_scale = val;
            break;
        }
        case TAG_Q:
        {
            int32_t val = argtoi32(*args);
            if (!nargs || !(val >= 0 && val <= 3))
                val = render_priv->track->WrapStyle;
            state->wrap_style = val;
            break;
        }
        case TAG_FE:
        {
            int32_t val;
            if (nargs)
                val = argtoi32(*args);
            else
                val = state->style->Encoding;
            state->font_encoding = val;
            break;
        }
        default:
            break;
        }
next_tag:
        p = q;
    }
    return end;
}

void ass_apply_tags(RenderContext *state, char *text, const ParsedEvent *parsed,
                    const ParsedTextRun *run)
{
    apply_tags(state, text, text + run->start, text + run->override_end,
               1., false, parsed, run->first, run->last);
}

void ass_apply_transition_effects(RenderContext *state)
{
    ASS_Renderer *render_priv = state->renderer;
    int v[4];
    int cnt;
    ASS_Event *event = state->event;
    char *p = event->Effect;

    if (!p || !*p)
        return;

    cnt = 0;
    while (cnt < 4 && (p = strchr(p, ';'))) {
        v[cnt++] = atoi(++p);
    }

    ASS_Vector layout_res = ass_layout_res(render_priv);
    if (strncmp(event->Effect, "Banner;", 7) == 0) {
        double delay;
        if (cnt < 1) {
            ass_msg(render_priv->library, MSGL_V,
                    "Error parsing effect: '%s'", event->Effect);
            return;
        }
        if (cnt >= 2 && v[1])   // left-to-right
            state->scroll_direction = SCROLL_LR;
        else                    // right-to-left
            state->scroll_direction = SCROLL_RL;

        delay = v[0];
        // VSF works in storage coordinates, but scales delay to PlayRes canvas
        // before applying max(scaled_ delay, 1). This means, if scaled_delay < 1
        // (esp. delay=0) we end up with 1 ms per _storage pixel_ without any
        // PlayRes scaling.
        // The way libass deals with delay, it is automatically relative to the
        // PlayRes canvas, so we only want to "unscale" the small delay values.
        //
        // VSF also casts the scaled delay to int, which if not emulated leads to
        // easily noticeable deviations from VSFilter as the effect goes on.
        // To achieve both we need to keep our Playres-relative delay with high precision,
        // but must temporarily convert to storage-relative and truncate and take the
        // maxuimum there, before converting back.
        double scale_x = ((double) layout_res.x) / render_priv->track->PlayResX;
        delay = ((int) FFMAX(delay / scale_x, 1)) * scale_x;
        state->scroll_shift =
            (render_priv->time - event->Start) / delay;
        state->evt_type |= EVENT_HSCROLL;
        state->detect_collisions = 0;
        state->wrap_style = 2;
        return;
    }

    if (strncmp(event->Effect, "Scroll up;", 10) == 0) {
        state->scroll_direction = SCROLL_BT;
    } else if (strncmp(event->Effect, "Scroll down;", 12) == 0) {
        state->scroll_direction = SCROLL_TB;
    } else {
        ass_msg(render_priv->library, MSGL_DBG2,
                "Unknown transition effect: '%s'", event->Effect);
        return;
    }
    // parse scroll up/down parameters
    {
        double delay;
        int y0, y1;
        if (cnt < 3) {
            ass_msg(render_priv->library, MSGL_V,
                    "Error parsing effect: '%s'", event->Effect);
            return;
        }
        delay = v[2];
        // See explanation for Banner
        double scale_y = ((double) layout_res.y) / render_priv->track->PlayResY;
        delay = ((int) FFMAX(delay / scale_y, 1)) * scale_y;
        state->scroll_shift =
            (render_priv->time - event->Start) / delay;
        if (v[0] < v[1]) {
            y0 = v[0];
            y1 = v[1];
        } else {
            y0 = v[1];
            y1 = v[0];
        }
        state->scroll_y0 = y0;
        state->scroll_y1 = y1;
        state->evt_type |= EVENT_VSCROLL;
        state->detect_collisions = 0;
    }

}

/**
 * \brief determine karaoke effects
 * Karaoke effects cannot be calculated during parse stage (ass_get_next_char()),
 * so they are done in a separate step.
 * Parse stage: when karaoke style override is found, its parameters are stored in the next glyph's
 * (the first glyph of the karaoke word)'s effect_type and effect_timing.
 * This function:
 * 1. sets effect_type for all glyphs in the word (_karaoke_ word)
 * 2. sets effect_timing for all glyphs to x coordinate of the border line between the left and right karaoke parts
 * (left part is filled with PrimaryColour, right one - with SecondaryColour).
 */
void ass_process_karaoke_effects(RenderContext *state)
{
    TextInfo *text_info = &state->text_info;
    long long tm_current = state->renderer->time - state->event->Start;

    int32_t timing = 0, skip_timing = 0;
    Effect effect_type = EF_NONE;
    GlyphInfo *last_boundary = NULL;
    bool has_reset = false;
    for (int i = 0; i <= text_info->length; i++) {
        if (i < text_info->length &&
            !text_info->glyphs[i].starts_new_run) {

            if (text_info->glyphs[i].reset_effect) {
                has_reset = true;
                skip_timing = 0;
            }

            // VSFilter compatibility: if we have \k12345\k0 without a run
            // break, subsequent text is still part of the same karaoke word,
            // the current word's starting and ending time stay unchanged,
            // but the starting time of the next karaoke word is advanced.
            skip_timing += (uint32_t) text_info->glyphs[i].effect_skip_timing;
            continue;
        }

        GlyphInfo *start = last_boundary;
        GlyphInfo *end = text_info->glyphs + i;
        last_boundary = end;
        if (!start)
            continue;

        if (start->effect_type != EF_NONE)
            effect_type = start->effect_type;
        if (effect_type == EF_NONE)
            continue;

        if (start->reset_effect)
            timing = 0;

        long long tm_start = timing + start->effect_skip_timing;
        long long tm_end = tm_start + start->effect_timing;
        timing = !has_reset * tm_end + skip_timing;
        skip_timing = 0;
        has_reset = false;

        if (effect_type != EF_KARAOKE_KF)
            tm_end = tm_start;

        int x;
        if (tm_current < tm_start)
            x = -100000000;
        else if (tm_current >= tm_end)
            x = 100000000;
        else {
            GlyphInfo *first_visible = start, *last_visible = end - 1;
            while (first_visible < last_visible && first_visible->skip)
                ++first_visible;
            while (first_visible < last_visible && last_visible->skip)
                --last_visible;

            int x_start = first_visible->pos.x;
            int x_end = last_visible->pos.x + last_visible->advance.x;
            double dt = (double) (tm_current - tm_start) / (tm_end - tm_start);
            double frz = fmod(start->frz, 360);
            if (frz > 90 && frz < 270) {
                // Fill from right to left
                dt = 1 - dt;
                for (GlyphInfo *info = start; info < end; info++) {
                    uint32_t tmp = info->c[0];
                    info->c[0] = info->c[1];
                    info->c[1] = tmp;
                }
            }
            x = x_start + ass_lrint((x_end - x_start) * dt);
        }

        for (GlyphInfo *info = start; info < end; info++) {
            info->effect_type = effect_type;
            info->effect_timing = x - info->pos.x;
        }
    }
}


/**
 * \brief Get next ucs4 char from string, parsing UTF-8 and escapes
 * \param str string pointer
 * \return ucs4 code of the next char
 * On return str points to the unparsed part of the string
 */
unsigned ass_get_next_char(RenderContext *state, char **str)
{
    char *p = *str;
    unsigned chr;
    if (*p == '\t') {
        ++p;
        *str = p;
        return ' ';
    }
    if (*p == '\\') {
        if ((p[1] == 'N') || ((p[1] == 'n') &&
                              (state->wrap_style == 2))) {
            p += 2;
            *str = p;
            return '\n';
        } else if (p[1] == 'n') {
            p += 2;
            *str = p;
            return ' ';
        } else if (p[1] == 'h') {
            p += 2;
            *str = p;
            return NBSP;
        } else if (p[1] == '{') {
            p += 2;
            *str = p;
            return '{';
        } else if (p[1] == '}') {
            p += 2;
            *str = p;
            return '}';
        }
    }
    chr = ass_utf8_get_char((char **) &p);
    *str = p;
    return chr;
}

// Return 1 if the event contains tags that will apply overrides the selective
// style override code should not touch. Return 0 otherwise.
int ass_event_has_hard_overrides(char *str)
{
    // look for \pos and \move tags inside {...}
    // mirrors ass_get_next_char, but is faster and doesn't change any global state
    while (*str) {
        if (str[0] == '\\' && str[1] != '\0') {
            str += 2;
        } else if (str[0] == '{') {
            str++;
            while (*str && *str != '}') {
                if (*str == '\\') {
                    char *p = str + 1;
                    if (mystrcmp(&p, "pos") || mystrcmp(&p, "move") ||
                        mystrcmp(&p, "clip") || mystrcmp(&p, "iclip") ||
                        mystrcmp(&p, "org") || mystrcmp(&p, "pbo") ||
                        mystrcmp(&p, "p"))
                        return 1;
                }
                str++;
            }
        } else {
            str++;
        }
    }
    return 0;
}
