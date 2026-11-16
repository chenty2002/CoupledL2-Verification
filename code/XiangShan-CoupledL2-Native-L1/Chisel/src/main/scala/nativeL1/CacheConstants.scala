package nativeL1

import chisel3._
import chisel3.util._

// Copied and simplified from XiangShan cache constants to keep L1 request semantics.
trait MemoryOpConstants {
  val NUM_XA_OPS = 9
  val M_SZ = 5

  def M_X = BitPat("b?????")
  def M_XRD = "b00000".U
  def M_XWR = "b00001".U
  def M_PFR = "b00010".U
  def M_PFW = "b00011".U
  def M_XA_SWAP = "b00100".U
  def M_FLUSH_ALL = "b00101".U
  def M_XLR = "b00110".U
  def M_XSC = "b00111".U
  def M_XA_ADD = "b01000".U
  def M_XA_XOR = "b01001".U
  def M_XA_OR = "b01010".U
  def M_XA_AND = "b01011".U
  def M_XA_MIN = "b01100".U
  def M_XA_MAX = "b01101".U
  def M_XA_MINU = "b01110".U
  def M_XA_MAXU = "b01111".U
  def M_FLUSH = "b10000".U
  def M_PWR = "b10001".U
  def M_PRODUCE = "b10010".U
  def M_CLEAN = "b10011".U
  def M_SFENCE = "b10100".U
  def M_WOK = "b10111".U
  def M_XA_CASQ = "b11000".U
  def M_XA_CASW = "b11010".U
  def M_XA_CASD = "b11011".U

  def isAMOLogical(cmd: UInt): Bool = cmd === M_XA_SWAP || cmd === M_XA_XOR || cmd === M_XA_OR || cmd === M_XA_AND
  def isAMOArithmetic(cmd: UInt): Bool = cmd === M_XA_ADD || cmd === M_XA_MIN || cmd === M_XA_MAX || cmd === M_XA_MINU || cmd === M_XA_MAXU
  def isAMOCAS(cmd: UInt): Bool = cmd === M_XA_CASW || cmd === M_XA_CASD || cmd === M_XA_CASQ
  def isAMO(cmd: UInt): Bool = isAMOLogical(cmd) || isAMOArithmetic(cmd) || isAMOCAS(cmd)
  def isPrefetch(cmd: UInt): Bool = cmd === M_PFR || cmd === M_PFW
  def isRead(cmd: UInt): Bool = cmd === M_XRD || cmd === M_XLR || cmd === M_XSC || isAMO(cmd)
  def isWrite(cmd: UInt): Bool = cmd === M_XWR || cmd === M_PWR || cmd === M_XSC || isAMO(cmd)
  def isWriteIntent(cmd: UInt): Bool = isWrite(cmd) || cmd === M_PFW || cmd === M_XLR
}

object MemoryOpConstants extends MemoryOpConstants
