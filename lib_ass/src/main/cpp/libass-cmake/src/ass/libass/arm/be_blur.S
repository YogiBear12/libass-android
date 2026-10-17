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
 * AArch32 translation of aarch64/be_blur.S, instruction for instruction.
 * v0-v7 map to q0-q7 so the paired loads and stores stay on consecutive
 * registers; q4-q7 are callee-saved, hence the vpush. v16 maps to q12.
 */

#include "asm.S"

/*
 * void be_blur(uint8_t *buf, intptr_t stride,
 *              intptr_t width, intptr_t height, uint16_t *tmp);
 */

function be_blur_neon, export=1
    push {r4-r9, lr}
    vpush {d8-d15}
    ldr r4, [sp, #28 + 64]
    sub r1, r1, r2
    bic r1, r1, #15
    mov r6, r0
    mov r7, r4
    vmov.i8 q12, #0
    mov r9, r2

    vld1.8 {q3}, [r0]!
    vmovl.u8 q4, d6

    vext.8 q5, q12, q4, #14
    vadd.i16 q5, q5, q4

    vmovl.u8 q0, d7
    b 1f

0:
    vld1.8 {q3}, [r0]!
    vmovl.u8 q4, d6
    vext.8 q5, q0, q4, #14
    vadd.i16 q5, q5, q4
    vmovl.u8 q0, d7
    vext.8 q3, q1, q5, #2
    vadd.i16 q3, q3, q1
    vmov q2, q3

    vst1.16 {d4-d7}, [r4]!

1:
    vext.8 q1, q4, q0, #14
    vadd.i16 q1, q1, q0
    vext.8 q3, q5, q1, #2
    vadd.i16 q3, q3, q5

    vmov q4, q3
    vst1.16 {d6-d9}, [r4]!

    subs r2, r2, #16
    bhi 0b

    vext.8 q0, q0, q12, #14
    vext.8 q3, q1, q0, #2
    vadd.i16 q3, q3, q1

    vmov q4, q3
    vst1.16 {d6-d9}, [r4]!

    add r0, r0, r1
    subs r3, r3, #1
    ble 3f

0:
    mov r4, r7
    mov r2, r9
    vld1.8 {q2}, [r0]!
    vmovl.u8 q4, d4
    vext.8 q5, q12, q4, #14
    vadd.i16 q5, q5, q4
    vmovl.u8 q0, d5

    b 2f

1:
    vld1.8 {q2}, [r0]!
    vmovl.u8 q4, d4
    vext.8 q5, q0, q4, #14
    vadd.i16 q5, q5, q4
    vmovl.u8 q0, d5
    vext.8 q2, q1, q5, #2
    vadd.i16 q6, q2, q1

    vld1.16 {d2-d5}, [r4]
    vadd.i16 q7, q1, q6
    vst1.16 {d12-d15}, [r4]!
    vadd.i16 q2, q2, q7
    vqshrn.u16 d7, q2, #4

    vst1.8 {q3}, [r6]!

2:
    vext.8 q1, q4, q0, #14
    vadd.i16 q1, q1, q0
    vext.8 q2, q5, q1, #2
    vadd.i16 q2, q2, q5

    vld1.16 {d6-d9}, [r4]
    vadd.i16 q3, q3, q2
    vst1.16 {d4-d7}, [r4]!
    vadd.i16 q4, q4, q3
    vqshrn.u16 d6, q4, #4

    subs r2, r2, #16
    bhi 1b

    vext.8 q0, q0, q12, #14
    vext.8 q2, q1, q0, #2
    vadd.i16 q4, q2, q1

    vld1.16 {d0-d3}, [r4]
    vadd.i16 q5, q0, q4
    vst1.16 {d8-d11}, [r4]!
    vadd.i16 q1, q1, q5
    vqshrn.u16 d7, q1, #4
    vst1.8 {q3}, [r6]!

    add r0, r0, r1
    add r6, r6, r1
    subs r3, r3, #1
    bhi 0b

3:
    mov r2, r9
    mov r4, r7
0:
    vld1.16 {d4-d7}, [r4]!
    vld1.16 {d8-d11}, [r4]!
    vadd.i16 q2, q2, q3
    vqshrn.u16 d4, q2, #4
    vadd.i16 q3, q4, q5
    vqshrn.u16 d5, q3, #4
    vst1.8 {q2}, [r6]!
    subs r2, r2, #16
    bhi 0b
    vpop {d8-d15}
    pop {r4-r9, pc}
endfunc
