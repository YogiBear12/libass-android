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

#include "asm.S"

/* void ass_add_bitmaps(uint8_t *dst, ptrdiff_t dst_stride,
 *                      const uint8_t *src, ptrdiff_t src_stride,
 *                      size_t width, size_t height);
 */
function add_bitmaps_neon, export=1
    push {r4-r8, lr}
    ldr r4, [sp, #24]
    ldr r5, [sp, #28]
    mov r6, r1
    mov r7, r3
.Ladd_row:
    mov r8, r4
.Ladd_vector:
    cmp r8, #8
    blo .Ladd_tail
    vld1.8 {d0}, [r0]
    vld1.8 {d1}, [r2]
    vqadd.u8 d0, d0, d1
    vst1.8 {d0}, [r0]
    add r0, r0, #8
    add r2, r2, #8
    subs r8, r8, #8
    b .Ladd_vector
.Ladd_tail:
    cmp r8, #0
    beq .Ladd_next_row
.Ladd_pixel:
    ldrb r12, [r0]
    ldrb lr, [r2]
    add r12, r12, lr
    cmp r12, #255
    movhi r12, #255
    strb r12, [r0], #1
    add r2, r2, #1
    subs r8, r8, #1
    bne .Ladd_pixel
.Ladd_next_row:
    subs r5, r5, #1
    beq .Ladd_done
    sub r12, r6, r4
    add r0, r0, r12
    sub r12, r7, r4
    add r2, r2, r12
    b .Ladd_row
.Ladd_done:
    pop {r4-r8, pc}
endfunc

/* void ass_imul_bitmaps(uint8_t *dst, ptrdiff_t dst_stride,
 *                       const uint8_t *src, ptrdiff_t src_stride,
 *                       size_t width, size_t height);
 */
function imul_bitmaps_neon, export=1
    push {r4-r8, lr}
    ldr r4, [sp, #24]
    ldr r5, [sp, #28]
    mov r6, r1
    mov r7, r3
    vmov.i16 q3, #255
.Limul_row:
    mov r8, r4
.Limul_vector:
    cmp r8, #8
    blo .Limul_tail
    vld1.8 {d0}, [r0]
    vld1.8 {d1}, [r2]
    vmvn d1, d1
    vmull.u8 q2, d0, d1
    vadd.i16 q2, q2, q3
    vshrn.i16 d0, q2, #8
    vst1.8 {d0}, [r0]
    add r0, r0, #8
    add r2, r2, #8
    subs r8, r8, #8
    b .Limul_vector
.Limul_tail:
    cmp r8, #0
    beq .Limul_next_row
.Limul_pixel:
    ldrb r12, [r0]
    ldrb lr, [r2]
    rsb lr, lr, #255
    mul r12, r12, lr
    add r12, r12, #255
    lsr r12, r12, #8
    strb r12, [r0], #1
    add r2, r2, #1
    subs r8, r8, #1
    bne .Limul_pixel
.Limul_next_row:
    subs r5, r5, #1
    beq .Limul_done
    sub r12, r6, r4
    add r0, r0, r12
    sub r12, r7, r4
    add r2, r2, r12
    b .Limul_row
.Limul_done:
    pop {r4-r8, pc}
endfunc

/* void ass_mul_bitmaps(uint8_t *dst, ptrdiff_t dst_stride,
 *                      const uint8_t *src1, ptrdiff_t src1_stride,
 *                      const uint8_t *src2, ptrdiff_t src2_stride,
 *                      size_t width, size_t height);
 */
function mul_bitmaps_neon, export=1
    push {r4-r8, lr}
    ldr r4, [sp, #32]
    ldr r5, [sp, #36]
    ldr r6, [sp, #24]
    ldr r7, [sp, #28]
    vmov.i16 q3, #255
.Lmul_row:
    mov r8, r4
.Lmul_vector:
    cmp r8, #8
    blo .Lmul_tail
    vld1.8 {d0}, [r2]
    vld1.8 {d1}, [r6]
    vmull.u8 q2, d0, d1
    vadd.i16 q2, q2, q3
    vshrn.i16 d0, q2, #8
    vst1.8 {d0}, [r0]
    add r0, r0, #8
    add r2, r2, #8
    add r6, r6, #8
    subs r8, r8, #8
    b .Lmul_vector
.Lmul_tail:
    cmp r8, #0
    beq .Lmul_next_row
.Lmul_pixel:
    ldrb r12, [r2]
    ldrb lr, [r6]
    mul r12, r12, lr
    add r12, r12, #255
    lsr r12, r12, #8
    strb r12, [r0], #1
    add r2, r2, #1
    add r6, r6, #1
    subs r8, r8, #1
    bne .Lmul_pixel
.Lmul_next_row:
    subs r5, r5, #1
    beq .Lmul_done
    sub r12, r1, r4
    add r0, r0, r12
    sub r12, r3, r4
    add r2, r2, r12
    sub r12, r7, r4
    add r6, r6, r12
    b .Lmul_row
.Lmul_done:
    pop {r4-r8, pc}
endfunc
