/** *************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  * http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  * *************************************************************************************
  */

package coupledL2

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.util.SetAssocLRU
import coupledL2.utils._
import utility.{ParallelPriorityMux, RegNextN}
import org.chipsalliance.cde.config.Parameters
import coupledL2.prefetch.PfSource
import freechips.rocketchip.tilelink.TLMessages._
import chiselFv._
import coupledL2.DirectoryTest.instanceId

class DirectoryTest(implicit p: Parameters) extends L2Module with Formal {

  val sets = cacheParams.sets
  val ways = cacheParams.ways

  val io = IO(new Bundle() {
    val metaWReq = Flipped(ValidIO(new MetaWrite))
    val tagWReq = Flipped(ValidIO(new TagWrite))
  })

  def invalid_way_sel(metaVec: Seq[MetaEntry], repl: UInt) = {
    val invalid_vec = metaVec.map(_.state === MetaData.INVALID)
    val has_invalid_way = Cat(invalid_vec).orR
    val way = ParallelPriorityMux(invalid_vec.zipWithIndex.map(x => x._1 -> x._2.U(wayBits.W)))
    (has_invalid_way, way)
  }


  val tagWen  = io.tagWReq.valid
  val metaWen = io.metaWReq.valid

  val tagArray = Module(new SRAMTemplateTest(UInt(tagBits.W), sets, ways, singlePort = true))
  val metaArray = Module(new SRAMTemplateTest(new MetaEntry, sets, ways, singlePort = true))

  val stateArray = WireDefault(VecInit.fill(sets, ways)(0.U(stateBits.W)))
  stateArray.zip(metaArray.io.r).foreach { case (sArray, mArray) =>
    sArray.zip(mArray).foreach { case (s, m) =>
      s := m.state
    }
  }

  /* BoringUtils Method 2
  BoringUtils.addSource(stateArray, s"stateArray_${instanceId} in directory", disableDedup = true)
  BoringUtils.addSource(tagArray.io.r, s"tagArray_${instanceId} in directory", disableDedup = true
   */

  // tagArray.array(addr(setstart, setend)).map(_ (tagBits - 1, 0) === req_s3.tag)  /// 12.22

  val resetFinish = RegInit(false.B)
  val resetIdx = RegInit((sets - 1).U)

  /* ====== Generate response signals ====== */
  // hit/way calculation in stage 3, Cuz SRAM latency is high under high frequency
  /* stage 1: io.read.fire, access Tag/Meta
     stage 2: get Tag/Meta, latch
     stage 3: calculate hit/way and chosen meta/tag by way
  */

  // Tag R/W
  tagArray.io.w(
    tagWen,
    io.tagWReq.bits.wtag,
    io.tagWReq.bits.set,
    UIntToOH(io.tagWReq.bits.way)
  )

  // Meta R/W
  metaArray.io.w(
    metaWen,
    io.metaWReq.bits.wmeta,
    io.metaWReq.bits.set,
    io.metaWReq.bits.wayOH
  )

  val replaceWay = WireInit(UInt(wayBits.W), 0.U)

  dontTouch(io)
  dontTouch(metaArray.io)
  dontTouch(tagArray.io)

  /* ====== Reset ====== */
  when(resetIdx === 0.U) {
    resetFinish := true.B
  }
  when(!resetFinish) {
    resetIdx := resetIdx - 1.U
  }
}

object DirectoryTest {
  var instanceId: Int = -1
  def apply()(implicit p: Parameters): DirectoryTest = {
    instanceId += 1
    new DirectoryTest()
  }
}