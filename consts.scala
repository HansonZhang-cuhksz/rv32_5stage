//**************************************************************************
// RISCV Processor Constants
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2011 Feb 1

package sodor.stage5
package constants
{

import chisel3._
import chisel3.util._


trait SodorProcConstants
{

   //************************************
   // Machine Parameters
   val USE_FULL_BYPASSING = false // turn on full bypassing (only stalls
                                  // on load-use). Otherwise rely
                                  // entirely on interlocking to handle
                                  // pipeline hazards.
}

trait ScalarOpConstants
{

   //************************************
   // Control Signals
   val Y        = true.B
   val N        = false.B

   // PC Select Signal
   val PC_4     = 0.asUInt(2.W)  // PC + 4
   val PC_BRJMP = 1.asUInt(2.W)  // brjmp_target
   val PC_JALR  = 2.asUInt(2.W)  // jump_reg_target
   val PC_EXC   = 3.asUInt(2.W)  // exception

   // Branch Type
   val BR_N     = 0.asUInt(4.W)  // Next
   val BR_NE    = 1.asUInt(4.W)  // Branch on NotEqual
   val BR_EQ    = 2.asUInt(4.W)  // Branch on Equal
   val BR_GE    = 3.asUInt(4.W)  // Branch on Greater/Equal
   val BR_GEU   = 4.asUInt(4.W)  // Branch on Greater/Equal Unsigned
   val BR_LT    = 5.asUInt(4.W)  // Branch on Less Than
   val BR_LTU   = 6.asUInt(4.W)  // Branch on Less Than Unsigned
   val BR_J     = 7.asUInt(4.W)  // Jump
   val BR_JR    = 8.asUInt(4.W)  // Jump Register

   // RS1 Operand Select Signal
   val OP1_RS1   = 0.asUInt(2.W) // Register Source #1
   val OP1_PC    = 1.asUInt(2.W) // PC
   val OP1_IMZ   = 2.asUInt(2.W) // Zero-extended Immediate from RS1 field, for use by CSRI instructions
   val OP1_X     = 0.asUInt(2.W)

   // RS2 Operand Select Signal
   val OP2_RS2    = 0.asUInt(3.W) // Register Source #2
   val OP2_ITYPE  = 1.asUInt(3.W) // immediate, I-type
   val OP2_STYPE  = 2.asUInt(3.W) // immediate, S-type
   val OP2_SBTYPE = 3.asUInt(3.W) // immediate, B
   val OP2_UTYPE  = 4.asUInt(3.W) // immediate, U-type
   val OP2_UJTYPE = 5.asUInt(3.W) // immediate, J-type
   val OP2_X      = 0.asUInt(3.W)

   // Register Operand Output Enable Signal
   val OEN_0   = false.B
   val OEN_1   = true.B

   // Register File Write Enable Signal
   val REN_0   = false.B
   val REN_1   = true.B

   // FP RegFile Write Enable Signal
   val FREN_0   = false.B
   val FREN_1   = true.B

   // ALU Operation Signal
   val FUN_SZ_ALU = 5   // To support more ALU operations
   val ALU_ADD    = 0.asUInt(FUN_SZ_ALU.W)
   val ALU_SUB    = 1.asUInt(FUN_SZ_ALU.W)
   val ALU_SLL    = 2.asUInt(FUN_SZ_ALU.W)
   val ALU_SRL    = 3.asUInt(FUN_SZ_ALU.W)
   val ALU_SRA    = 4.asUInt(FUN_SZ_ALU.W)
   val ALU_AND    = 5.asUInt(FUN_SZ_ALU.W)
   val ALU_OR     = 6.asUInt(FUN_SZ_ALU.W)
   val ALU_XOR    = 7.asUInt(FUN_SZ_ALU.W)
   val ALU_SLT    = 8.asUInt(FUN_SZ_ALU.W)
   val ALU_SLTU   = 9.asUInt(FUN_SZ_ALU.W)
   val ALU_COPY_1 = 10.asUInt(FUN_SZ_ALU.W)
   val ALU_COPY_2 = 11.asUInt(FUN_SZ_ALU.W)
   // M extension
   val ALU_MUL    = 12.asUInt(FUN_SZ_ALU.W)
   val ALU_MULH   = 13.asUInt(FUN_SZ_ALU.W)
   val ALU_MULHSU = 14.asUInt(FUN_SZ_ALU.W)
   val ALU_MULHU  = 15.asUInt(FUN_SZ_ALU.W)
   val ALU_DIV    = 16.asUInt(FUN_SZ_ALU.W)
   val ALU_DIVU   = 17.asUInt(FUN_SZ_ALU.W)
   val ALU_REM    = 18.asUInt(FUN_SZ_ALU.W)
   val ALU_REMU   = 19.asUInt(FUN_SZ_ALU.W)
   val ALU_X      = 0.asUInt(FUN_SZ_ALU.W)

   // FPU Operation Signal
   val FUN_SZ_FPU = 5
   val FPU_FADD_S = 1.asUInt(FUN_SZ_FPU.W)
   val FPU_FSUB_S = 2.asUInt(FUN_SZ_FPU.W)
   val FPU_FMUL_S = 3.asUInt(FUN_SZ_FPU.W)
   val FPU_FDIV_S = 4.asUInt(FUN_SZ_FPU.W)
   val FPU_FSQRT_S= 5.asUInt(FUN_SZ_FPU.W)
   val FPU_FSGNJ_S= 6.asUInt(FUN_SZ_FPU.W)
   val FPU_FSGNJN_S= 7.asUInt(FUN_SZ_FPU.W)
   val FPU_FSGNJX_S= 8.asUInt(FUN_SZ_FPU.W)
   val FPU_FMIN_S = 9.asUInt(FUN_SZ_FPU.W)
   val FPU_FMAX_S = 10.asUInt(FUN_SZ_FPU.W)
   val FPU_FEQ_S  = 11.asUInt(FUN_SZ_FPU.W)
   val FPU_FLT_S  = 12.asUInt(FUN_SZ_FPU.W)
   val FPU_FLE_S  = 13.asUInt(FUN_SZ_FPU.W)
   val FPU_FCLASS_S= 14.asUInt(FUN_SZ_FPU.W)
   val FPU_FCVT_W_S= 15.asUInt(FUN_SZ_FPU.W)
   val FPU_FCVT_WU_S=16.asUInt(FUN_SZ_FPU.W)
   val FPU_FCVT_S_W= 17.asUInt(FUN_SZ_FPU.W)
   val FPU_FCVT_S_WU=18.asUInt(FUN_SZ_FPU.W)
   val FPU_COPY_1 = 19.asUInt(FUN_SZ_FPU.W)
   val FPU_COPY_2 = 20.asUInt(FUN_SZ_FPU.W)
   val FPU_FMADD_S = 21.asUInt(FUN_SZ_FPU.W)
   val FPU_FMSUB_S = 22.asUInt(FUN_SZ_FPU.W)
   val FPU_FNMADD_S= 23.asUInt(FUN_SZ_FPU.W)
   val FPU_FNMSUB_S= 24.asUInt(FUN_SZ_FPU.W)
   val FPU_X      = 0.asUInt(FUN_SZ_FPU.W)

   // Writeback Select Signal
   val WB_ALU  = 0.asUInt(3.W)
   val WB_MEM  = 1.asUInt(3.W)
   val WB_PC4  = 2.asUInt(3.W)
   val WB_CSR  = 3.asUInt(3.W)
   val WB_FPU  = 4.asUInt(3.W)
   val WB_X    = 0.asUInt(3.W)

   // FP Writeback Select Signal
   val FWB_ALU  = 0.asUInt(3.W)
   val FWB_MEM  = 1.asUInt(3.W)
   val FWB_FPU  = 2.asUInt(3.W)
   val FWB_X    = 0.asUInt(3.W)

   // Memory Write Select Signal
   val MWS_REG  = 0.asUInt(2.W)
   val MWS_FREG = 1.asUInt(2.W)
   val MWS_X    = 0.asUInt(2.W)

   // Memory Write Signal
   val MWR_0   = false.B
   val MWR_1   = true.B
   val MWR_X   = false.B

   // Memory Enable Signal
   val MEN_0   = false.B
   val MEN_1   = true.B
   val MEN_X   = false.B

   // Memory Mask Type Signal
   val MSK_B   = 0.asUInt(3.W)
   val MSK_BU  = 1.asUInt(3.W)
   val MSK_H   = 2.asUInt(3.W)
   val MSK_HU  = 3.asUInt(3.W)
   val MSK_W   = 4.asUInt(3.W)
   val MSK_X   = 4.asUInt(3.W)

   // Rounding Mode
   val RM_RNE  = 0.asUInt(3.W)
   val RM_RTZ  = 1.asUInt(3.W)
   val RM_RDN  = 2.asUInt(3.W)
   val RM_RUP  = 3.asUInt(3.W)
   val RM_RMM  = 4.asUInt(3.W)
   val RM_DYN  = 7.asUInt(3.W)
   val RM_X    = 0.asUInt(3.W)
}

}

