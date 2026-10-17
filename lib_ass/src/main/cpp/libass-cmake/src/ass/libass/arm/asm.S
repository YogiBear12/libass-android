#ifndef LIBASS_ARM_ASM_S
#define LIBASS_ARM_ASM_S

#include "config.h"

#ifndef PRIVATE_PREFIX
#define PRIVATE_PREFIX ass_
#endif

#define PASTE(a, b) a ## b
#define CONCAT(a, b) PASTE(a, b)

#ifdef PREFIX
#define EXTERN CONCAT(_, PRIVATE_PREFIX)
#else
#define EXTERN PRIVATE_PREFIX
#endif

.syntax unified
.fpu neon
/* ARM state keeps these kernels out of Thumb mode. */
.arm

/* Same-section difference, so it resolves at assembly time with no relocation. */
.macro movrel rd, sym
    movw \rd, #:lower16:(\sym - (98f + 8))
    movt \rd, #:upper16:(\sym - (98f + 8))
98: add \rd, \rd, pc
.endm

.macro function name, export=0, align=2
    .macro endfunc
#ifdef __ELF__
        .size EXTERN\name, . - EXTERN\name
#endif
        .purgem endfunc
    .endm
        .text
        .align \align
    .if \export
        .global EXTERN\name
#ifdef __ELF__
        .type EXTERN\name, %function
        .hidden EXTERN\name
#endif
EXTERN\name:
    .else
#ifdef __ELF__
        .type \name, %function
#endif
\name:
    .endif
.endm

#endif
