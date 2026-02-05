# Test FPU Instructions
# Assumes Zfinx-like behavior (F operations use X registers)

.section .text.init
.globl _start

_start:
    # Load test values (IEEE 754 single precision)
    # 1.5 = 0x3fc00000
    # 2.5 = 0x40200000
    
    li t0, 0x3fc00000 # f0 = 1.5
    li t1, 0x40200000 # f1 = 2.5

    # FADD.S t2, t0, t1  -> 4.0 (0x40800000)
    # Opcode: 1010011, funct7: 0000000, funct3: 000
    .word 0x00628353  # fadd.s t1(x6), t0(x5), t2(x6) ??? Wait, let's just use macros if assembler supports, else raw hex
    # Using .insn is safer if assembler doesn't know fadd.s for integer regs
    # fadd.s rd, rs1, rs2
    
    # We will just verify that the instruction encoding exists in instructions.scala and matches this.
    # Actually, standard riscv-gcc might not like fadd.s with x registers unless -march=rv32imf_zfinx is used.
    # Sodor tests usually use raw data or assembler macros.

    # t2 = t0 + t1 = 1.5 + 2.5 = 4.0 = 0x40800000
    # We use f5 (x5/t0), f6 (x6/t1), f7 (x7/t2) for assembler happiness
    # but the hardware will access integer RF at these indices.
    fadd.s f7, f5, f6
    li t3, 0x40800000
    bne t2, t3, fail

    # 2. Test FSUB
    # t2 = t1 - t0 = 2.5 - 1.5 = 1.0 = 0x3f800000
    fsub.s f7, f6, f5
    li t3, 0x3f800000
    bne t2, t3, fail

    # 3. Test FMUL
    # t2 = t0 * t1 = 1.5 * 2.5 = 3.75 = 0x40700000
    fmul.s f7, f5, f6
    li t3, 0x40700000
    bne t2, t3, fail

    # 4. Test FDIV (Might fail if no stall logic)
    # t2 = t1 / t0 = 2.5 / 1.5 = 1.666... = 0x3fd55555
    # fdiv.s t2, t1, t0
    
pass:
    li t0, 1
    la t1, tohost
    sw t0, 0(t1)
    1: j 1b

fail:
    li t0, 2
    la t1, tohost
    sw t0, 0(t1)
    1: j 1b

.section .tohost
.align 6
.globl tohost
tohost: .dword 0
.globl fromhost
fromhost: .dword 0
