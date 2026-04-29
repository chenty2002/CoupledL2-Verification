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

package coupledL2AsL1.prefetch

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiselFv._
import coupledL2.prefetch._
import org.chipsalliance.cde.config.Parameters

case class CoupledL2AsL1PrefParam() extends PrefetchParameters {
  override val hasPrefetchBit: Boolean = true
  override val hasPrefetchSrc: Boolean = true
  override val inflightEntries: Int = 16
}

class PrefetchIO(implicit p: Parameters) extends PrefetchBundle {
  val train = Flipped(DecoupledIO(new PrefetchTrain))
  val req = DecoupledIO(new PrefetchReq)
  val resp = Flipped(DecoupledIO(new PrefetchResp))
}

class ReqTimer(implicit p: Parameters) extends PrefetchBundle {
  val valid = new Bool
  val timer = UInt(10.W)
  val adder = UInt(1.W)
  val reqSet = UInt(setBits.W)
  val reqTag = UInt(tagBits.W)
}

class Prefetcher(implicit p: Parameters) extends PrefetchModule with Formal {
  val io = IO(new PrefetchIO)
  val randomIO = IO(new Bundle() {
    val inputValid = Input(new Bool)
    val inputRandomAddr = Input(UInt(24.W))
    val inputNeedT = Input(new Bool)
  })

  prefetchOpt.get match {
    case _ =>
      println("--------------------------------")
      println("Prefetcher Modified for L2 as L1")
      println("--------------------------------")

      io.resp.ready := true.B
      io.train.ready := true.B

      val randomAddr = RegInit(1.U(24.W))
      randomAddr := Mux(randomIO.inputValid, randomIO.inputRandomAddr, randomAddr)

      io.req.valid := true.B
      val (tag, set, _) = parseAddress(randomAddr)
      io.req.bits.tag := tag
      io.req.bits.set := set
      io.req.bits.needT := randomIO.inputNeedT

      io.req.bits.pfSource := PfSource.NoWhere.id.U

      val reqSource = RegInit(cacheParams.hartIds.head.U(6.W))
      when(io.req.valid && io.req.ready) { // handshake
        reqSource := reqSource + 2.U
      }
      io.req.bits.source := reqSource
  }
}
