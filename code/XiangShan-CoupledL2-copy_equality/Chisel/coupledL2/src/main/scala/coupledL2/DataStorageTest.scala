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


class DataStorageTest(implicit p: Parameters) extends L2Module {
  val io = IO(new Bundle() {
    val req = Flipped(ValidIO(new DSRequest))
    val wdata = Input(new DSBlock)
    // Expose array as output for verification
    val dataArray = Output(Vec(blocks, UInt(blockBits.W)))
  })

  val array = RegInit(VecInit.fill(blocks)(0.U(blockBits.W)))

  val arrayIdx = Cat(io.req.bits.way, io.req.bits.set)
  val wen = io.req.valid && io.req.bits.wen
  when(wen) {
    array(arrayIdx) := io.wdata.data
  }

  // Connect internal array to output
  io.dataArray := array
}
