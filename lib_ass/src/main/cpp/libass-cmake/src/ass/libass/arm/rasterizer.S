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
 * AArch32 translation of aarch64/rasterizer.S. v0-v7 map to q0-q3 and
 * q8-q11; v16-v19 map to q12-q15. No callee-saved NEON registers are used.
 */

#include "asm.S"

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
.set big_endian, 0
#elif __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__
.set big_endian, 1
#else
.error "unknown byte order"
#endif

    .text
    .align 4
words_index:
    .short 0, 1, 2, 3, 4, 5, 6, 7

/* Fill size bytes (16 or 32) starting from dst with q0. */
.macro fill_line dst, size
.if \size == 16
    vst1.8 {q0}, [\dst]
.elseif \size == 32
    vst1.8 {q0}, [\dst]!
    vst1.8 {q0}, [\dst]
    sub \dst, \dst, #16
.else
.error "invalid line size"
.endif
.endm

/* void fill_solid_tile(uint8_t *buf, ptrdiff_t stride, int set); */
.macro fill_solid_tile tile_size
function fill_solid_tile\tile_size\()_neon, export=1
    cmp r2, #0
    movne r2, #-1
    vdup.32 q0, r2
.rept \tile_size - 1
    fill_line r0, \tile_size
    add r0, r0, r1
.endr
    fill_line r0, \tile_size
    bx lr
endfunc
.endm

fill_solid_tile 16
fill_solid_tile 32

/* Calculate a line using the antialiased halfplane algorithm. */
.macro calc_line dst, src, delta, zero, full, tmp
    vadd.i16 \tmp, \src, \delta
    vmax.s16 \dst, \src, \zero
    vmax.s16 \tmp, \tmp, \zero
    vmin.s16 \dst, \dst, \full
    vmin.s16 \tmp, \tmp, \full
    vadd.i16 \dst, \dst, \tmp
.endm

/*
 * void fill_halfplane_tile(uint8_t *buf, ptrdiff_t stride,
 *                          int32_t a, int32_t b, int64_t c, int32_t scale);
 */
.macro fill_halfplane_tile tile_order, tile_size
function fill_halfplane_tile\tile_size\()_neon, export=1
    push {r4-r8, lr}
    ldr r5, [sp, #32]
    /* Only the high word survives each rounded signed 64-bit product. */
    smull r6, r7, r2, r5
    add r7, r7, #1 << (13 + \tile_order)
    asr r2, r7, #14 + \tile_order
    smull r6, r7, r3, r5
    add r7, r7, #1 << (13 + \tile_order)
    asr r3, r7, #14 + \tile_order
.if big_endian
    ldr r4, [sp, #28]
    ldr r6, [sp, #24]
.else
    ldr r4, [sp, #24]
    ldr r6, [sp, #28]
.endif
    /* RESCALE_C casts c >> (7 + tile_order) to int32_t before multiplying. */
    lsr r4, r4, #7 + \tile_order
    orr r4, r4, r6, lsl #25 - \tile_order
    smull r6, r7, r4, r5
    add r7, r7, #1 << 12
    asr r4, r7, #13
    add r6, r2, r3
    add r4, r4, #1 << (13 - \tile_order)
    sub r4, r4, r6, asr #1

    cmp r2, #0
    mov r5, r2
    rsblt r5, r2, #0
    cmp r3, #0
    mov r6, r3
    rsblt r6, r3, #0
    cmp r5, r6
    movgt r5, r6
    add r5, r5, #2
    lsr r5, r5, #2
    sub r4, r4, r5
    add r5, r5, r5

    vdup.16 q0, r4
    movrel r6, words_index
    vld1.16 {q1}, [r6]
    vdup.16 q2, r2
    vmls.i16 q0, q1, q2

    mov r6, #(1 << \tile_order) - 8
    mul r6, r2, r6
    sub r3, r3, r6
    vdup.16 q1, r5
    vshl.i16 q2, q2, #3
    vdup.16 q3, r3

    vmov.i16 q8, #0
    vmov.i16 q9, #1 << (14 - \tile_order)
.if (1 << \tile_order) > 16
    sub r1, r1, #(1 << \tile_order) - 16
.endif
    mov r3, #1 << \tile_order
0:
.set pos, 0
.rept (1 << \tile_order) / 16
    calc_line q10, q0, q1, q8, q9, q12
    vsub.i16 q0, q0, q2
    calc_line q11, q0, q1, q8, q9, q12
    vqshrn.u16 d20, q10, #7 - \tile_order
    vqshrn.u16 d21, q11, #7 - \tile_order
.set pos, pos + 16
.if pos == (1 << \tile_order)
    vst1.8 {q10}, [r0]
    vsub.i16 q0, q0, q3
.else
    vst1.8 {q10}, [r0]!
    vsub.i16 q0, q0, q2
.endif
.endr
    subs r3, r3, #1
    add r0, r0, r1
    bne 0b
    pop {r4-r8, pc}
endfunc
.endm

fill_halfplane_tile 4, 16
fill_halfplane_tile 5, 32

/*
 * struct segment {
 *     int64_t c;
 *     int32_t a, b, scale, flags;
 *     int32_t x_min, x_max, y_min, y_max;
 * };
 */
.set line_c,       0
.set line_a,       8
.set line_b,      12
.set line_scale,  16
.set line_flags,  20
.set line_x_min,  24
.set line_x_max,  28
.set line_y_min,  32
.set line_y_max,  36
.set sizeof_line, 40

/* Render the top/bottom line of the trapezium with antialiasing. */
.macro update_border_line tile_order, res, abs_a, b, abs_b, size, sum, vc, van, zero, \
                          tmp1, tmp2
    subs \tmp1, \abs_a, \size, lsl #8 - \tile_order
    movlt \tmp1, #0
    rsbge \tmp1, \tmp1, #0
    add \tmp1, \tmp1, #1 << (14 - \tile_order)
    lsl \tmp1, \tmp1, #2 * \tile_order - 5
    vdup.16 q10, \tmp1

    mul \tmp2, \abs_b, \size
    lsr \tmp2, \tmp2, #6
    cmp \tmp2, \abs_a
    movgt \tmp2, \abs_a
    add \tmp2, \tmp2, #2
    lsr \tmp2, \tmp2, #2

    mul \sum, \sum, \b
    asr \sum, \sum, #7
    add \sum, \sum, \tmp2
    sub \tmp2, \sum, \tmp2, lsl #1
    mul \sum, \sum, \tmp1
    mul \tmp2, \tmp2, \tmp1
    sub \sum, \size, \sum, asr #16
    sub \tmp2, \size, \tmp2, asr #16
    vdup.16 q11, \sum
    vdup.16 q12, \tmp2

    lsl \size, \size, #1
    vdup.16 q13, \size
.set pos, 0
.rept (1 << \tile_order) / 8
    vmull.s16 q14, d6, d20
    vmull.s16 q15, d7, d21
    /* AArch64 uzp2 keeps the high halfword of each signed product. */
    vuzp.16 q14, q15
    vmov q14, q15
    vadd.i16 q15, q14, q11
    vadd.i16 q14, q14, q12
    vmax.s16 q15, q15, \zero
    vmax.s16 q14, q14, \zero
    vmin.s16 q15, q15, q13
    vmin.s16 q14, q14, q13
    vadd.i16 q14, q14, q15
    vld1.16 {q15}, [\res]
    vadd.i16 q15, q15, q14
    vst1.16 {q15}, [\res]!
.set pos, pos + 16
.if pos < (2 << \tile_order)
    vsub.i16 \vc, \vc, \van
.endif
.endr
.endm

/*
 * void fill_generic_tile(uint8_t *buf, ptrdiff_t stride,
 *                        const struct segment *line, size_t n_lines,
 *                        int winding);
 *
 * AArch64 w5-w14 map to r0-r9. Output arguments and the segment iterator
 * are spilled above the temporary buffer; r10-r12 handle 64-bit products.
 */
.macro fill_generic_tile tile_order, tile_size
function fill_generic_tile\tile_size\()_neon, export=1
    .set delta_offs, 2 << (2 * \tile_order)
    .set buf_size, delta_offs + (2 << \tile_order) + 16
    push {r4-r11, lr}
    ldr r12, [sp, #36]
    /* The extra 28 bytes retain arguments and keep the stack 8-byte aligned. */
    sub sp, sp, #delta_offs
    sub sp, sp, #buf_size - delta_offs + 28
    str r0, [sp, #buf_size]
    str r1, [sp, #buf_size + 4]
    str r2, [sp, #buf_size + 8]
    str r3, [sp, #buf_size + 12]
    str r12, [sp, #buf_size + 16]
    vmov.i16 q0, #0
    mov r10, sp
.rept buf_size / 32
    vst1.16 {q0}, [r10]!
    vst1.16 {q0}, [r10]!
.endr
.if (buf_size & 16) != 0
    vst1.16 {q0}, [r10]!
.endif

    vmov.i16 q1, #1 << (14 - \tile_order)
    movrel r10, words_index
    vld1.16 {q2}, [r10]
0:
    ldr r10, [sp, #buf_size + 8]
    /* Split the flags/x_min pair instead of testing an AArch64 64-bit value. */
    ldr r0, [r10, #line_flags]
    ldr r11, [r10, #line_x_min]
    and r1, r0, #2
    add r1, r1, #2
    and r12, r0, #4
    cmp r12, #4
    cmpeq r11, #0
    movne r1, #0
    tst r0, #1
    mov r0, r1
    mvnne r0, r1

    ldr r1, [r10, #line_y_min]
    ldr r2, [r10, #line_y_max]
    asr r3, r1, #6
    asr r4, r2, #6
    and r5, r1, #63
    and r6, r2, #63
    mov r7, #256 << 16
    tst r0, #4
    beq 1f
    add r8, sp, r3, lsl #1
    ldr r9, [r8, #delta_offs]
.if big_endian
    ror r9, r9, #16
.endif
    sub r9, r9, r5, lsl #18
    ror r9, r9, #16
    add r9, r9, r5, lsl #18
    sub r9, r9, r7
.if !big_endian
    ror r9, r9, #16
.endif
    str r9, [r8, #delta_offs]
1:
    tst r0, #2
    beq 2f
    add r8, sp, r4, lsl #1
    ldr r9, [r8, #delta_offs]
.if big_endian
    ror r9, r9, #16
.endif
    add r9, r9, r6, lsl #18
    ror r9, r9, #16
    sub r9, r9, r6, lsl #18
    add r9, r9, r7
.if !big_endian
    ror r9, r9, #16
.endif
    str r9, [r8, #delta_offs]
2:
    cmp r1, r2
    beq 7f

    ldr r1, [r10, #line_a]
    ldr r2, [r10, #line_b]
    ldr r7, [r10, #line_scale]
    smull r11, r12, r1, r7
    add r12, r12, #1 << (13 + \tile_order)
    asr r1, r12, #14 + \tile_order
    smull r11, r12, r2, r7
    add r12, r12, #1 << (13 + \tile_order)
    asr r2, r12, #14 + \tile_order
.if big_endian
    ldr r0, [r10, #line_c + 4]
    ldr r11, [r10, #line_c]
.else
    ldr r0, [r10, #line_c]
    ldr r11, [r10, #line_c + 4]
.endif
    lsr r0, r0, #7 + \tile_order
    orr r0, r0, r11, lsl #25 - \tile_order
    smull r11, r12, r0, r7
    add r12, r12, #1 << 12
    asr r0, r12, #13
    sub r0, r0, r1, asr #1
    mul r11, r2, r3
    sub r0, r0, r11

    cmp r1, #0
    mov r7, r1
    rsblt r7, r1, #0
    cmp r2, #0
    mov r8, r2
    rsblt r8, r2, #0
    vdup.16 q3, r0
    vdup.16 q8, r1
    vmls.i16 q3, q2, q8
    mov r0, #(1 << \tile_order) - 8
    mul r0, r0, r1
    sub r0, r2, r0
    vshl.i16 q8, q8, #3
    vdup.16 q9, r0

    lsl r3, r3, #\tile_order + 1
    lsl r4, r4, #\tile_order + 1
    add r3, sp, r3
    add r4, sp, r4
    cmp r3, r4
    beq 6f
    cmp r5, #0
    beq 3f
    mov r9, #64
    sub r9, r9, r5
    add r5, r5, #64
    update_border_line \tile_order, r3, r7, r2, r8, r9, r5, q3, q8, q0, r0, r1
    vsub.i16 q3, q3, q9
    cmp r3, r4
    beq 5f
3:
    cmp r7, r8
    mov r0, r7
    movgt r0, r8
    add r0, r0, #2
    lsr r0, r0, #2
    mov r1, #1 << (13 - \tile_order)
    sub r1, r1, r2, asr #1
    sub r1, r1, r0
    add r0, r0, r0
    vdup.16 q10, r1
    vdup.16 q11, r0
    vadd.i16 q3, q3, q10
4:
.set pos, 0
.rept (1 << \tile_order) / 8
    calc_line q12, q3, q11, q0, q1, q13
    vld1.16 {q13}, [r3]
    vsra.s16 q13, q12, #7 - \tile_order
    vst1.16 {q13}, [r3]!
.set pos, pos + 16
.if pos < (2 << \tile_order)
    vsub.i16 q3, q3, q8
.else
    vsub.i16 q3, q3, q9
.endif
.endr
    cmp r3, r4
    bne 4b
    vsub.i16 q3, q3, q10
5:
    cmp r6, #0
    beq 7f
    mov r5, #0
6:
    sub r9, r6, r5
    add r5, r6, r5
    update_border_line \tile_order, r3, r7, r2, r8, r9, r5, q3, q8, q0, r0, r1
7:
    ldr r3, [sp, #buf_size + 12]
    ldr r2, [sp, #buf_size + 8]
    subs r3, r3, #1
    add r2, r2, #sizeof_line
    str r3, [sp, #buf_size + 12]
    str r2, [sp, #buf_size + 8]
    bne 0b

    ldr r0, [sp, #buf_size]
    ldr r1, [sp, #buf_size + 4]
    ldr r4, [sp, #buf_size + 16]
    lsl r4, r4, #8
    add r3, sp, #delta_offs
    /* Keep sp fixed so the spill slots remain addressable while consuming res. */
    mov r10, sp
.if (1 << \tile_order) > 16
    sub r1, r1, #(1 << \tile_order) - 16
.endif
    mov r2, #1 << \tile_order
8:
    ldrsh r5, [r3], #2
    add r4, r4, r5
    vdup.16 q2, r4
.set pos, 0
.rept (1 << \tile_order) / 16
    vld1.16 {q0, q1}, [r10]!
    vadd.i16 q0, q0, q2
    vadd.i16 q1, q1, q2
    vabs.s16 q0, q0
    vabs.s16 q1, q1
    vqmovn.u16 d0, q0
    vqmovn.u16 d1, q1
.set pos, pos + 16
.if pos == (1 << \tile_order)
    vst1.8 {q0}, [r0]
.else
    vst1.8 {q0}, [r0]!
.endif
.endr
    subs r2, r2, #1
    add r0, r0, r1
    bne 8b
    add sp, sp, #delta_offs
    add sp, sp, #buf_size - delta_offs + 28
    pop {r4-r11, pc}
endfunc
.endm

fill_generic_tile 4, 16
fill_generic_tile 5, 32

/* Calculate the maximum of two lines. */
.macro merge_line dst, src, size
.if \size == 16
    vld1.8 {q0}, [\dst]
    vld1.8 {q1}, [\src]!
    vmax.u8 q0, q0, q1
    vst1.8 {q0}, [\dst]
.elseif \size == 32
    vld1.8 {q0, q1}, [\dst]
    vld1.8 {q2, q3}, [\src]!
    vmax.u8 q0, q0, q2
    vmax.u8 q1, q1, q3
    vst1.8 {q0, q1}, [\dst]
.else
.error "invalid line size"
.endif
.endm

/* void merge_tile(uint8_t *buf, ptrdiff_t stride, const uint8_t *tile); */
.macro merge_tile tile_size
function merge_tile\tile_size\()_neon, export=1
.rept \tile_size - 1
    merge_line r0, r2, \tile_size
    add r0, r0, r1
.endr
    merge_line r0, r2, \tile_size
    bx lr
endfunc
.endm

merge_tile 16
merge_tile 32
