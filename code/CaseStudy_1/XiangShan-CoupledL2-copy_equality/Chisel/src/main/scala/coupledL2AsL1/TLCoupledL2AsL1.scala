/** *************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  * *************************************************************************************
  */

package coupledL2AsL1

import chisel3._
import chisel3.util._
import coupledL2._
import coupledL2.tl2tl._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

class TLCoupledL2AsL1(implicit p: Parameters) extends CoupledL2Base {
  println(s"prefetchers: $prefetchers")
  assert(prefetchers.length == 1 && prefetchers.exists(_.isInstanceOf[InputAsPrefectchParam]))

  class CoupledL2AsL1Imp(wrapper: LazyModule) extends BaseCoupledL2Imp(wrapper) {
    override lazy val prefetcher = prefetchOpt.map(_ => Module(new Input2Req()(pftParams)))
    val fullAddrBits = node.in.head._2.bundle.addressBits

    // keep io_name same as before
    val io_inputAddr = IO(Input(UInt(fullAddrBits.W)))
    val io_inputNeedT = IO(Input(Bool()))
    val io_inputStore = IO(Input(Bool()))
    val io_inputData = IO(Input(UInt(blockBits.W)))
    val io_requestType = IO(Input(Bool())) // 0 Acquire, 1 Release

    prefetchOpt.foreach {
       _ =>
        prefetcher.get.io_inputAddr := io_inputAddr
        prefetcher.get.io_inputNeedT := io_inputNeedT
        prefetcher.get.io_requestType := io_requestType
        prefetcher.get.io_inputStore := io_inputStore
        prefetcher.get.io_inputData := io_inputData
    }

    // Match BaseCoupledL2Imp signature: (edgeIn, edgeOut, i) and return BaseSlice
    override def createSlice(edgeIn: TLEdgeIn, edgeOut: TLEdgeOut, i: Int): coupledL2.BaseSlice[_ <: coupledL2.BaseOuterBundle] =
      Module(new L1Slice()(p.alterPartial {
        case EdgeInKey  => edgeIn
        case EdgeOutKey => edgeOut
        case BankBitsKey => bankBits
        case SliceIdKey => i
      }))

    require(banks == 1, "The native store sideband is scoped to the single-bank copy-equality environment")
    val nativeSlices = slices.map(_.asInstanceOf[L1Slice])
    nativeSlices.foreach { s =>
      s.io_storePending := prefetcher.get.io_storePending
      s.io_storeSource := prefetcher.get.io_storeSource
      s.io_storeTag := prefetcher.get.io_storeTag
      s.io_storeSet := prefetcher.get.io_storeSet
      s.io_storeData := prefetcher.get.io_storeData
    }
    prefetcher.get.io_storeCanIssue := VecInit(nativeSlices.map(_.io_storeCanIssue)).asUInt.orR
    prefetcher.get.io_storeDone := VecInit(nativeSlices.map(_.io_storeDone)).asUInt.orR
    }

  override lazy val module = new CoupledL2AsL1Imp(this)
}