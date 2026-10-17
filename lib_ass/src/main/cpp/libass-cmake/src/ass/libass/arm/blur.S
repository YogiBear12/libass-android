/*
 * Copyright (C) 2022 libass contributors
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

/*
 * AArch32 translation of aarch64/blur.S, kept instruction for instruction so it
 * follows upstream changes. v0-v7 map onto q0-q3 and q8-q11, which leaves the
 * callee-saved q4-q7 untouched.
 */

#include "asm.S"

    .text
    .align 4
words_zero:
    .short 0, 0, 0, 0, 0, 0, 0, 0

/*
 * void stripe_unpack(int16_t *dst, const uint8_t *src, ptrdiff_t src_stride,
 *                    size_t width, size_t height);
 */

function stripe_unpack16_neon, export=1
    push {r4-r7, lr}
    ldr r4, [sp, #20]
    add r3, r3, #7
    lsl r5, r4, #4
    bic r7, r3, #15
    sub r2, r2, r7
0:
    mov r6, r0
    subs r7, r3, #16
    blo 2f
1:
    vld1.8 {q0}, [r1]!
    vmov q1, q0
    vzip.8 q1, q0
    vrshr.u16 q1, q1, #2
    vrshr.u16 q0, q0, #2
    vst1.16 {q1}, [r6], r5
    vst1.16 {q0}, [r6], r5
    subs r7, r7, #16
    bhs 1b
2:
    tst r7, #8
    beq 3f
    vld1.8 {q0}, [r1]
    vmov q1, q0
    vzip.8 q1, q0
    vrshr.u16 q1, q1, #2
    vst1.16 {q1}, [r6]
3:
    subs r4, r4, #1
    add r0, r0, #16
    add r1, r1, r2
    bne 0b
    pop {r4-r7, pc}
endfunc

/*
 * void stripe_pack(uint8_t *dst, ptrdiff_t dst_stride, const int16_t *src,
 *                  size_t width, size_t height);
 */

function stripe_pack16_neon, export=1
    push {r4-r8, lr}
    ldr r4, [sp, #24]
    lsl r4, r4, #4
    movw r5, #8
    movt r5, #40
    vmov.i16 q1, #48
    subs r3, r3, #9
    blo 2f
0:
    mov r6, r0
    mov r7, r4
    vdup.32 q0, r5
1:
    add r8, r2, r4
    vld1.16 {q2}, [r2]!
    vld1.16 {q3}, [r8]
    vshr.u16 q8, q2, #8
    vshr.u16 q9, q3, #8
    vsub.i16 q2, q2, q8
    vsub.i16 q3, q3, q9
    vadd.i16 q2, q2, q0
    vadd.i16 q3, q3, q0
    vshrn.i16 d4, q2, #6
    vshrn.i16 d5, q3, #6
    vst1.8 {q2}, [r6]
    subs r7, r7, #16
    veor q0, q0, q1
    add r6, r6, r1
    bne 1b
    subs r3, r3, #16
    add r0, r0, #16
    add r2, r2, r4
    bhs 0b
2:
    tst r3, #8
    beq 4f
    vdup.32 q0, r5
3:
    vld1.16 {q2}, [r2]!
    vshr.u16 q8, q2, #8
    vsub.i16 q2, q2, q8
    vadd.i16 q2, q2, q0
    vshrn.i16 d4, q2, #6
    /* AArch64 shrn zeroes the upper half; the 16-byte store writes it. */
    vmov.i8 d5, #0
    vst1.8 {q2}, [r0]
    subs r4, r4, #16
    veor q0, q0, q1
    add r0, r0, r1
    bne 3b
4:
    pop {r4-r8, pc}
endfunc

/*
 * load_line
 * Load a q register with the source line at offs, or with zeros when offs is
 * outside [0, max).
 */

.macro load_line dst, base, offs, max, zero_offs, tmp
    cmp \offs, \max
    movlo \tmp, \offs
    movhs \tmp, \zero_offs
    add \tmp, \tmp, \base
    vld1.16 {\dst}, [\tmp]
.endm

/*
 * void shrink_horz(int16_t *dst, const int16_t *src,
 *                  size_t src_width, size_t src_height);
 */

function shrink_horz16_neon, export=1
    push {r4-r10, lr}
    lsl r4, r2, #1
    add r4, r4, #15
    bic r4, r4, #15
    mul r4, r4, r3
    add r2, r2, #5 - 2
    movrel r5, words_zero
    sub r5, r5, r1
    mov r6, #0
0:
    mov r7, r3
1:
    sub r8, r6, r3, lsl #4
    load_line q1, r1, r8, r4, r5, r9
    load_line q2, r1, r6, r4, r5, r9
    add r8, r6, r3, lsl #4
    load_line q3, r1, r8, r4, r5, r9
    vmov q0, q1
    vuzp.16 q0, q1
    vuzp.16 q2, q3
    vext.8 q8, q0, q2, #14
    vext.8 q9, q1, q3, #14
    vext.8 q0, q0, q2, #12
    vext.8 q1, q1, q3, #12

    vadd.i16 q0, q0, q3
    vadd.i16 q1, q1, q2
    vadd.i16 q8, q8, q9
    vhadd.u16 q0, q0, q1
    vhadd.u16 q0, q0, q8
    vhadd.u16 q0, q0, q1
    vhadd.u16 q0, q0, q8
    vrshr.u16 q0, q0, #1
    vst1.16 {q0}, [r0]!

    subs r7, r7, #1
    add r6, r6, #16
    bne 1b
    subs r2, r2, #16
    add r6, r6, r3, lsl #4
    bhs 0b
    pop {r4-r10, pc}
endfunc

/*
 * void shrink_vert(int16_t *dst, const int16_t *src,
 *                  size_t src_width, size_t src_height);
 */

function shrink_vert16_neon, export=1
    push {r4-r8, lr}
    lsl r3, r3, #4
    movrel r4, words_zero
    sub r4, r4, r1
0:
    add r5, r3, #(5 - 2) * 16
    vmov.i16 q0, #0
    vmov.i16 q1, #0
    vmov.i16 q2, #0
    vmov.i16 q3, #0
    mov r6, #0
1:
    load_line q8, r1, r6, r3, r4, r7
    add r6, r6, #16
    load_line q9, r1, r6, r3, r4, r7
    add r6, r6, #16

    vadd.i16 q0, q0, q9
    vadd.i16 q1, q1, q8
    vadd.i16 q10, q2, q3
    vhadd.u16 q0, q0, q1
    vhadd.u16 q0, q0, q10
    vhadd.u16 q0, q0, q1
    vhadd.u16 q0, q0, q10
    vrshr.u16 q0, q0, #1
    vst1.16 {q0}, [r0]!

    subs r5, r5, #32
    vmov q0, q2
    vmov q1, q3
    vmov q2, q8
    vmov q3, q9
    bhs 1b
    subs r2, r2, #8
    add r1, r1, r3
    sub r4, r4, r3
    bhi 0b
    pop {r4-r8, pc}
endfunc

/*
 * void expand_horz(int16_t *dst, const int16_t *src,
 *                  size_t src_width, size_t src_height);
 */

.macro expand_horz_step
    sub r8, r6, r3, lsl #4
    load_line q1, r1, r8, r4, r5, r9
    load_line q2, r1, r6, r4, r5, r9
    vext.8 q0, q1, q2, #12
    vext.8 q1, q1, q2, #14

    vhadd.u16 q3, q0, q2
    vhadd.u16 q3, q3, q1
    vhadd.u16 q0, q0, q3
    vhadd.u16 q2, q2, q3
    vrhadd.u16 q0, q0, q1
    vrhadd.u16 q2, q2, q1
    vzip.16 q0, q2
.endm

function expand_horz16_neon, export=1
    push {r4-r10, lr}
    lsl r4, r2, #1
    add r4, r4, #15
    bic r4, r4, #15
    mul r4, r4, r3
    movrel r5, words_zero
    sub r5, r5, r1
    subs r2, r2, #3
    mov r6, #0
    blo 2f
0:
    mov r7, r3
1:
    expand_horz_step
    add r9, r0, r3, lsl #4
    vst1.16 {q0}, [r0]
    vst1.16 {q2}, [r9]

    subs r7, r7, #1
    add r0, r0, #16
    add r6, r6, #16
    bne 1b
    subs r2, r2, #8
    add r0, r0, r3, lsl #4
    bhs 0b
2:
    tst r2, #4
    beq 4f
    mov r7, r3
3:
    expand_horz_step
    vst1.16 {q0}, [r0]!

    subs r7, r7, #1
    add r6, r6, #16
    bne 3b
4:
    pop {r4-r10, pc}
endfunc

/*
 * void expand_vert(int16_t *dst, const int16_t *src,
 *                  size_t src_width, size_t src_height);
 */

function expand_vert16_neon, export=1
    push {r4-r8, lr}
    lsl r3, r3, #4
    movrel r4, words_zero
    sub r4, r4, r1
0:
    add r5, r3, #32
    vmov.i16 q0, #0
    vmov.i16 q1, #0
    mov r6, #0
1:
    load_line q2, r1, r6, r3, r4, r7
    add r6, r6, #16

    vhadd.u16 q3, q0, q2
    vhadd.u16 q3, q3, q1
    vhadd.u16 q0, q0, q3
    vhadd.u16 q3, q2, q3
    vrhadd.u16 q0, q0, q1
    vrhadd.u16 q3, q3, q1
    vst1.16 {q0}, [r0]!
    vst1.16 {q3}, [r0]!

    subs r5, r5, #16
    vmov q0, q1
    vmov q1, q2
    bne 1b
    subs r2, r2, #8
    add r1, r1, r3
    sub r4, r4, r3
    bhi 0b
    pop {r4-r8, pc}
endfunc

/*
 * calc_diff
 * Calculate difference between offset line and center line
 */

.macro calc_diff dst, line0, line1, line2, pos, center
.if \pos == 0
    vsub.i16 \dst, \line2, \center
.elseif \pos > 0 && \pos < 8
    vext.8 \dst, \line1, \line2, #16 - 2 * \pos
    vsub.i16 \dst, \dst, \center
.elseif \pos == 8
    vsub.i16 \dst, \line1, \center
.elseif \pos > 8 && \pos < 16
    vext.8 \dst, \line0, \line1, #32 - 2 * \pos
    vsub.i16 \dst, \dst, \center
.elseif \pos == 16
    vsub.i16 \dst, \line0, \center
.else
.error "invalid pos"
.endif
.endm

/*
 * mla_param
 * Accumulate q10 times filter coefficient pos (held in q0) into q8:q9.
 * 16-bit scalar operands must name a lane of d0-d7, so the lane is spelled out.
 */

.macro mla_lane dreg, lane
    vmlal.s16 q8, d20, \dreg[\lane]
    vmlal.s16 q9, d21, \dreg[\lane]
.endm

.macro mla_param pos
.if \pos == 0
    mla_lane d0, 0
.elseif \pos == 1
    mla_lane d0, 1
.elseif \pos == 2
    mla_lane d0, 2
.elseif \pos == 3
    mla_lane d0, 3
.elseif \pos == 4
    mla_lane d1, 0
.elseif \pos == 5
    mla_lane d1, 1
.elseif \pos == 6
    mla_lane d1, 2
.elseif \pos == 7
    mla_lane d1, 3
.else
.error "invalid pos"
.endif
.endm

/*
 * calc_blur
 * Calculate filtered line from q1-q3 around center, store it at r0
 */

.macro calc_blur n, center
    vmov.i32 q8, #0x8000
    vmov.i32 q9, #0x8000
.set pos, 0
.rept \n
    calc_diff q10, q1, q2, q3, (\n - pos - 1), \center
    mla_param pos
    calc_diff q10, q1, q2, q3, (\n + pos + 1), \center
    mla_param pos
.set pos, pos + 1
.endr
    vshrn.i32 d20, q8, #16
    vshrn.i32 d21, q9, #16
    vadd.i16 q10, q10, \center
    vst1.16 {q10}, [r0]!
.endm

/*
 * void blur_horz(int16_t *dst, const int16_t *src,
 *                size_t src_width, size_t src_height,
 *                const int16_t *param);
 */

.macro blur_horz n
function blur\n\()_horz16_neon, export=1
    push {r4-r10, lr}
    ldr r4, [sp, #32]
    vld1.16 {q0}, [r4]
    lsl r4, r2, #1
    add r4, r4, #15
    bic r4, r4, #15
    mul r4, r4, r3
    movrel r5, words_zero
    sub r5, r5, r1
    add r2, r2, #2 * \n
    mov r6, #0
0:
    mov r7, r3
1:
.if \n > 4
    sub r8, r6, r3, lsl #5
    load_line q1, r1, r8, r4, r5, r9
.endif
    sub r8, r6, r3, lsl #4
    load_line q2, r1, r8, r4, r5, r9
    load_line q3, r1, r6, r4, r5, r9

.if \n < 8
    vext.8 q11, q2, q3, #16 - 2 * \n
    calc_blur \n, q11
.else
    calc_blur \n, q2
.endif

    subs r7, r7, #1
    add r6, r6, #16
    bne 1b
    subs r2, r2, #8
    bhi 0b
    pop {r4-r10, pc}
endfunc
.endm

blur_horz 4
blur_horz 5
blur_horz 6
blur_horz 7
blur_horz 8

/*
 * void blur_vert(int16_t *dst, const int16_t *src,
 *                size_t src_width, size_t src_height,
 *                const int16_t *param);
 */

.macro blur_vert n
function blur\n\()_vert16_neon, export=1
    push {r4-r8, lr}
    ldr r4, [sp, #24]
    vld1.16 {q0}, [r4]
    lsl r3, r3, #4
    movrel r4, words_zero
    sub r4, r4, r1
0:
    add r5, r3, #32 * \n
    mov r6, #-16 * \n
1:
    load_line q1, r1, r6, r3, r4, r7
    vmov.i32 q8, #0x8000
    vmov.i32 q9, #0x8000
.set pos, 0
.rept \n
    sub r8, r6, #16 * (pos + 1)
    load_line q10, r1, r8, r3, r4, r7
    vsub.i16 q10, q10, q1
    mla_param pos
    add r8, r6, #16 * (pos + 1)
    load_line q10, r1, r8, r3, r4, r7
    vsub.i16 q10, q10, q1
    mla_param pos
.set pos, pos + 1
.endr
    vshrn.i32 d20, q8, #16
    vshrn.i32 d21, q9, #16
    vadd.i16 q10, q10, q1
    vst1.16 {q10}, [r0]!

    subs r5, r5, #16
    add r6, r6, #16
    bne 1b
    subs r2, r2, #8
    add r1, r1, r3
    sub r4, r4, r3
    bhi 0b
    pop {r4-r8, pc}
endfunc
.endm

blur_vert 4
blur_vert 5
blur_vert 6
blur_vert 7
blur_vert 8
