//**************************************************************************
// RISCV Processor 5-Stage Control Path
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 20
//
// Supports both a fully-bypassed datapath (with stalls for load-use), and a
// fully interlocked (no bypass) datapath that stalls for all hazards.

package sodor.stage5

import chisel3._
import chisel3.util._

import freechips.rocketchip.rocket.{CSR, Causes}

import sodor.stage5.Constants._
import sodor.common._
import sodor.common.Instructions._

class CtlToDatIo extends Bundle()
{
   val dec_stall  = Output(Bool())    // stall IF/DEC stages (due to hazards)
   val full_stall = Output(Bool())    // stall entire pipeline (due to D$ misses)
   val exe_pc_sel = Output(UInt(2.W))
   val br_type    = Output(UInt(4.W))
   val if_kill    = Output(Bool())
   val dec_kill   = Output(Bool())
   val op1_sel    = Output(UInt(2.W))
   val op2_sel    = Output(UInt(3.W))
   val alu_fun    = Output(UInt(5.W))  // Modified from 4 bits to 5 bits
   val fpu_fun    = Output(UInt(5.W))  // F extension
   val wb_sel     = Output(UInt(3.W))
   val fwb_sel    = Output(UInt(3.W))  // F extension
   val rf_wen     = Output(Bool())
   val frf_wen    = Output(Bool())     // F extension
   val mem_wr_sel = Output(UInt(2.W))     // F extension
   val mem_val    = Output(Bool())
   val mem_fcn    = Output(UInt(2.W))
   val mem_typ    = Output(UInt(3.W))
   val csr_cmd    = Output(UInt(CSR.SZ.W))
   val fencei     = Output(Bool())    // pipeline is executing a fencei

   val pipeline_kill = Output(Bool()) // an exception occurred (detected in mem stage).
                                    // Kill the entire pipeline disregard stalls
                                    // and kill if,dec,exe stages.
   val mem_exception = Output(Bool()) // tell the CSR that the core detected an exception
   val mem_exception_cause = Output(UInt(32.W))
}

class CpathIo(implicit val conf: SodorCoreParams) extends Bundle()
{
   val dcpath = Flipped(new DebugCPath())
   val imem = new MemPortIo(conf.xprlen)
   val dmem = new MemPortIo(conf.xprlen)
   val dat  = Flipped(new DatToCtlIo())
   val ctl  = new CtlToDatIo()
}


class CtlPath(implicit val conf: SodorCoreParams) extends Module
{
  val io = IO(new CpathIo())
  io := DontCare

   val csignals =
      ListLookup(io.dat.dec_inst,
                             List(N, BR_N  , OP1_X , OP2_X    , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  ,  REN_0,  FREN_0, MEN_0, M_X  , MWS_X   , MT_X, CSR.N, N),
               Array(       /* val  |  BR  |  op1  |   op2     |  R1  |  R2  |  FR1  |  FR2  |  ALU    |  FPU    |  wb   |  fwb   | rf   | rf    | mem  | mem  | mem      | mask | csr | fence.i */
                            /* inst | type |   sel |    sel    |  oen |  oen |  oen  |  oen  |   fcn   |   fcn   |  sel  |  sel   | wen  | wen   |  en  |  wr  | wr sel   | type | cmd |         */
                  LW     -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_MEM, FWB_X  , REN_1, FREN_0, MEN_1, M_XRD, MWS_REG  , MT_W, CSR.N, N),
                  LB     -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_MEM, FWB_X  , REN_1, FREN_0, MEN_1, M_XRD, MWS_REG  , MT_B, CSR.N, N),
                  LBU    -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_MEM, FWB_X  , REN_1, FREN_0, MEN_1, M_XRD, MWS_REG  , MT_BU,CSR.N, N),
                  LH     -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_MEM, FWB_X  , REN_1, FREN_0, MEN_1, M_XRD, MWS_REG  , MT_H, CSR.N, N),
                  LHU    -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_MEM, FWB_X  , REN_1, FREN_0, MEN_1, M_XRD, MWS_REG  , MT_HU,CSR.N, N),
                  SW     -> List(Y, BR_N  , OP1_RS1, OP2_STYPE , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_1, M_XWR, MWS_REG  , MT_W, CSR.N, N),
                  SB     -> List(Y, BR_N  , OP1_RS1, OP2_STYPE , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_1, M_XWR, MWS_REG  , MT_B, CSR.N, N),
                  SH     -> List(Y, BR_N  , OP1_RS1, OP2_STYPE , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_1, M_XWR, MWS_REG  , MT_H, CSR.N, N),

                  AUIPC  -> List(Y, BR_N  , OP1_PC , OP2_UTYPE , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_ADD   , FPU_X   ,WB_ALU, FWB_X  ,REN_1, FREN_0, MEN_0, M_X , MWS_REG  , MT_X,  CSR.N, N),
                  LUI    -> List(Y, BR_N  , OP1_X  , OP2_UTYPE , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_COPY_2, FPU_X   ,WB_ALU, FWB_X  ,REN_1, FREN_0, MEN_0, M_X , MWS_REG  , MT_X,  CSR.N, N),

                  ADDI   -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  ANDI   -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_AND , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  ORI    -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_OR  , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  XORI   -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_XOR , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SLTI   -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_SLT , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SLTIU  -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_SLTU, FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SLLI_RV32->List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_SLL , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SRAI_RV32->List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_SRA , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SRLI_RV32->List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_SRL , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),

                  SLL    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SLL , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  ADD    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SUB    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SUB , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SLT    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SLT , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SLTU   -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SLTU, FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  AND    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_AND , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  OR     -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_OR  , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  XOR    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_XOR , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SRA    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SRA , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  SRL    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_SRL , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),

                  JAL    -> List(Y, BR_J  , OP1_RS1, OP2_UJTYPE, OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_PC4, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  JALR   -> List(Y, BR_JR , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_PC4, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BEQ    -> List(Y, BR_EQ , OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BNE    -> List(Y, BR_NE , OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BGE    -> List(Y, BR_GE , OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BGEU   -> List(Y, BR_GEU, OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BLT    -> List(Y, BR_LT , OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  BLTU   -> List(Y, BR_LTU, OP1_RS1, OP2_SBTYPE, OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),

                  CSRRWI -> List(Y, BR_N  , OP1_IMZ, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.W, N),
                  CSRRSI -> List(Y, BR_N  , OP1_IMZ, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.S, N),
                  CSRRW  -> List(Y, BR_N  , OP1_RS1, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.W, N),
                  CSRRS  -> List(Y, BR_N  , OP1_RS1, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.S, N),
                  CSRRC  -> List(Y, BR_N  , OP1_RS1, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.C, N),
                  CSRRCI -> List(Y, BR_N  , OP1_IMZ, OP2_X     , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_COPY_1, FPU_X   ,WB_CSR, FWB_X  ,REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.C, N),

                  ECALL  -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.I, N),
                  MRET   -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.I, N),
                  DRET   -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.I, N),
                  EBREAK -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.I, N),
                  WFI    -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X  , FPU_X    , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N), // implemented as a NOP

                  FENCE_I-> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, Y),
                  // kill pipeline and refetch instructions since the pipeline will be holding stall instructions.
                  FENCE  -> List(Y, BR_N  , OP1_X  , OP2_X     , OEN_0, OEN_0,  OEN_0,  OEN_0, ALU_X   , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  // we are already sequentially consistent, so no need to honor the fence instruction
                  
                  // M extension
                  MUL    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_MUL    , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  MULH   -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_MULH   , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  MULHSU -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_MULHSU , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  MULHU  -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_MULHU  , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  DIV    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_DIV    , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  DIVU   -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_DIVU   , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  REM    -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_REM    , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  REMU   -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_1, OEN_1,  OEN_0,  OEN_0, ALU_REMU   , FPU_X   , WB_ALU, FWB_X  , REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  
                  // F extension
                  FLW    -> List(Y, BR_N  , OP1_RS1, OP2_ITYPE , OEN_1, OEN_0,  OEN_0,  OEN_0, ALU_ADD , FPU_X   , WB_X  , FWB_MEM, REN_0, FREN_1, MEN_1, M_XRD, MWS_REG  , MT_W, CSR.N, N),
                  FSW    -> List(Y, BR_N  , OP1_RS1, OP2_STYPE , OEN_1, OEN_0,  OEN_0,  OEN_1, ALU_ADD , FPU_X   , WB_X  , FWB_X  , REN_0, FREN_0, MEN_1, M_XWR, MWS_FREG , MT_W, CSR.N, N),

                  FADD_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FADD_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FSUB_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FSUB_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FMUL_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FMUL_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FDIV_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FDIV_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FSQRT_S-> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_0, ALU_X   , FPU_FSQRT_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FSGNJ_S-> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FSGNJ_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FSGNJN_S->List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FSGNJN_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FSGNJX_S->List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FSGNJX_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FMIN_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FMIN_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FMAX_S -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FMAX_S, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FEQ_S  -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FEQ_S , WB_FPU, FWB_X, REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FLT_S  -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FLT_S , WB_FPU, FWB_X, REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FLE_S  -> List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FLE_S , WB_FPU, FWB_X, REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  FCLASS_S->List(Y, BR_N  , OP1_RS1, OP2_RS2   , OEN_0, OEN_0,  OEN_1,  OEN_0, ALU_X   , FPU_FCLASS_S, WB_FPU, FWB_X, REN_1, FREN_0, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  // FMV_X_W-> List(Y, BR_N  , OP1_RS1, OP2_X     , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FMV_X_W, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  // FMV_W_X-> List(Y, BR_N  , OP1_RS1, OP2_X     , OEN_0, OEN_0,  OEN_1,  OEN_1, ALU_X   , FPU_FMV_W_X, WB_X, FWB_FPU, REN_0, FREN_1, MEN_0, M_X  , MWS_REG  , MT_X, CSR.N, N),
                  ))

   // Put these control signals in variables
   val (cs_val_inst: Bool) :: cs_br_type :: cs_op1_sel :: cs_op2_sel :: (cs_rs1_oen: Bool) :: (cs_rs2_oen: Bool) :: (cs_frs1_oen: Bool) :: (cs_frs2_oen: Bool) :: cs0 = csignals
   val cs_alu_fun :: cs_fpu_fun :: cs_wb_sel :: cs_fwb_sel :: (cs_rf_wen: Bool) :: (cs_frf_wen: Bool) :: (cs_mem_en: Bool) :: cs_mem_fcn :: cs_mem_wr_sel :: cs_msk_sel :: cs_csr_cmd :: (cs_fencei: Bool) :: Nil = cs0


   // Branch Logic
   val ctrl_exe_pc_sel = Mux(io.ctl.pipeline_kill         , PC_EXC,
                         Mux(io.dat.exe_br_type === BR_N  , PC_4,
                         Mux(io.dat.exe_br_type === BR_NE , Mux(!io.dat.exe_br_eq,  PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_EQ , Mux( io.dat.exe_br_eq,  PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_GE , Mux(!io.dat.exe_br_lt,  PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_GEU, Mux(!io.dat.exe_br_ltu, PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_LT , Mux( io.dat.exe_br_lt,  PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_LTU, Mux( io.dat.exe_br_ltu, PC_BRJMP, PC_4),
                         Mux(io.dat.exe_br_type === BR_J  , PC_BRJMP,
                         Mux(io.dat.exe_br_type === BR_JR , PC_JALR,
                                                            PC_4
                     ))))))))))

   val ifkill  = (ctrl_exe_pc_sel =/= PC_4) || cs_fencei || RegNext(cs_fencei)
   val deckill = (ctrl_exe_pc_sel =/= PC_4)

   // Exception Handling ---------------------

   io.ctl.pipeline_kill := (io.dat.csr_eret || io.ctl.mem_exception || io.dat.csr_interrupt)

   val dec_illegal = (!cs_val_inst && io.dat.dec_valid)

   // Stall Signal Logic --------------------
   val stall   = Wire(Bool())

   val dec_rs1_addr = io.dat.dec_inst(19, 15)
   val dec_rs2_addr = io.dat.dec_inst(24, 20)
   val dec_wbaddr   = io.dat.dec_inst(11, 7)
   val dec_rs1_oen  = Mux(deckill, false.B, cs_rs1_oen)
   val dec_rs2_oen  = Mux(deckill, false.B, cs_rs2_oen)
   // F extension
   val dec_rs1_faddr = io.dat.dec_inst(19, 15)
   val dec_rs2_faddr = io.dat.dec_inst(24, 20)
   val dec_fwbaddr  = io.dat.dec_inst(11, 7)
   val dec_frs1_oen = Mux(deckill, false.B, cs_frs1_oen)
   val dec_frs2_oen = Mux(deckill, false.B, cs_frs2_oen)

   val exe_reg_wbaddr      = Reg(UInt())
   val mem_reg_wbaddr      = Reg(UInt())
   val wb_reg_wbaddr       = Reg(UInt())
   val exe_reg_ctrl_rf_wen = RegInit(false.B)
   val mem_reg_ctrl_rf_wen = RegInit(false.B)
   val wb_reg_ctrl_rf_wen  = RegInit(false.B)
   val exe_reg_illegal     = RegInit(false.B)
   // F extension
   val exe_reg_fwbaddr     = Reg(UInt())
   val mem_reg_fwbaddr     = Reg(UInt())
   val wb_reg_fwbaddr      = Reg(UInt())
   val exe_reg_ctrl_frf_wen = RegInit(false.B)
   val mem_reg_ctrl_frf_wen = RegInit(false.B)
   val wb_reg_ctrl_frf_wen  = RegInit(false.B)

   val exe_reg_is_csr = RegInit(false.B)

   // TODO rename stall==hazard_stall full_stall == cmiss_stall
   val full_stall = Wire(Bool())
   when (!stall && !full_stall)
   {
      when (deckill)
      {
         exe_reg_wbaddr      := 0.U
         exe_reg_ctrl_rf_wen := false.B
         exe_reg_is_csr      := false.B
         exe_reg_illegal     := false.B
         // F extension
         exe_reg_fwbaddr     := 0.U
         exe_reg_ctrl_frf_wen := false.B
      }
      .otherwise
      {
         exe_reg_wbaddr      := dec_wbaddr
         exe_reg_ctrl_rf_wen := cs_rf_wen
         exe_reg_is_csr      := cs_csr_cmd =/= CSR.N && cs_csr_cmd =/= CSR.I
         exe_reg_illegal     := dec_illegal
         // F extension
         exe_reg_fwbaddr     := dec_fwbaddr
         exe_reg_ctrl_frf_wen := cs_frf_wen
      }
   }
   .elsewhen (stall && !full_stall)
   {
      // kill exe stage
      exe_reg_wbaddr      := 0.U
      exe_reg_ctrl_rf_wen := false.B
      exe_reg_is_csr      := false.B
      exe_reg_illegal     := false.B
      // F extension
      exe_reg_fwbaddr     := 0.U
      exe_reg_ctrl_frf_wen := false.B
   }
   when (!full_stall) {
     mem_reg_wbaddr      := exe_reg_wbaddr
     wb_reg_wbaddr       := mem_reg_wbaddr
     mem_reg_ctrl_rf_wen := exe_reg_ctrl_rf_wen
     wb_reg_ctrl_rf_wen  := mem_reg_ctrl_rf_wen
     // F extension
     mem_reg_fwbaddr     := exe_reg_fwbaddr
     wb_reg_fwbaddr      := mem_reg_fwbaddr
     mem_reg_ctrl_frf_wen := exe_reg_ctrl_frf_wen
     wb_reg_ctrl_frf_wen  := mem_reg_ctrl_frf_wen
   }

   val exe_inst_is_load = RegInit(false.B)
   val exe_inst_is_fload = RegInit(false.B)

   when (!full_stall)
   {
      exe_inst_is_load := cs_mem_en && (cs_mem_fcn === M_XRD) && cs_rf_wen
      exe_inst_is_fload := cs_mem_en && (cs_mem_fcn === M_XRD) && cs_frf_wen  // F extension
   }

   // Clear instruction exception (from the "instruction" following xret) when returning from trap
   when (io.dat.csr_eret)
   {
      exe_reg_illegal    := false.B
   }

   // Stall signal stalls instruction fetch & decode stages,
   // inserts NOP into execute stage,  and drains execute, memory, and writeback stages
   // stalls on I$ misses and on hazards
   if (USE_FULL_BYPASSING)
   {
      // stall for load-use hazard
      stall := ((exe_inst_is_load) && (exe_reg_wbaddr === dec_rs1_addr) && (exe_reg_wbaddr =/= 0.U) && dec_rs1_oen) ||
               ((exe_inst_is_load) && (exe_reg_wbaddr === dec_rs2_addr) && (exe_reg_wbaddr =/= 0.U) && dec_rs2_oen) ||
               ((exe_inst_is_fload) && (exe_reg_fwbaddr === dec_rs1_faddr) && dec_frs1_oen) ||  // F extension
               ((exe_inst_is_fload) && (exe_reg_fwbaddr === dec_rs2_faddr) && dec_frs2_oen) ||
               (exe_reg_is_csr)
   }
   else
   {
      // stall for all hazards
      stall := ((exe_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && exe_reg_ctrl_rf_wen && dec_rs1_oen) ||
               ((mem_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && mem_reg_ctrl_rf_wen && dec_rs1_oen) ||
               ((wb_reg_wbaddr  === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen && dec_rs1_oen) ||
               ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && dec_rs2_oen) ||
               ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && mem_reg_ctrl_rf_wen && dec_rs2_oen) ||
               ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen && dec_rs2_oen) ||
               ((exe_inst_is_load) && (exe_reg_wbaddr === dec_rs1_addr) && (exe_reg_wbaddr =/= 0.U) && dec_rs1_oen) ||
               ((exe_inst_is_load) && (exe_reg_wbaddr === dec_rs2_addr) && (exe_reg_wbaddr =/= 0.U) && dec_rs2_oen) ||
               ((exe_reg_fwbaddr === dec_rs1_faddr) && exe_reg_ctrl_frf_wen && dec_frs1_oen) ||   // F extension
               ((mem_reg_fwbaddr === dec_rs1_faddr) && mem_reg_ctrl_frf_wen && dec_frs1_oen) ||
               ((wb_reg_fwbaddr  === dec_rs1_faddr) &&  wb_reg_ctrl_frf_wen && dec_frs1_oen) ||
               ((exe_reg_fwbaddr === dec_rs2_faddr) && exe_reg_ctrl_frf_wen && dec_frs2_oen) ||
               ((mem_reg_fwbaddr === dec_rs2_faddr) && mem_reg_ctrl_frf_wen && dec_frs2_oen) ||
               ((wb_reg_fwbaddr  === dec_rs2_faddr) &&  wb_reg_ctrl_frf_wen && dec_frs2_oen) ||
               ((exe_inst_is_fload) && (exe_reg_fwbaddr === dec_rs1_faddr) && dec_frs1_oen) |
               ((exe_inst_is_fload) && (exe_reg_fwbaddr === dec_rs2_faddr) && dec_frs2_oen) ||
               ((exe_reg_is_csr))
   }


   // stall full pipeline on D$ miss
   val dmem_val   = io.dat.mem_ctrl_dmem_val
   full_stall := !((dmem_val && io.dmem.resp.valid) || !dmem_val)


   io.ctl.dec_stall  := stall // stall if, dec stage (pipeline hazard)
   io.ctl.full_stall := full_stall // stall entire pipeline (cache miss)
   io.ctl.exe_pc_sel := ctrl_exe_pc_sel
   io.ctl.br_type    := cs_br_type
   io.ctl.if_kill    := ifkill
   io.ctl.dec_kill   := deckill
   io.ctl.op1_sel    := cs_op1_sel
   io.ctl.op2_sel    := cs_op2_sel
   io.ctl.alu_fun    := cs_alu_fun
   io.ctl.fpu_fun    := cs_fpu_fun  // F extension
   io.ctl.wb_sel     := cs_wb_sel
   io.ctl.fwb_sel    := cs_fwb_sel  // F extension
   io.ctl.rf_wen     := cs_rf_wen
   io.ctl.frf_wen    := cs_frf_wen  // F extension
   io.ctl.mem_wr_sel := cs_mem_wr_sel

   // we need to stall IF while fencei goes through DEC and EXE, as there may
   // be a store we need to wait to clear in MEM.
   io.ctl.fencei     := cs_fencei || RegNext(cs_fencei)

   // Exception priority matters!
   io.ctl.mem_exception := RegNext((exe_reg_illegal || io.dat.exe_inst_misaligned) && !io.dat.csr_eret) || io.dat.mem_data_misaligned
   io.ctl.mem_exception_cause := Mux(RegNext(exe_reg_illegal),            Causes.illegal_instruction.U,
                                 Mux(RegNext(io.dat.exe_inst_misaligned), Causes.misaligned_fetch.U,
                                 Mux(io.dat.mem_store,                    Causes.misaligned_store.U,
                                                                          Causes.misaligned_load.U
                                 )))

   // convert CSR instructions with raddr1 == 0 to read-only CSR commands
   val rs1_addr = io.dat.dec_inst(RS1_MSB, RS1_LSB)
   val csr_ren = (cs_csr_cmd === CSR.S || cs_csr_cmd === CSR.C) && rs1_addr === 0.U
   io.ctl.csr_cmd := Mux(csr_ren, CSR.R, cs_csr_cmd)

   io.ctl.mem_val    := cs_mem_en
   io.ctl.mem_fcn    := cs_mem_fcn
   io.ctl.mem_typ    := cs_msk_sel

}
