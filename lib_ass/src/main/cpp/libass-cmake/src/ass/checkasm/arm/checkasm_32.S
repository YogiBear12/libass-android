#define PRIVATE_PREFIX checkasm_
#include "arm/asm.S"

/* Two hidden arguments keep 64-bit parameters aligned as in the target call. */
function checked_call_vfp, export=1
    push {r4-r12, lr}
    vpush {d8-d15}
    sub sp, sp, #64
    mov r12, r0
    mov r0, r2
    mov r1, r3
    ldr r2, [sp, #168]
    ldr r3, [sp, #172]
    .set offset, 0
    .rept 12
        ldr r4, [sp, #176 + offset]
        str r4, [sp, #offset]
        .set offset, offset + 4
    .endr
    movw r4, #0x1357
    str r4, [sp, #48]
    .irp reg, 4, 5, 6, 7, 8, 9, 10, 11
        movw r\reg, #(\reg * 0x1111)
    .endr
    vmov.i8 q4, #0x44
    vmov.i8 q5, #0x55
    vmov.i8 q6, #0x66
    vmov.i8 q7, #0x77
    blx r12
    str r0, [sp, #52]
    str r1, [sp, #56]
    ldr r2, [sp, #48]
    movw r3, #0x1357
    cmp r2, r3
    bne 1f
    .irp reg, 4, 5, 6, 7, 8, 9, 10, 11
        movw r2, #(\reg * 0x1111)
        cmp r\reg, r2
        bne 1f
    .endr
    .irp reg, 8, 9, 10, 11, 12, 13, 14, 15
        vmov r2, r3, d\reg
        movw r12, #((\reg / 2) * 0x1111)
        movt r12, #((\reg / 2) * 0x1111)
        cmp r2, r12
        bne 1f
        cmp r3, r12
        bne 1f
    .endr
    b 2f
1:
    movrel r0, error_message
    bl checkasm_fail_func
2:
    ldr r0, [sp, #52]
    ldr r1, [sp, #56]
    add sp, sp, #64
    vpop {d8-d15}
    pop {r4-r12, pc}
endfunc

error_message:
    .asciz "failed to preserve register or stack"
