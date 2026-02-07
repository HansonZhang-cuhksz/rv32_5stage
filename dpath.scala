//**************************************************************************
// RISCV Processor 5-Stage Datapath
//--------------------------------------------------------------------------
//
// Christopher Celio
// 2012 Jan 13
//
// TODO refactor stall, kill, fencei, flush signals. They're more confusing than they need to be.

package sodor.stage5

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket.{CSR, CSRFile, Causes}
import freechips.rocketchip.rocket.CoreInterrupts

import sodor.stage5.Constants._
import sodor.common._

// F extension
import hardfloat._

class DatToCtlIo(implicit val conf: SodorCoreParams) extends Bundle()
{
   val dec_inst    = Output(UInt(conf.xprlen.W))
   val dec_valid   = Output(Bool())
   val exe_br_eq   = Output(Bool())
   val exe_br_lt   = Output(Bool())
   val exe_br_ltu  = Output(Bool())
   val exe_br_type = Output(UInt(4.W))
   val exe_inst_misaligned = Output(Bool())

   val mem_ctrl_dmem_val = Output(Bool())
   val mem_data_misaligned = Output(Bool())
   val mem_store = Output(Bool())

   val csr_eret = Output(Bool())
   val csr_interrupt = Output(Bool())
}

class DpathIo(implicit val p: Parameters, val conf: SodorCoreParams) extends Bundle
{
   val ddpath = Flipped(new DebugDPath())
   val imem = new MemPortIo(conf.xprlen)
   val dmem = new MemPortIo(conf.xprlen)
   val ctl  = Flipped(new CtlToDatIo())
   val dat  = new DatToCtlIo()
   val interrupt = Input(new CoreInterrupts(false))
   val hartid = Input(UInt())
   val reset_vector = Input(UInt())
}

class DatPath(implicit val p: Parameters, val conf: SodorCoreParams) extends Module
{
   val io = IO(new DpathIo())
   io := DontCare

   val csr = Module(new CSRFile(perfEventSets=CSREvents.events))
   csr.io := DontCare
   val fflags = RegInit(0.asUInt(5.W))
   val frm = RegInit(0.asUInt(3.W))

   //**********************************
   // Exception handling values (all read during mem_stage)
   val mem_tval_data_ma = Wire(UInt(conf.xprlen.W))
   val mem_tval_inst_ma = Wire(UInt(conf.xprlen.W))

   //**********************************

   //**********************************
   // Pipeline State Registers

   // Instruction Fetch State
   val if_reg_pc             = RegInit(io.reset_vector)

   // Instruction Decode State
   val dec_reg_valid         = RegInit(false.B)
   val dec_reg_inst          = RegInit(BUBBLE)
   val dec_reg_pc            = RegInit(0.asUInt(conf.xprlen.W))

   // Execute State
   val exe_reg_valid         = RegInit(false.B)
   val exe_reg_inst          = RegInit(BUBBLE)
   val exe_reg_pc            = RegInit(0.asUInt(conf.xprlen.W))
   val exe_reg_wbaddr        = Reg(UInt(5.W))
   val exe_reg_rs1_addr      = Reg(UInt(5.W))
   val exe_reg_rs2_addr      = Reg(UInt(5.W))
   val exe_reg_op1_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_op2_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_rs2_data      = Reg(UInt(conf.xprlen.W))
   val exe_reg_ctrl_br_type  = RegInit(BR_N)
   val exe_reg_ctrl_op2_sel  = Reg(UInt())
   val exe_reg_ctrl_alu_fun  = Reg(UInt())
   val exe_reg_ctrl_wb_sel   = Reg(UInt())
   val exe_reg_ctrl_rf_wen   = RegInit(false.B)
   val exe_reg_ctrl_mem_val  = RegInit(false.B)
   val exe_reg_ctrl_mem_fcn  = RegInit(M_X)
   val exe_reg_ctrl_mem_typ  = RegInit(MT_X)
   val exe_reg_ctrl_csr_cmd  = RegInit(CSR.N)
   // F extension
   val exe_reg_fwbaddr       = Reg(UInt(5.W))
   val exe_reg_rs1_faddr     = Reg(UInt(5.W))
   val exe_reg_rs2_faddr     = Reg(UInt(5.W))
   val exe_reg_rs3_faddr     = Reg(UInt(5.W))
   val exe_reg_fpu_op1_data  = Reg(UInt(conf.xprlen.W))
   val exe_reg_fpu_op2_data  = Reg(UInt(conf.xprlen.W))
   val exe_reg_fpu_op3_data  = Reg(UInt(conf.xprlen.W))
   val exe_reg_ctrl_fpu_fun  = Reg(UInt())
   val exe_reg_ctrl_fwb_sel  = Reg(UInt())
   val exe_reg_ctrl_frf_wen  = RegInit(false.B)
   val exe_reg_ctrl_mem_wr_sel = Reg(UInt())
   val exe_reg_rm            = RegInit(0.asUInt(3.W))

   // Memory State
   val mem_reg_valid         = RegInit(false.B)
   val mem_reg_pc            = Reg(UInt(conf.xprlen.W))
   val mem_reg_inst          = Reg(UInt(conf.xprlen.W))
   val mem_reg_alu_out       = Reg(Bits())
   val mem_reg_wbaddr        = Reg(UInt())
   val mem_reg_rs1_addr      = Reg(UInt())
   val mem_reg_rs2_addr      = Reg(UInt())
   val mem_reg_op1_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_op2_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_rs2_data      = Reg(UInt(conf.xprlen.W))
   val mem_reg_ctrl_rf_wen   = RegInit(false.B)
   val mem_reg_ctrl_mem_val  = RegInit(false.B)
   val mem_reg_ctrl_mem_fcn  = RegInit(M_X)
   val mem_reg_ctrl_mem_typ  = RegInit(MT_X)
   val mem_reg_ctrl_wb_sel   = Reg(UInt())
   val mem_reg_ctrl_csr_cmd  = RegInit(CSR.N)
   // F extension
   val mem_reg_fpu_out       = Reg(Bits())
   val mem_reg_fwbaddr       = Reg(UInt())
   val mem_reg_rs1_faddr     = Reg(UInt())
   val mem_reg_rs2_faddr     = Reg(UInt())
   val mem_reg_rs3_faddr     = Reg(UInt())
   val mem_reg_fpu_op1_data  = Reg(UInt(conf.xprlen.W))
   val mem_reg_fpu_op2_data  = Reg(UInt(conf.xprlen.W))
   val mem_reg_fpu_op3_data  = Reg(UInt(conf.xprlen.W))
   val mem_reg_ctrl_frf_wen  = RegInit(false.B)
   val mem_reg_ctrl_fwb_sel  = Reg(UInt())
   val mem_reg_ctrl_mem_wr_sel = Reg(UInt())
   val mem_reg_fflags        = Reg(UInt())

   // Writeback State
   val wb_reg_valid          = RegInit(false.B)
   val wb_reg_wbaddr         = Reg(UInt())
   val wb_reg_wbdata         = Reg(UInt(conf.xprlen.W))
   val wb_reg_ctrl_rf_wen    = RegInit(false.B)
   // F extension
   val wb_reg_fwbaddr        = Reg(UInt())
   val wb_reg_fwbdata        = Reg(UInt(conf.xprlen.W))
   val wb_reg_ctrl_frf_wen   = RegInit(false.B)

   //**********************************
   // Instruction Fetch Stage
   val if_pc_next          = Wire(UInt(32.W))
   val exe_brjmp_target    = Wire(UInt(32.W))
   val exe_jump_reg_target = Wire(UInt(32.W))
   val exception_target    = Wire(UInt(32.W))

   // Instruction fetch buffer
   val if_buffer_in = Wire(new DecoupledIO(new MemResp(conf.xprlen)))
   if_buffer_in.bits := io.imem.resp.bits
   if_buffer_in.valid := io.imem.resp.valid
   assert(!(if_buffer_in.valid && !if_buffer_in.ready), "Instruction backlog")

   val if_buffer_out = Queue(if_buffer_in, entries = 1, pipe = false, flow = true)
   if_buffer_out.ready := !io.ctl.dec_stall && !io.ctl.full_stall

   // Instruction PC buffer
   val if_pc_buffer_in = Wire(new DecoupledIO(UInt(conf.xprlen.W)))
   if_pc_buffer_in.bits := if_reg_pc
   if_pc_buffer_in.valid := if_buffer_in.valid

   val if_pc_buffer_out = Queue(if_pc_buffer_in, entries = 1, pipe = false, flow = true)
   if_pc_buffer_out.ready := if_buffer_out.ready

   // Instruction fetch kill flag buffer
   val if_reg_killed = RegInit(false.B)
   when ((io.ctl.pipeline_kill || io.ctl.if_kill) && !if_buffer_out.fire)
   {
      if_reg_killed := true.B
   }
   when (if_reg_killed && if_buffer_out.fire)
   {
      if_reg_killed := false.B
   }

   // Do not change the PC again if the instruction is killed in previous cycles (when the PC has changed)
   when ((if_buffer_in.fire && !if_reg_killed) || io.ctl.if_kill || io.ctl.pipeline_kill)
   {
      if_reg_pc := if_pc_next
   }

   val if_pc_plus4 = (if_reg_pc + 4.asUInt(conf.xprlen.W))

   if_pc_next := Mux(io.ctl.exe_pc_sel === PC_4,      if_pc_plus4,
                 Mux(io.ctl.exe_pc_sel === PC_BRJMP,  exe_brjmp_target,
                 Mux(io.ctl.exe_pc_sel === PC_JALR,   exe_jump_reg_target,
                 /*Mux(io.ctl.exe_pc_sel === PC_EXC*/ exception_target)))

   // for a fencei, refetch the if_pc (assuming no stall, no branch, and no exception)
   when (io.ctl.fencei && io.ctl.exe_pc_sel === PC_4 &&
         !io.ctl.dec_stall && !io.ctl.full_stall && !io.ctl.pipeline_kill)
   {
      if_pc_next := if_reg_pc
   }

   // Instruction Memory
   io.imem.req.valid := if_buffer_in.ready
   io.imem.req.bits.fcn := M_XRD
   io.imem.req.bits.typ := MT_WU
   io.imem.req.bits.addr := if_reg_pc

   when (io.ctl.pipeline_kill)
   {
      dec_reg_valid := false.B
      dec_reg_inst := BUBBLE
   }
   .elsewhen (!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      when (io.ctl.if_kill || if_reg_killed)
      {
         dec_reg_valid := false.B
         dec_reg_inst := BUBBLE
      }
      .elsewhen (if_buffer_out.valid)
      {
         dec_reg_valid := true.B
         dec_reg_inst := if_buffer_out.bits.data
      }
      .otherwise
      {
         dec_reg_valid := false.B
         dec_reg_inst := BUBBLE
      }

      dec_reg_pc := if_pc_buffer_out.bits
   }


   //**********************************
   // Decode Stage
   val dec_rs1_addr = dec_reg_inst(19, 15)
   val dec_rs2_addr = dec_reg_inst(24, 20)
   val dec_wbaddr   = dec_reg_inst(11, 7)
   // FP RegFile
   val dec_rs1_faddr = dec_reg_inst(19, 15)
   val dec_rs2_faddr = dec_reg_inst(24, 20)
   val dec_rs3_faddr = dec_reg_inst(31, 27)   // for FMA instructions
   val dec_wb_faddr  = dec_reg_inst(11, 7)
   val dec_rm_raw    = dec_reg_inst(14, 12)
   val dec_rm        = Mux(dec_rm_raw === RM_DYN, frm, dec_rm_raw)

   // Register File
   val regfile = Module(new RegisterFile())
   val f_regfile = Module(new FPRegisterFile())   // FP RegFile

   regfile.io.rs1_addr := dec_rs1_addr
   regfile.io.rs2_addr := dec_rs2_addr
   val rf_rs1_data = regfile.io.rs1_data
   val rf_rs2_data = regfile.io.rs2_data
   regfile.io.waddr := wb_reg_wbaddr
   regfile.io.wdata := wb_reg_wbdata
   regfile.io.wen   := wb_reg_ctrl_rf_wen

   // FP RegFile
   f_regfile.io.rs1_addr := dec_rs1_faddr
   f_regfile.io.rs2_addr := dec_rs2_faddr
   f_regfile.io.rs3_addr := dec_rs3_faddr
   val f_rf_rs1_data = f_regfile.io.rs1_data
   val f_rf_rs2_data = f_regfile.io.rs2_data
   val f_rf_rs3_data = f_regfile.io.rs3_data
   f_regfile.io.waddr := wb_reg_fwbaddr
   f_regfile.io.wdata := wb_reg_fwbdata
   f_regfile.io.wen   := wb_reg_ctrl_frf_wen

   //// DebugModule
   regfile.io.dm_addr := io.ddpath.addr
   io.ddpath.rdata := regfile.io.dm_rdata
   regfile.io.dm_en := io.ddpath.validreq
   regfile.io.dm_wdata := io.ddpath.wdata

   // FP RegFile
   f_regfile.io.dm_addr := io.ddpath.faddr
   io.ddpath.frdata := f_regfile.io.dm_rdata
   f_regfile.io.dm_en := io.ddpath.fvalidreq
   f_regfile.io.dm_wdata := io.ddpath.fwdata
   ///

   // immediates
   val imm_itype  = dec_reg_inst(31,20)
   val imm_stype  = Cat(dec_reg_inst(31,25), dec_reg_inst(11,7))
   val imm_sbtype = Cat(dec_reg_inst(31), dec_reg_inst(7), dec_reg_inst(30, 25), dec_reg_inst(11,8))
   val imm_utype  = dec_reg_inst(31, 12)
   val imm_ujtype = Cat(dec_reg_inst(31), dec_reg_inst(19,12), dec_reg_inst(20), dec_reg_inst(30,21))

   val imm_z = Cat(Fill(27,0.U), dec_reg_inst(19,15))

   // sign-extend immediates
   val imm_itype_sext  = Cat(Fill(20,imm_itype(11)), imm_itype)
   val imm_stype_sext  = Cat(Fill(20,imm_stype(11)), imm_stype)
   val imm_sbtype_sext = Cat(Fill(19,imm_sbtype(11)), imm_sbtype, 0.U)
   val imm_utype_sext  = Cat(imm_utype, Fill(12,0.U))
   val imm_ujtype_sext = Cat(Fill(11,imm_ujtype(19)), imm_ujtype, 0.U)

   // Operand 2 Mux
   val dec_alu_op2 = MuxCase(0.U, Array(
               (io.ctl.op2_sel === OP2_RS2)    -> rf_rs2_data,
               (io.ctl.op2_sel === OP2_ITYPE)  -> imm_itype_sext,
               (io.ctl.op2_sel === OP2_STYPE)  -> imm_stype_sext,
               (io.ctl.op2_sel === OP2_SBTYPE) -> imm_sbtype_sext,
               (io.ctl.op2_sel === OP2_UTYPE)  -> imm_utype_sext,
               (io.ctl.op2_sel === OP2_UJTYPE) -> imm_ujtype_sext,
               )).asUInt



   // Bypass Muxes
   val exe_alu_out  = Wire(UInt(conf.xprlen.W))
   val exe_fpu_out  = Wire(UInt(conf.xprlen.W))
   val mem_wbdata   = Wire(UInt(conf.xprlen.W))
   val mem_fwbdata  = Wire(UInt(conf.xprlen.W))

   val dec_op1_data = Wire(UInt(conf.xprlen.W))
   val dec_op2_data = Wire(UInt(conf.xprlen.W))
   val dec_rs2_data = Wire(UInt(conf.xprlen.W))
   val dec_fpu_op1_data = Wire(UInt(conf.xprlen.W))
   val dec_fpu_op2_data = Wire(UInt(conf.xprlen.W))
   val dec_fpu_op3_data = Wire(UInt(conf.xprlen.W))

   if (USE_FULL_BYPASSING)
   {
      // roll the OP1 mux into the bypass mux logic
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                           ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                           ((io.ctl.op1_sel === OP1_PC)) -> dec_reg_pc,
                           ((exe_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (exe_reg_ctrl_alu_fun =/= ALU_X)) -> exe_alu_out,
                           ((exe_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (exe_reg_ctrl_fpu_fun =/= FPU_X)) -> exe_fpu_out,
                           ((mem_reg_wbaddr === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs1_addr) && (dec_rs1_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))

      dec_op2_data := MuxCase(dec_alu_op2, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2) && (exe_reg_ctrl_alu_fun =/= ALU_X)) -> exe_alu_out,
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2) && (exe_reg_ctrl_fpu_fun =/= FPU_X)) -> exe_fpu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && mem_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen && (io.ctl.op2_sel === OP2_RS2)) -> wb_reg_wbdata
                           ))

      dec_rs2_data := MuxCase(rf_rs2_data, Array(
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (exe_reg_ctrl_alu_fun =/= ALU_X)) -> exe_alu_out,
                           ((exe_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && exe_reg_ctrl_rf_wen && (exe_reg_ctrl_fpu_fun =/= FPU_X)) -> exe_fpu_out,
                           ((mem_reg_wbaddr === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((wb_reg_wbaddr  === dec_rs2_addr) && (dec_rs2_addr =/= 0.U) &&  wb_reg_ctrl_rf_wen) -> wb_reg_wbdata
                           ))
      // F extension
      val dec_fpu_op1_intreg = (io.ctl.fpu_fun === FPU_FCVT_S_W) || (io.ctl.fpu_fun === FPU_FCVT_S_WU)
      dec_fpu_op1_data := MuxCase(Mux(dec_fpu_op1_intreg, rf_rs1_data, f_rf_rs1_data), Array(
                           ((exe_reg_wbaddr === dec_rs1_addr) && exe_reg_ctrl_rf_wen) -> exe_alu_out,
                           ((exe_reg_fwbaddr === dec_rs1_faddr) && exe_reg_ctrl_frf_wen) -> exe_fpu_out,
                           ((mem_reg_wbaddr === dec_rs1_addr) && mem_reg_ctrl_rf_wen) -> mem_wbdata,
                           ((mem_reg_fwbaddr === dec_rs1_faddr) && mem_reg_ctrl_frf_wen) -> mem_fwbdata,
                           ((wb_reg_fwbaddr  === dec_rs1_faddr) &&  wb_reg_ctrl_frf_wen) -> wb_reg_fwbdata
                           ))
      dec_fpu_op2_data := MuxCase(f_rf_rs2_data, Array(
                           ((exe_reg_fwbaddr === dec_rs2_faddr) && exe_reg_ctrl_frf_wen) -> exe_fpu_out,
                           ((mem_reg_fwbaddr === dec_rs2_faddr) && mem_reg_ctrl_frf_wen) -> mem_fwbdata,
                           ((wb_reg_fwbaddr  === dec_rs2_faddr) &&  wb_reg_ctrl_frf_wen) -> wb_reg_fwbdata
                           ))
      dec_fpu_op3_data := MuxCase(f_rf_rs3_data, Array(
                           ((exe_reg_fwbaddr === dec_rs3_faddr) && exe_reg_ctrl_frf_wen) -> exe_fpu_out,
                           ((mem_reg_fwbaddr === dec_rs3_faddr) && mem_reg_ctrl_frf_wen) -> mem_fwbdata,
                           ((wb_reg_fwbaddr  === dec_rs3_faddr) &&  wb_reg_ctrl_frf_wen) -> wb_reg_fwbdata
                           ))
   }
   else
   {
      // Rely only on control interlocking to resolve hazards
      dec_op1_data := MuxCase(rf_rs1_data, Array(
                          ((io.ctl.op1_sel === OP1_IMZ)) -> imm_z,
                          ((io.ctl.op1_sel === OP1_PC))  -> dec_reg_pc
                          ))
      dec_rs2_data := rf_rs2_data
      dec_op2_data := dec_alu_op2
      // F extension
      dec_fpu_op1_data := Mux((io.ctl.fpu_fun === FPU_FCVT_S_W) || (io.ctl.fpu_fun === FPU_FCVT_S_WU), rf_rs1_data, f_rf_rs1_data)
      dec_fpu_op2_data := f_rf_rs2_data
      dec_fpu_op3_data := f_rf_rs3_data
   }


   when ((io.ctl.dec_stall && !io.ctl.full_stall) || io.ctl.pipeline_kill)
   {
      // (kill exe stage)
      // insert NOP (bubble) into Execute stage on front-end stall (e.g., hazard clearing)
      exe_reg_valid         := false.B
      exe_reg_inst          := BUBBLE
      exe_reg_wbaddr        := 0.U
      exe_reg_ctrl_rf_wen   := false.B
      exe_reg_ctrl_mem_val  := false.B
      exe_reg_ctrl_mem_fcn  := M_X
      exe_reg_ctrl_csr_cmd  := CSR.N
      exe_reg_ctrl_br_type  := BR_N
      // F extension
      exe_reg_fwbaddr       := 0.U
      exe_reg_ctrl_frf_wen  := false.B
      exe_reg_ctrl_mem_wr_sel := 0.U
      exe_reg_rm              := 0.U
   }
   .elsewhen(!io.ctl.dec_stall && !io.ctl.full_stall)
   {
      // no stalling...
      exe_reg_pc            := dec_reg_pc
      exe_reg_rs1_addr      := dec_rs1_addr
      exe_reg_rs2_addr      := dec_rs2_addr
      exe_reg_op1_data      := dec_op1_data
      exe_reg_op2_data      := dec_op2_data
      exe_reg_rs2_data      := dec_rs2_data
      exe_reg_ctrl_op2_sel  := io.ctl.op2_sel
      exe_reg_ctrl_alu_fun  := io.ctl.alu_fun
      exe_reg_ctrl_wb_sel   := io.ctl.wb_sel
      // F extension
      exe_reg_rs1_faddr     := dec_rs1_faddr
      exe_reg_rs2_faddr     := dec_rs2_faddr
      exe_reg_rs3_faddr     := dec_rs3_faddr
      exe_reg_fpu_op1_data  := dec_fpu_op1_data
      exe_reg_fpu_op2_data  := dec_fpu_op2_data
      exe_reg_fpu_op3_data  := dec_fpu_op3_data
      exe_reg_ctrl_fpu_fun  := io.ctl.fpu_fun
      exe_reg_ctrl_fwb_sel  := io.ctl.fwb_sel
      exe_reg_ctrl_mem_wr_sel := io.ctl.mem_wr_sel

      when (io.ctl.dec_kill)
      {
         exe_reg_valid         := false.B
         exe_reg_inst          := BUBBLE
         exe_reg_wbaddr        := 0.U
         exe_reg_ctrl_rf_wen   := false.B
         exe_reg_ctrl_mem_val  := false.B
         exe_reg_ctrl_mem_fcn  := M_X
         exe_reg_ctrl_csr_cmd  := CSR.N
         exe_reg_ctrl_br_type  := BR_N
         // F extension
         exe_reg_fwbaddr       := 0.U
         exe_reg_ctrl_frf_wen  := false.B
         exe_reg_ctrl_mem_wr_sel := MWS_REG
         exe_reg_rm            := 0.U
      }
      .otherwise
      {
         exe_reg_valid         := dec_reg_valid
         exe_reg_inst          := dec_reg_inst
         exe_reg_wbaddr        := dec_wbaddr
         exe_reg_ctrl_rf_wen   := io.ctl.rf_wen
         exe_reg_ctrl_mem_val  := io.ctl.mem_val
         exe_reg_ctrl_mem_fcn  := io.ctl.mem_fcn
         exe_reg_ctrl_mem_typ  := io.ctl.mem_typ
         exe_reg_ctrl_csr_cmd  := io.ctl.csr_cmd
         exe_reg_ctrl_br_type  := io.ctl.br_type
         // F extension
         exe_reg_fwbaddr       := dec_wb_faddr
         exe_reg_ctrl_frf_wen  := io.ctl.frf_wen
         exe_reg_rm            := dec_rm
      }
   }

   //**********************************
   // Execute Stage

   val exe_alu_op1 = exe_reg_op1_data.asUInt
   val exe_alu_op2 = exe_reg_op2_data.asUInt
   // F extension
   val exe_fpu_op1 = exe_reg_fpu_op1_data.asUInt
   val exe_fpu_op2 = exe_reg_fpu_op2_data.asUInt
   val exe_fpu_op3 = exe_reg_fpu_op3_data.asUInt

   // ALU
   val alu_shamt     = exe_alu_op2(4,0).asUInt
   val exe_adder_out = (exe_alu_op1 + exe_alu_op2)(conf.xprlen-1,0)

   // Constants used by M-extension corner cases (avoid negative Scala Int literals)
   val INT_MIN = (BigInt(1) << (conf.xprlen - 1)).U(conf.xprlen.W)      // 0x8000... for xprlen
   val UINT_MAX = ((BigInt(1) << conf.xprlen) - 1).U(conf.xprlen.W)     // 0xffff... for xprlen

   //only for debug purposes right now until debug() works
   exe_alu_out := MuxCase(exe_reg_inst.asUInt, Array(
                  (exe_reg_ctrl_alu_fun === ALU_ADD)  -> exe_adder_out,
                  (exe_reg_ctrl_alu_fun === ALU_SUB)  -> (exe_alu_op1 - exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_AND)  -> (exe_alu_op1 & exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_OR)   -> (exe_alu_op1 | exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_XOR)  -> (exe_alu_op1 ^ exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLT)  -> (exe_alu_op1.asSInt < exe_alu_op2.asSInt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLTU) -> (exe_alu_op1 < exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SLL)  -> ((exe_alu_op1 << alu_shamt)(conf.xprlen-1, 0)).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRA)  -> (exe_alu_op1.asSInt >> alu_shamt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_SRL)  -> (exe_alu_op1 >> alu_shamt).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_1)-> exe_alu_op1,
                  (exe_reg_ctrl_alu_fun === ALU_COPY_2)-> exe_alu_op2,

                  // M extension
                  (exe_reg_ctrl_alu_fun === ALU_MUL)  -> (exe_alu_op1 * exe_alu_op2).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_MULH) -> ((exe_alu_op1.asSInt * exe_alu_op2.asSInt) >> conf.xprlen).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_MULHSU) -> ((exe_alu_op1.asSInt * exe_alu_op2.asUInt) >> conf.xprlen).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_MULHU) -> ((exe_alu_op1.asUInt * exe_alu_op2.asUInt) >> conf.xprlen).asUInt,
                  (exe_reg_ctrl_alu_fun === ALU_DIV)  -> Mux(exe_alu_op2 === 0.U, (-1).S(conf.xprlen.W).asUInt,
                                                        Mux(exe_alu_op1 === INT_MIN && exe_alu_op2 === UINT_MAX, exe_alu_op1,
                                                        (exe_alu_op1.asSInt / exe_alu_op2.asSInt).asUInt)),
                  (exe_reg_ctrl_alu_fun === ALU_DIVU) -> Mux(exe_alu_op2 === 0.U, (-1).S(conf.xprlen.W).asUInt,
                                                        (exe_alu_op1.asUInt / exe_alu_op2.asUInt).asUInt),
                  (exe_reg_ctrl_alu_fun === ALU_REM)  -> Mux(exe_alu_op2 === 0.U, (-1).S(conf.xprlen.W).asUInt,
                                                        Mux(exe_alu_op1 === INT_MIN && exe_alu_op2 === UINT_MAX, exe_alu_op1,
                                                        (exe_alu_op1.asSInt % exe_alu_op2.asSInt).asUInt)),
                  (exe_reg_ctrl_alu_fun === ALU_REMU) -> Mux(exe_alu_op2 === 0.U, (-1).S(conf.xprlen.W).asUInt,
                                                        (exe_alu_op1.asUInt % exe_alu_op2.asUInt).asUInt)
                  ))

   // FPU
   // Preprocess Operands
   val exe_fpu_op1_rec = recFNFromFN(8, 24, exe_fpu_op1)
   val exe_fpu_op2_rec = recFNFromFN(8, 24, exe_fpu_op2)
   val exe_fpu_op3_rec = recFNFromFN(8, 24, exe_fpu_op3)
   val exe_fpu_op3_rec_neg = recFNFromFN(8, 24, Cat(!exe_fpu_op3(31), exe_fpu_op3(30, 0)))

   // FADD / FSUB
   val fpu_adder = Module(new AddRecFN(8, 24))
   fpu_adder.io.subOp := (exe_reg_ctrl_fpu_fun === FPU_FSUB_S)
   fpu_adder.io.a := exe_fpu_op1_rec
   fpu_adder.io.b := exe_fpu_op2_rec
   fpu_adder.io.roundingMode := exe_reg_rm
   fpu_adder.io.detectTininess := 0.U

   // FMUL
   val fpu_mul = Module(new MulRecFN(8, 24))
   fpu_mul.io.a := exe_fpu_op1_rec
   fpu_mul.io.b := exe_fpu_op2_rec
   fpu_mul.io.roundingMode := exe_reg_rm
   fpu_mul.io.detectTininess := 0.U

   // Compare (ordered) helper for FMIN/FMAX
   val fpu_cmp = Module(new CompareRecFN(8, 24))
   fpu_cmp.io.a := exe_fpu_op1_rec
   fpu_cmp.io.b := exe_fpu_op2_rec
   fpu_cmp.io.signaling := false.B

   val a_isNaN = (exe_fpu_op1(30, 23) === "hff".U) && (exe_fpu_op1(22, 0) =/= 0.U)
   val b_isNaN = (exe_fpu_op2(30, 23) === "hff".U) && (exe_fpu_op2(22, 0) =/= 0.U)
   val ordered_lt = fpu_cmp.io.lt
   val ordered_eq = fpu_cmp.io.eq

   // Detect +0/-0 (in IEEE encoding, exponent+frac all zero)
   def isZeroFN(x: UInt): Bool = x(30, 0) === 0.U
   val a_isZero = isZeroFN(exe_fpu_op1)
   val b_isZero = isZeroFN(exe_fpu_op2)
   val a_sign = exe_fpu_op1(31)
   val b_sign = exe_fpu_op2(31)

   // When both are zeros, fmin picks -0, fmax picks +0
   val minZero = Cat(a_sign | b_sign, 0.U(31.W))  // if either is negative => -0
   val maxZero = Cat(a_sign & b_sign, 0.U(31.W))  // only negative if both negative => -0

   val a_fn = fNFromRecFN(8, 24, exe_fpu_op1_rec)
   val b_fn = fNFromRecFN(8, 24, exe_fpu_op2_rec)

   val fmin_fn =
      Mux(a_isNaN && b_isNaN, 0x7fc00000L.U(32.W), // canonical quiet NaN
      Mux(a_isNaN, b_fn,
      Mux(b_isNaN, a_fn,
      Mux(a_isZero && b_isZero, minZero,
      Mux(ordered_lt || ordered_eq, a_fn, b_fn)))))

   val fmax_fn =
      Mux(a_isNaN && b_isNaN, 0x7fc00000L.U(32.W),
      Mux(a_isNaN, b_fn,
      Mux(b_isNaN, a_fn,
      Mux(a_isZero && b_isZero, maxZero,
      Mux(ordered_lt || ordered_eq, b_fn, a_fn))))) 
   
   // RV32F compare results: if either operand is NaN => result is 0
   val anyNaN = a_isNaN || b_isNaN
   val feq_res = (!anyNaN && ordered_eq).asUInt
   val flt_res = (!anyNaN && ordered_lt).asUInt
   val fle_res = (!anyNaN && (ordered_lt || ordered_eq)).asUInt

   // Classify
   val fclass_res = classifyRecFN(8, 24, exe_fpu_op1_rec).asUInt

   // CVT
   val fpu_i2f = Module(new INToRecFN(32, 8, 24))
   fpu_i2f.io.signedIn := (exe_reg_ctrl_fpu_fun === FPU_FCVT_S_W)
   fpu_i2f.io.in := exe_reg_op1_data(31, 0)
   fpu_i2f.io.roundingMode := exe_reg_rm
   fpu_i2f.io.detectTininess := 0.U

   val fcvt_s_w_bits  = fNFromRecFN(8, 24, fpu_i2f.io.out)

   val fpu_f2i = Module(new RecFNToIN(8, 24, 32))
   fpu_f2i.io.in := exe_fpu_op1_rec
   fpu_f2i.io.roundingMode := exe_reg_rm
   fpu_f2i.io.signedOut := (exe_reg_ctrl_fpu_fun === FPU_FCVT_W_S)

   val fcvt_w_s_bits = fpu_f2i.io.out

   // FMA
   val fma_adder = Module(new AddRecFN(8, 24))
   fma_adder.io.subOp := (exe_reg_ctrl_fpu_fun === FPU_FMSUB_S) || (exe_reg_ctrl_fpu_fun === FPU_FNMSUB_S)
   fma_adder.io.a := fpu_mul.io.out
   fma_adder.io.b := exe_fpu_op3_rec
   fma_adder.io.roundingMode := exe_reg_rm
   fma_adder.io.detectTininess := 0.U
   val fma_adder_out = fNFromRecFN(8, 24, fma_adder.io.out)

   exe_fpu_out := MuxCase(exe_reg_inst.asUInt, Array(
                  (exe_reg_ctrl_fpu_fun === FPU_FADD_S)  -> fNFromRecFN(8, 24, fpu_adder.io.out),
                  (exe_reg_ctrl_fpu_fun === FPU_FSUB_S)  -> fNFromRecFN(8, 24, fpu_adder.io.out),
                  (exe_reg_ctrl_fpu_fun === FPU_FMUL_S)  -> fNFromRecFN(8, 24, fpu_mul.io.out),
                  (exe_reg_ctrl_fpu_fun === FPU_FDIV_S)  -> exe_fpu_op1,
                  (exe_reg_ctrl_fpu_fun === FPU_FSQRT_S) -> exe_fpu_op1,
                  (exe_reg_ctrl_fpu_fun === FPU_FSGNJ_S) -> Cat(exe_fpu_op2(31), exe_fpu_op1(30, 0)),
                  (exe_reg_ctrl_fpu_fun === FPU_FSGNJN_S)-> Cat(~exe_fpu_op2(31), exe_fpu_op1(30, 0)),
                  (exe_reg_ctrl_fpu_fun === FPU_FSGNJX_S)-> Cat(exe_fpu_op2(31) ^ exe_fpu_op1(31), exe_fpu_op1(30, 0)),
                  (exe_reg_ctrl_fpu_fun === FPU_FMIN_S)  -> fmin_fn,
                  (exe_reg_ctrl_fpu_fun === FPU_FMAX_S)  -> fmax_fn,
                  (exe_reg_ctrl_fpu_fun === FPU_FEQ_S)   -> feq_res,
                  (exe_reg_ctrl_fpu_fun === FPU_FLT_S)   -> flt_res,
                  (exe_reg_ctrl_fpu_fun === FPU_FLE_S)   -> fle_res,
                  (exe_reg_ctrl_fpu_fun === FPU_FCLASS_S)-> fclass_res,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_S_W)-> fcvt_s_w_bits,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_S_WU)-> fcvt_s_w_bits,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_W_S)-> fcvt_w_s_bits,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_WU_S)-> fcvt_w_s_bits,
                  (exe_reg_ctrl_fpu_fun === FPU_COPY_1)  -> exe_fpu_op1,
                  (exe_reg_ctrl_fpu_fun === FPU_COPY_2)  -> exe_fpu_op2,
                  (exe_reg_ctrl_fpu_fun === FPU_FMADD_S) -> fma_adder_out,
                  (exe_reg_ctrl_fpu_fun === FPU_FMSUB_S) -> fma_adder_out,
                  (exe_reg_ctrl_fpu_fun === FPU_FNMSUB_S) -> Cat(!fma_adder_out(31), fma_adder_out(30, 0)),
                  (exe_reg_ctrl_fpu_fun === FPU_FNMADD_S) -> Cat(!fma_adder_out(31), fma_adder_out(30, 0))
                  ))

   val exe_fflags = MuxCase(0.U, Array(
                  (exe_reg_ctrl_fpu_fun === FPU_FADD_S)  -> fpu_adder.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FSUB_S)  -> fpu_adder.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FMUL_S)  -> fpu_mul.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FDIV_S)  -> exe_fpu_op1,
                  (exe_reg_ctrl_fpu_fun === FPU_FSQRT_S) -> exe_fpu_op1,
                  (exe_reg_ctrl_fpu_fun === FPU_FMIN_S)  -> fpu_cmp.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FMAX_S)  -> fpu_cmp.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FEQ_S)   -> fpu_cmp.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FLT_S)   -> fpu_cmp.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FLE_S)   -> fpu_cmp.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_S_W)-> fpu_i2f.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_S_WU)-> fpu_i2f.io.exceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_W_S)-> fpu_f2i.io.intExceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FCVT_WU_S)-> fpu_f2i.io.intExceptionFlags,
                  (exe_reg_ctrl_fpu_fun === FPU_FMADD_S) -> (fpu_mul.io.exceptionFlags | fma_adder.io.exceptionFlags),
                  (exe_reg_ctrl_fpu_fun === FPU_FMSUB_S) -> (fpu_mul.io.exceptionFlags | fma_adder.io.exceptionFlags),
                  (exe_reg_ctrl_fpu_fun === FPU_FNMSUB_S) -> (fpu_mul.io.exceptionFlags | fma_adder.io.exceptionFlags),
                  (exe_reg_ctrl_fpu_fun === FPU_FNMADD_S) -> (fpu_mul.io.exceptionFlags | fma_adder.io.exceptionFlags)
                  ))

   // Branch/Jump Target Calculation
   val brjmp_offset    = exe_reg_op2_data
   exe_brjmp_target    := exe_reg_pc + brjmp_offset
   exe_jump_reg_target := exe_adder_out & ~1.U(conf.xprlen.W)

   // Instruction misalign detection
   // In control path, instruction misalignment exception is always raised in the next cycle once the misaligned instruction reaches
   // execution stage, regardless whether the pipeline stalls or not
   io.dat.exe_inst_misaligned := (exe_brjmp_target(1, 0).orR    && io.ctl.exe_pc_sel === PC_BRJMP) ||
                                 (exe_jump_reg_target(1, 0).orR && io.ctl.exe_pc_sel === PC_JALR)
   mem_tval_inst_ma := RegNext(Mux(io.ctl.exe_pc_sel === PC_BRJMP, exe_brjmp_target, exe_jump_reg_target))

   val exe_pc_plus4    = (exe_reg_pc + 4.U)(conf.xprlen-1,0)

   when (io.ctl.pipeline_kill)
   {
      mem_reg_valid         := false.B
      mem_reg_inst          := BUBBLE
      mem_reg_ctrl_rf_wen   := false.B
      mem_reg_ctrl_mem_val  := false.B
      mem_reg_ctrl_csr_cmd  := false.B
      // F extension
      mem_reg_ctrl_frf_wen  := false.B
      mem_reg_ctrl_mem_wr_sel := 0.U
      mem_reg_fflags        := 0.U
   }
   .elsewhen (!io.ctl.full_stall)
   {
      mem_reg_valid         := exe_reg_valid
      mem_reg_pc            := exe_reg_pc
      mem_reg_inst          := exe_reg_inst
      mem_reg_alu_out       := Mux((exe_reg_ctrl_wb_sel === WB_PC4), exe_pc_plus4, exe_alu_out)
      mem_reg_wbaddr        := exe_reg_wbaddr
      mem_reg_rs1_addr      := exe_reg_rs1_addr
      mem_reg_rs2_addr      := exe_reg_rs2_addr
      mem_reg_op1_data      := exe_reg_op1_data
      mem_reg_op2_data      := exe_reg_op2_data
      mem_reg_rs2_data      := exe_reg_rs2_data
      mem_reg_ctrl_rf_wen   := exe_reg_ctrl_rf_wen
      mem_reg_ctrl_mem_val  := exe_reg_ctrl_mem_val
      mem_reg_ctrl_mem_fcn  := exe_reg_ctrl_mem_fcn
      mem_reg_ctrl_mem_typ  := exe_reg_ctrl_mem_typ
      mem_reg_ctrl_wb_sel   := exe_reg_ctrl_wb_sel
      mem_reg_ctrl_csr_cmd  := exe_reg_ctrl_csr_cmd
      // F extension
      mem_reg_fpu_out       := exe_fpu_out
      mem_reg_fwbaddr       := exe_reg_fwbaddr
      mem_reg_rs1_faddr     := exe_reg_rs1_faddr
      mem_reg_rs2_faddr     := exe_reg_rs2_faddr
      mem_reg_rs3_faddr     := exe_reg_rs3_faddr
      mem_reg_fpu_op1_data  := exe_reg_fpu_op1_data
      mem_reg_fpu_op2_data  := exe_reg_fpu_op2_data
      mem_reg_fpu_op3_data  := exe_reg_fpu_op3_data
      mem_reg_ctrl_frf_wen  := exe_reg_ctrl_frf_wen
      mem_reg_ctrl_fwb_sel  := exe_reg_ctrl_fwb_sel
      mem_reg_ctrl_mem_wr_sel := exe_reg_ctrl_mem_wr_sel
      mem_reg_fflags        := exe_fflags
   }

   //**********************************
   // Memory Stage

   // Control Status Registers
   // The CSRFile can redirect the PC so it's easiest to put this in Execute for now.
   csr.io.decode(0).inst := mem_reg_inst
   csr.io.rw.addr   := mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB)
   csr.io.rw.wdata  := mem_reg_alu_out
   csr.io.rw.cmd    := mem_reg_ctrl_csr_cmd

   val csr_type = Wire(UInt(2.W))
   csr_type := CSR_N
   val new_fflags = (fflags | mem_reg_fflags)
   when (mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB) === 0x001.U) {
      fflags := MuxCase(new_fflags, Array(
                  (mem_reg_ctrl_csr_cmd === CSR.W) -> mem_reg_alu_out,
                  (mem_reg_ctrl_csr_cmd === CSR.S) -> (new_fflags | mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.C) -> (new_fflags & ~mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.I) -> mem_reg_alu_out
               ))
      csr_type := CSR_FFLAGS
   }
   .otherwise {
      fflags := new_fflags
   }
   when (mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB) === 0x002.U) {
      frm    := MuxCase(frm, Array(
                  (mem_reg_ctrl_csr_cmd === CSR.W) -> mem_reg_alu_out,
                  (mem_reg_ctrl_csr_cmd === CSR.S) -> (frm | mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.C) -> (frm & ~mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.I) -> mem_reg_alu_out
               ))
      csr_type := CSR_FRM
   }
   when (mem_reg_inst(CSR_ADDR_MSB,CSR_ADDR_LSB) === 0x003.U) {
      val fcsr = Cat(0.U(24.W), fflags | mem_reg_fflags, frm)
      val new_fcsr = MuxCase(fcsr, Array(
                  (mem_reg_ctrl_csr_cmd === CSR.W) -> mem_reg_alu_out,
                  (mem_reg_ctrl_csr_cmd === CSR.S) -> (fcsr | mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.C) -> (fcsr & ~mem_reg_alu_out),
                  (mem_reg_ctrl_csr_cmd === CSR.I) -> mem_reg_alu_out
               ))
      fflags := new_fcsr(4, 0)
      frm := new_fcsr(7, 5)
      csr_type := CSR_FCSR
   }

   csr.io.retire    := wb_reg_valid
   csr.io.exception := io.ctl.mem_exception
   csr.io.pc        := mem_reg_pc
   exception_target := csr.io.evec

   csr.io.tval := MuxCase(0.U, Array(
                  (io.ctl.mem_exception_cause === Causes.illegal_instruction.U) -> RegNext(exe_reg_inst),
                  (io.ctl.mem_exception_cause === Causes.misaligned_fetch.U)    -> mem_tval_inst_ma,
                  (io.ctl.mem_exception_cause === Causes.misaligned_store.U)    -> mem_tval_data_ma,
                  (io.ctl.mem_exception_cause === Causes.misaligned_load.U)     -> mem_tval_data_ma,
                  ))

   // Interrupt rising edge detector (output trap signal for one cycle on rising edge)
   val reg_interrupt_handled = RegNext(csr.io.interrupt, false.B)
   val interrupt_edge = csr.io.interrupt && !reg_interrupt_handled

   csr.io.interrupts := io.interrupt
   csr.io.hartid := io.hartid
   io.dat.csr_interrupt := interrupt_edge
   csr.io.cause := Mux(io.ctl.mem_exception, io.ctl.mem_exception_cause, csr.io.interrupt_cause)
   csr.io.ungated_clock := clock

   io.dat.csr_eret := csr.io.eret
   // TODO replay? stall?

   // Add your own uarch counters here!
   csr.io.counters.foreach(_.inc := false.B)


   // Data misalignment detection
   // For example, if type is 3 (word), the mask is ~(0b111 << (3 - 1)) = ~0b100 = 0b011.
   val misaligned_mask = Wire(UInt(3.W))
   misaligned_mask := ~(7.U(3.W) << (mem_reg_ctrl_mem_typ - 1.U)(1, 0))
   io.dat.mem_data_misaligned := (misaligned_mask & mem_reg_alu_out.asUInt.apply(2, 0)).orR && mem_reg_ctrl_mem_val
   io.dat.mem_store := mem_reg_ctrl_mem_fcn === M_XWR
   mem_tval_data_ma := mem_reg_alu_out.asUInt

   // WB Mux
   val csr_rdata = MuxCase(csr.io.rw.rdata, Array(
                  (csr_type === CSR_FFLAGS) -> fflags,
                  (csr_type === CSR_FRM)    -> frm,
                  (csr_type === CSR_FCSR)   -> Cat(0.U(24.W), fflags, frm)
                  ))
   mem_wbdata := MuxCase(mem_reg_alu_out, Array(
                  (mem_reg_ctrl_wb_sel === WB_ALU) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_PC4) -> mem_reg_alu_out,
                  (mem_reg_ctrl_wb_sel === WB_MEM) -> io.dmem.resp.bits.data,
                  (mem_reg_ctrl_wb_sel === WB_CSR) -> csr_rdata,
                  (mem_reg_ctrl_wb_sel === WB_FPU) -> mem_reg_fpu_out
                  ))
   mem_fwbdata := MuxCase(mem_reg_fpu_out, Array(
                  (mem_reg_ctrl_fwb_sel === FWB_ALU) -> mem_reg_alu_out,
                  (mem_reg_ctrl_fwb_sel === FWB_MEM) -> io.dmem.resp.bits.data,
                  (mem_reg_ctrl_fwb_sel === FWB_FPU) -> mem_reg_fpu_out
                  ))

   //**********************************
   // Writeback Stage

   when (!io.ctl.full_stall)
   {
      wb_reg_valid         := mem_reg_valid && !io.ctl.mem_exception && !interrupt_edge
      wb_reg_wbaddr        := mem_reg_wbaddr
      wb_reg_wbdata        := mem_wbdata
      wb_reg_ctrl_rf_wen   := Mux(io.ctl.mem_exception || interrupt_edge, false.B, mem_reg_ctrl_rf_wen)
      // F extension
      wb_reg_fwbaddr       := mem_reg_fwbaddr
      wb_reg_fwbdata       := mem_fwbdata
      wb_reg_ctrl_frf_wen  := Mux(io.ctl.mem_exception || interrupt_edge, false.B, mem_reg_ctrl_frf_wen)
   }
   .otherwise
   {
      wb_reg_valid         := false.B
      wb_reg_ctrl_rf_wen   := false.B
      // F extension
      wb_reg_ctrl_frf_wen  := false.B
   }



   //**********************************
   // External Signals

   // datapath to controlpath outputs
   io.dat.dec_valid  := dec_reg_valid
   io.dat.dec_inst   := dec_reg_inst
   io.dat.exe_br_eq  := (exe_reg_op1_data === exe_reg_rs2_data)
   io.dat.exe_br_lt  := (exe_reg_op1_data.asSInt < exe_reg_rs2_data.asSInt)
   io.dat.exe_br_ltu := (exe_reg_op1_data.asUInt < exe_reg_rs2_data.asUInt)
   io.dat.exe_br_type:= exe_reg_ctrl_br_type

   io.dat.mem_ctrl_dmem_val := mem_reg_ctrl_mem_val

   // datapath to data memory outputs
   io.dmem.req.valid     := mem_reg_ctrl_mem_val && !io.dat.mem_data_misaligned
   io.dmem.req.bits.addr := mem_reg_alu_out.asUInt
   io.dmem.req.bits.fcn  := mem_reg_ctrl_mem_fcn
   io.dmem.req.bits.typ  := mem_reg_ctrl_mem_typ
   // io.dmem.req.bits.data := mem_reg_rs2_data // or mem_reg_fpu_op2_data
   io.dmem.req.bits.data := Mux(mem_reg_ctrl_mem_wr_sel === MWS_FREG, mem_reg_fpu_op2_data, mem_reg_rs2_data)

   val wb_reg_inst = RegNext(mem_reg_inst)

   printf("Cyc= %d [%d] pc=[%x] W[r%d=%x][%d] Op1=[r%d][%x] Op2=[r%d][%x] inst=[%x] %c%c%c DASM(%x)\n",
      csr.io.time(31,0),
      csr.io.retire,
      RegNext(mem_reg_pc),
      wb_reg_wbaddr,
      wb_reg_wbdata,
      wb_reg_ctrl_rf_wen,
      RegNext(mem_reg_rs1_addr),
      RegNext(mem_reg_op1_data),
      RegNext(mem_reg_rs2_addr),
      RegNext(mem_reg_op2_data),
      wb_reg_inst,
      MuxCase(Str(" "), Seq(
         io.ctl.pipeline_kill -> Str("K"),
         io.ctl.full_stall -> Str("F"),
         io.ctl.dec_stall -> Str("S"))),
      MuxLookup(io.ctl.exe_pc_sel, Str("?"), Seq(
         PC_BRJMP -> Str("B"),
         PC_JALR -> Str("R"),
         PC_EXC -> Str("E"),
         PC_4 -> Str(" "))),
      Mux(csr.io.exception, Str("X"), Str(" ")),
      wb_reg_inst)
}
