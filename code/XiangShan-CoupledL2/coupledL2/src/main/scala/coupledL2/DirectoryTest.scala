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
import org.chipsalliance.cde.config.Parameters

class DirectoryTest(implicit p: Parameters) extends L2Module {

  println(s"DirectoryTest: tagBits=${tagBits}, setBits=${setBits}, offsetBits=${offsetBits}, bankBits=${bankBits}")

  val sets = cacheParams.sets
  val ways = cacheParams.ways

  val io = IO(new Bundle() {
    val metaWReq = Flipped(ValidIO(new MetaWrite))
    val tagWReq = Flipped(ValidIO(new TagWrite))
    // Expose arrays as outputs for verification
    val stateArray = Output(Vec(sets, Vec(ways, UInt(stateBits.W))))
    val tagArray = Output(Vec(sets, Vec(ways, UInt(tagBits.W))))
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

  // Connect internal arrays to outputs
  io.stateArray := stateArray
  io.tagArray := tagArray
}