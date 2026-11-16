package coupledL2Verification

import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import freechips.rocketchip.tilelink.{TLMessages, TLPermissions}

object WriteReadCheckerRegression extends App {
  RawTester.test(new WatchedBlockDataChecker(2, 2, 5, 8, 2), Seq(WriteVcdAnnotation)) { c =>
    def resetChecker(): Unit = {
      for (ch <- c.io.channels) {
        ch.a_valid.poke(false.B)
        ch.a_ready.poke(false.B)
        ch.a_opcode.poke(TLMessages.AcquireBlock)
        ch.a_source.poke(0.U)
        ch.a_address.poke(0.U)
        ch.c_valid.poke(false.B)
        ch.c_ready.poke(false.B)
        ch.c_opcode.poke(TLMessages.ReleaseData)
        ch.c_param.poke(TLPermissions.TtoN)
        ch.c_address.poke(0.U)
        ch.c_data.poke(0.U)
        ch.d_valid.poke(false.B)
        ch.d_ready.poke(false.B)
        ch.d_opcode.poke(TLMessages.GrantData)
        ch.d_source.poke(0.U)
        ch.d_data.poke(0.U)
      }
      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)
      c.clock.step()
    }
    def acquire(source: Int, address: Int, client: Int = 0): Unit = {
      val ch = c.io.channels(client)
      ch.a_valid.poke(true.B)
      ch.a_ready.poke(true.B)
      ch.a_source.poke(source.U)
      ch.a_address.poke(address.U)
      c.clock.step()
      ch.a_valid.poke(false.B)
    }
    def writeBeat(value: Int, address: Int = 0, ready: Boolean = true, client: Int = 0): Unit = {
      val ch = c.io.channels(client)
      ch.c_valid.poke(true.B)
      ch.c_ready.poke(ready.B)
      ch.c_address.poke(address.U)
      ch.c_data.poke(value.U)
      c.clock.step()
      ch.c_valid.poke(false.B)
    }
    def readBeat(source: Int, value: Int, checked: Boolean,
                 violation: Boolean = false, ready: Boolean = true, client: Int = 0): Unit = {
      val ch = c.io.channels(client)
      ch.d_valid.poke(true.B)
      ch.d_ready.poke(ready.B)
      ch.d_source.poke(source.U)
      ch.d_data.poke(value.U)
      c.io.checked(client).expect((checked && ready).B)
      c.io.violation(client).expect((violation && ready).B)
      c.clock.step()
      ch.d_valid.poke(false.B)
    }

    resetChecker()
    writeBeat(0x34)
    writeBeat(0x12)
    acquire(0, 22)
    acquire(1, 0)
    readBeat(0, 0xad, checked = false)
    readBeat(0, 0xde, checked = false)
    readBeat(1, 0x34, checked = true)
    readBeat(1, 0x12, checked = true)
    acquire(2, 0)
    readBeat(2, 0x99, checked = true, violation = true)
    readBeat(2, 0x12, checked = true)
    println("PASS: address 22 response is excluded by accepted A/source; incorrect address 0 data is detected")

    resetChecker()
    writeBeat(0x34)
    writeBeat(0x12)
    for (_ <- 0 until 3) writeBeat(0xaa, ready = false)
    acquire(0, 0)
    readBeat(0, 0x34, checked = true)
    readBeat(0, 0x12, checked = true)
    writeBeat(0xaa)
    for (_ <- 0 until 3) writeBeat(0xbb, ready = false)
    acquire(0, 0)
    readBeat(0, 0xaa, checked = true)
    readBeat(0, 0x12, checked = true)
    writeBeat(0xbb)
    acquire(0, 0)
    readBeat(0, 0xaa, checked = true)
    readBeat(0, 0xbb, checked = true)
    println("PASS: C stalls neither update shadow data nor advance its beat phase")

    resetChecker()
    writeBeat(0x34)
    writeBeat(0x12)
    acquire(0, 0)
    for (_ <- 0 until 3) readBeat(0, 0xee, checked = true, ready = false)
    readBeat(0, 0x34, checked = true)
    for (_ <- 0 until 3) readBeat(0, 0xee, checked = true, ready = false)
    readBeat(0, 0x12, checked = true)
    acquire(0, 22)
    readBeat(0, 0xad, checked = false)
    readBeat(0, 0xde, checked = false)
    println("PASS: D stalls neither check unaccepted data nor advance phase; retired source IDs can be reused")

    resetChecker()
    writeBeat(0x34)
    writeBeat(0x12)
    writeBeat(0xad, address = 22)
    writeBeat(0xde, address = 22)
    acquire(0, 0, client = 1)
    acquire(0, 22)
    readBeat(0, 0x34, checked = true, client = 1)
    readBeat(0, 0x12, checked = true, client = 1)
    readBeat(0, 0xad, checked = false)
    readBeat(0, 0xde, checked = false)
    println("PASS: unwatched C traffic is ignored; source attribution is independent across clients")

    resetChecker()
    for ((lo, hi) <- Seq((0x34, 0x78), (0x12, 0x56))) {
      for ((ch, value) <- c.io.channels.zip(Seq(lo, hi))) {
        ch.c_valid.poke(true.B)
        ch.c_ready.poke(true.B)
        ch.c_data.poke(value.U)
      }
      c.clock.step()
    }
    c.io.channels.foreach(_.c_valid.poke(false.B))
    acquire(1, 0)
    readBeat(1, 0x78, checked = true)
    readBeat(1, 0x56, checked = true)
    println("PASS: simultaneous shadow updates retain the original later-client priority")
  }
}
