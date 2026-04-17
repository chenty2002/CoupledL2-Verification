package coupledL2

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import coupledL2.DirectoryTest.instanceId
import org.chipsalliance.cde.config.Parameters

class DirectoryTest(implicit p: Parameters) extends L2Module {
  val sets = cacheParams.sets
  val ways = cacheParams.ways

  val io = IO(new Bundle() {
    val metaWReq = Flipped(ValidIO(new MetaWrite))
    val tagWReq = Flipped(ValidIO(new TagWrite))
  })

  val tagWen = io.tagWReq.valid
  val metaWen = io.metaWReq.valid

  val tagArray = RegInit(VecInit.fill(sets, ways)(0.U(tagBits.W)))
  val metaArray = RegInit(VecInit.fill(sets, ways)(0.U.asTypeOf(new MetaEntry)))

  when(tagWen) {
    tagArray(io.tagWReq.bits.set)(io.tagWReq.bits.way) := io.tagWReq.bits.wtag
  }
  when(metaWen) {
    metaArray(io.metaWReq.bits.set)(OHToUInt(io.metaWReq.bits.wayOH)) := io.metaWReq.bits.wmeta
  }

  val stateArray = WireDefault(VecInit.fill(sets, ways)(0.U(stateBits.W)))
  stateArray.zip(metaArray).foreach {
    case (sArray, mArray) =>
      sArray.zip(mArray).foreach {
        case (s, m) =>
          s := m.state
      }
  }

  // BoringUtils.addSource(stateArray, s"stateArray_${instanceId}", disableDedup = true)
  // BoringUtils.addSource(tagArray, s"tagArray_${instanceId}", disableDedup = true)
}

object DirectoryTest {
  var instanceId: Int = -1
  def apply()(implicit p: Parameters): DirectoryTest = {
    instanceId += 1
    new DirectoryTest()
  }
}
