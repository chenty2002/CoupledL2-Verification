package coupledL2Verification

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.TLMessages._
import huancun.MetaData.isParamFromT

class WatchedBlockDataChecker(clients: Int, sourceBits: Int, addressBits: Int,
                              dataBits: Int, blockBytes: Int) extends Module {
  require(dataBits % 8 == 0 && blockBytes % (dataBits / 8) == 0)
  val beats = blockBytes / (dataBits / 8)
  require(beats > 0 && isPow2(blockBytes))
  val beatBits = log2Ceil(beats) max 1
  val io = IO(new Bundle {
    val channels = Input(Vec(clients, new Bundle {
      val a_valid = Bool()
      val a_ready = Bool()
      val a_opcode = UInt(3.W)
      val a_source = UInt(sourceBits.W)
      val a_address = UInt(addressBits.W)
      val c_valid = Bool()
      val c_ready = Bool()
      val c_opcode = UInt(3.W)
      val c_param = UInt(3.W)
      val c_address = UInt(addressBits.W)
      val c_data = UInt(dataBits.W)
      val d_valid = Bool()
      val d_ready = Bool()
      val d_opcode = UInt(3.W)
      val d_source = UInt(sourceBits.W)
      val d_data = UInt(dataBits.W)
    }))
    val checked = Output(Vec(clients, Bool()))
    val violation = Output(Vec(clients, Bool()))
  })

  // Retain the original shared shadow and client-order priority for simultaneous writes.
  val shadow = RegInit(VecInit(Seq.fill(beats)(0.U(dataBits.W))))
  val shadowValid = RegInit(false.B)
  for (i <- 0 until clients) {
    val ch = io.channels(i)
    val watched = RegInit(VecInit(Seq.fill(1 << sourceBits)(false.B)))
    val cBeat = RegInit(0.U(beatBits.W))
    val dBeat = RegInit(0.U(beatBits.W))
    val cData = ch.c_opcode === ReleaseData || ch.c_opcode === ProbeAckData
    val dData = ch.d_opcode === GrantData
    val cFire = ch.c_valid && ch.c_ready
    val dFire = ch.d_valid && ch.d_ready
    when(cFire && cData) {
      cBeat := Mux(cBeat === (beats - 1).U, 0.U, cBeat + 1.U)
      when(isParamFromT(ch.c_param) && ch.c_address === 0.U) {
        shadow(cBeat) := ch.c_data
        shadowValid := true.B
      }
    }
    io.checked(i) := dFire && dData && watched(ch.d_source)
    io.violation(i) := io.checked(i) && shadowValid && ch.d_data =/= shadow(dBeat)
    when(dFire && dData) {
      dBeat := Mux(dBeat === (beats - 1).U, 0.U, dBeat + 1.U)
    }
    when(dFire && (ch.d_opcode === Grant || (dData && dBeat === (beats - 1).U))) {
      watched(ch.d_source) := false.B
    }
    // D carries no address: classify its source using the accepted A request.
    when(ch.a_valid && ch.a_ready && (ch.a_opcode === AcquireBlock || ch.a_opcode === AcquirePerm)) {
      watched(ch.a_source) := (ch.a_address >> log2Ceil(blockBytes)) === 0.U
    }
  }
}
