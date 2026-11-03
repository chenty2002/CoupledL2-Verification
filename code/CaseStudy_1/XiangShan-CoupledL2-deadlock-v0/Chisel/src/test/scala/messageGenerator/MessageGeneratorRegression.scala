package messageGenerator

import chisel3._
import chiseltest._
import chiseltest.simulator.WriteVcdAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters

class GeneratorTestTop(sourceStart: Int = 0, ordinaryEntries: Int = 0, retainedGrantCopy: Boolean = false, beatBytes: Int = 1)(implicit p: Parameters) extends LazyModule {
  val generator = LazyModule(new TLMessageGenerator(MessageGeneratorParam(
    sets = 2, ways = 2, blockBytes = 2, channelBytes = TLChannelBeatBytes(beatBytes), sourceIdRange = IdRange(sourceStart, sourceStart + 4),
    ordinaryEntries = ordinaryEntries, retainedGrantCopy = retainedGrantCopy)))
  val manager = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(address = Seq(AddressSet(0, 31)), regionType = RegionType.CACHED,
      supportsAcquireT = TransferSizes(2), supportsAcquireB = TransferSizes(2), supportsGet = TransferSizes(1, 2))),
    beatBytes = beatBytes, endSinkId = 4)))
  manager := generator.node
  class GeneratorTestTopImp extends LazyModuleImp(GeneratorTestTop.this) {
    val in = IO(chiselTypeOf(generator.module.io))
    generator.module.io <> in
    val tl = IO(new TLBundle(manager.in.head._2.bundle))
    tl <> manager.in.head._1
  }
  lazy val module = new GeneratorTestTopImp
}

object MessageGeneratorRegression extends App {
  implicit val p: Parameters = Parameters.empty
  val top = DisableMonitors(p => LazyModule(new GeneratorTestTop()(p)))(p)
  RawTester.test(top.module, Seq(WriteVcdAnnotation)) { c =>
    c.in.in_valid.poke(false.B)
    c.in.in_addr.poke(0.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_store.poke(false.B)
    c.in.in_data.poke(0.U)
    c.tl.a.ready.poke(true.B)
    c.tl.b.valid.poke(false.B)
    c.tl.c.ready.poke(false.B)
    c.tl.d.valid.poke(false.B)
    c.tl.e.ready.poke(true.B)
    c.clock.step(3)

    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "test handshake timed out")
    }
    def grant(src: BigInt, value: Int, trunk: Boolean = true): Unit = {
      for (beat <- 0 until 2) {
        c.tl.d.valid.poke(true.B)
        c.tl.d.bits.opcode.poke(TLMessages.GrantData)
        c.tl.d.bits.param.poke(if (trunk) TLPermissions.toT else TLPermissions.toB)
        c.tl.d.bits.size.poke(1.U)
        c.tl.d.bits.source.poke(src.U)
        c.tl.d.bits.sink.poke(src.U)
        c.tl.d.bits.denied.poke(false.B)
        c.tl.d.bits.corrupt.poke(false.B)
        c.tl.d.bits.data.poke(((value >> (8 * beat)) & 255).U)
        c.clock.step()
      }
      c.tl.d.valid.poke(false.B)
    }
    def request(addr: Int, acquire: Boolean = true, trunk: Boolean = true): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(trunk.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    def acquire(addr: Int, value: Int, trunk: Boolean = true): Unit = {
      request(addr, trunk = trunk)
      await(c.tl.a.valid.peek().litToBoolean)
      val src = c.tl.a.bits.source.peek().litValue
      c.clock.step()
      grant(src, value, trunk)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(3)
    }
    def resetDut(): Unit = {
      c.in.in_valid.poke(false.B)
      c.in.in_store.poke(false.B)
      c.tl.a.ready.poke(true.B)
      c.tl.b.valid.poke(false.B)
      c.tl.c.ready.poke(false.B)
      c.tl.d.valid.poke(false.B)
      c.tl.e.ready.poke(true.B)
      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)
      c.clock.step(2)
    }
    def expectC(opcode: UInt, param: UInt, addr: Int, values: Seq[Int]): BigInt = {
      var src = BigInt(0)
      for ((value, i) <- values.zipWithIndex) {
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.opcode.expect(opcode)
        c.tl.c.bits.param.expect(param)
        c.tl.c.bits.address.expect(addr.U)
        c.tl.c.bits.data.expect(value.U)
        if (i == 0) src = c.tl.c.bits.source.peek().litValue
        c.tl.c.bits.source.expect(src.U)
        c.tl.c.ready.poke(true.B)
        c.clock.step()
      }
      c.tl.c.ready.poke(false.B)
      src
    }
    def releaseAck(src: BigInt): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def probe(addr: Int, param: UInt, probeSource: Int = 3): Unit = {
      c.tl.b.bits.opcode.poke(TLMessages.Probe)
      c.tl.b.bits.param.poke(param)
      c.tl.b.bits.size.poke(1.U)
      c.tl.b.bits.source.poke(probeSource.U)
      c.tl.b.bits.address.poke(addr.U)
      c.tl.b.valid.poke(true.B)
      await(c.tl.b.ready.peek().litToBoolean)
      c.clock.step()
      c.tl.b.valid.poke(false.B)
    }
    acquire(0, 0x1234)
    acquire(2, 0xabcd)
    for (addr <- Seq(0, 2)) {
      c.in.in_isAcquire.poke(false.B)
      c.in.in_param.poke(false.B)
      c.in.in_addr.poke(addr.U)
      c.in.in_valid.poke(true.B)
      c.clock.step()
    }
    c.in.in_valid.poke(false.B)
    c.clock.step(5)
    c.tl.c.ready.poke(true.B)
    val sources = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    val expected = Seq(0x34, 0x12, 0xcd, 0xab)
    for ((value, beat) <- expected.zipWithIndex) {
      await(c.tl.c.valid.peek().litToBoolean)
      c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
      c.tl.c.bits.data.expect(value.U)
      c.tl.c.bits.address.expect((if (beat < 2) 0 else 2).U)
      if (beat % 2 == 0) sources += c.tl.c.bits.source.peek().litValue
      // Stalling must preserve the complete beat, including data and source.
      c.tl.c.ready.poke(false.B)
      c.clock.step(2)
      c.tl.c.bits.data.expect(value.U)
      c.tl.c.ready.poke(true.B)
      c.clock.step()
    }
    assert(sources.distinct.size == 2, "two Releases must be outstanding before either ReleaseAck")
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_addr.poke(0.U)
    c.in.in_valid.poke(true.B)
    for (_ <- 0 until 6) {
      c.tl.a.valid.expect(false.B)
      c.clock.step()
    }
    c.in.in_valid.poke(false.B)
    for (src <- sources.reverse) {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
    }
    c.tl.d.valid.poke(false.B)
    c.clock.step(3)
    acquire(4, 0x5678)
    println("PASS: two outstanding Releases, distinct sources, reversed ReleaseAck, exact data beats, C stalls, same-block exclusion, slot reuse")

    resetDut()
    c.tl.a.ready.poke(false.B)
    request(0)
    c.clock.step()
    val a0 = c.tl.a.bits.source.peek().litValue
    request(2)
    for (_ <- 0 until 5) {
      request(0)
      c.tl.a.bits.source.expect(a0.U)
      c.tl.a.bits.address.expect(0.U)
    }
    c.tl.a.ready.poke(true.B)
    c.clock.step()
    await(c.tl.a.valid.peek().litToBoolean)
    val a1 = c.tl.a.bits.source.peek().litValue
    assert(a0 != a1, "independent Acquires need distinct source IDs")
    c.tl.a.bits.address.expect(2.U)
    c.clock.step()
    c.tl.a.valid.expect(false.B)
    c.tl.e.ready.poke(false.B)
    grant(a1, 0xabcd)
    await(c.tl.e.valid.peek().litToBoolean)
    c.tl.e.bits.sink.expect(a1.U)
    c.clock.step(2)
    grant(a0, 0x1234)
    c.tl.e.bits.sink.expect(a1.U)
    c.clock.step(2)
    c.tl.e.bits.sink.expect(a1.U)
    c.tl.e.ready.poke(true.B)
    c.clock.step()
    c.tl.e.bits.sink.expect(a0.U)
    c.clock.step()
    println("PASS: parallel Acquires, duplicate input exclusion, A backpressure, reversed Grants, E backpressure")

    c.clock.step(3)
    // A local CPU write is visible to the later ProbeAckData snapshot.
    c.in.in_addr.poke(0.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_data.poke(0xcafe.U)
    c.in.in_store.poke(true.B)
    c.in.in_valid.poke(true.B)
    c.clock.step()
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    probe(0, TLPermissions.toB)
    c.clock.step(2)
    val probeSource = expectC(TLMessages.ProbeAckData, TLPermissions.TtoB, 0, Seq(0xfe, 0xca))
    assert(probeSource == 3)
    c.clock.step(2)
    // Branch permission must reject CPU stores.
    c.in.in_addr.poke(0.U)
    c.in.in_data.poke(0xdead.U)
    c.in.in_param.poke(false.B)
    c.in.in_store.poke(true.B)
    c.in.in_valid.poke(true.B)
    c.clock.step()
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 0, Seq(0))
    println("PASS: exclusive local store, exact ProbeAckData snapshot, T-to-B and B-to-N responses")

    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8)
    await(c.tl.a.valid.peek().litToBoolean)
    c.tl.c.valid.expect(false.B)
    c.tl.a.bits.address.expect(8.U)
    val replacement = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    grant(replacement, 0x5678)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    val victim = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    assert(victim == replacement, "replacement owns one shared entry through Grant and victim Ack")
    releaseAck(victim)
    c.clock.step(3)
    probe(4, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 4, Seq(0xcd, 0xab))
    println("PASS: replacement sends A/Grant/E before victim ReleaseAck and preserves the other way")

    resetDut()
    acquire(0, 0x1234, trunk = false)
    request(0)
    await(c.tl.a.valid.peek().litToBoolean)
    c.tl.a.bits.param.expect(TLPermissions.BtoT)
    val upgrade = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    c.tl.d.valid.poke(true.B)
    c.tl.d.bits.opcode.poke(TLMessages.Grant)
    c.tl.d.bits.param.poke(TLPermissions.toT)
    c.tl.d.bits.source.poke(upgrade.U)
    c.tl.d.bits.sink.poke(upgrade.U)
    c.clock.step()
    c.tl.d.valid.poke(false.B)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    println("PASS: permission-only Grant after B-to-T AcquireBlock preserves cached data")

    resetDut()
    acquire(0, 0x1234, trunk = false)
    c.tl.a.ready.poke(false.B)
    request(0)
    c.clock.step()
    val overtaken = c.tl.a.bits.source.peek().litValue
    c.tl.a.bits.param.expect(TLPermissions.BtoT)
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 0, Seq(0))
    c.tl.a.valid.expect(true.B)
    c.tl.a.bits.param.expect(TLPermissions.BtoT)
    c.tl.a.ready.poke(true.B)
    c.clock.step()
    grant(overtaken, 0xbeef)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0xef, 0xbe))
    println("PASS: Probe overtakes pending upgrade, ProbeAck completes, later GrantData replaces invalidated copy")

    resetDut()
    val addresses = Seq(0, 2, 4, 6)
    val values = Seq(0x1234, 0xabcd, 0x5678, 0x9abc)
    for ((addr, value) <- addresses.zip(values)) acquire(addr, value)
    for (addr <- addresses) request(addr, acquire = false, trunk = false)
    c.clock.step(3)
    val fourReleases = addresses.zip(values).map { case (addr, value) =>
      expectC(TLMessages.ReleaseData, TLPermissions.TtoN, addr, Seq(value & 255, value >> 8))
    }
    assert(fourReleases.distinct.size == 4, "all four Release slots must coexist before any acknowledgement")
    for (src <- fourReleases.reverse) releaseAck(src)
    c.clock.step(2)
    acquire(8, 0x4321)
    println(s"PASS: four outstanding Releases with sources ${fourReleases.mkString(",")}, reversed acknowledgements, all slots reusable")

    resetDut()
    acquire(0, 0x1234)
    request(2)
    await(c.tl.a.valid.peek().litToBoolean)
    val activeAcquire = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    request(0, acquire = false, trunk = false)
    val parallelRelease = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    assert(activeAcquire != parallelRelease, "Acquire and Release cannot reuse a shared source slot")
    releaseAck(parallelRelease)
    grant(activeAcquire, 0x5678)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    println("PASS: independent Acquire and Release progress concurrently without sharing an active source ID")

    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8, acquire = false, trunk = false)
    for (_ <- 0 until 8) {
      c.tl.a.valid.expect(false.B)
      c.tl.c.valid.expect(false.B)
      c.clock.step()
    }
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    println("PASS: Release miss has no bus effect and preserves the full set's cached lines")

    resetDut()
    acquire(0, 0x1234, trunk = false)
    request(0, acquire = false, trunk = true)
    for (_ <- 0 until 8) {
      c.tl.a.valid.expect(false.B)
      c.tl.c.valid.expect(false.B)
      c.clock.step()
    }
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 0, Seq(0))
    println("PASS: explicit Release-to-B on an existing Branch is a no-op and retains Branch permission")


    // No cache ways are reserved by misses: four same-set transactions precede every D.
    resetDut()
    c.tl.a.ready.poke(false.B)
    val sameSet = Seq(0, 4, 8, 12)
    sameSet.foreach(request(_))
    val sameSetSources = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    c.tl.a.ready.poke(true.B)
    for (addr <- sameSet) {
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.address.expect(addr.U)
      sameSetSources += c.tl.a.bits.source.peek().litValue
      c.clock.step()
    }
    assert(sameSetSources.distinct.size == 4)
    c.tl.a.valid.expect(false.B)
    // Grants complete out of order. The first two take rows, the other two replace them.
    for (i <- Seq(3, 1)) {
      grant(sameSetSources(i), 0x1100 + i)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(4)
    }
    for (i <- Seq(2, 0)) {
      grant(sameSetSources(i), 0x1100 + i)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step()
    }
    val releases = (0 until 2).map { _ =>
      await(c.tl.c.valid.peek().litToBoolean)
      val addr = c.tl.c.bits.address.peek().litValue.toInt
      val value = 0x1100 + sameSet.indexOf(addr)
      (addr, expectC(TLMessages.ReleaseData, TLPermissions.TtoN, addr, Seq(value & 255, value >> 8)))
    }
    assert(releases.map(_._1).toSet == Set(12, 4))
    assert(releases.map(_._2).distinct.size == 2)
    for ((_, src) <- releases.reverse) releaseAck(src)
    c.clock.step(6)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(2, 0x11))
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0, 0x11))
    c.clock.step(3)
    acquire(16, 0x5566)
    println("PASS: four same-set A before any D, reversed Grants, two buffered replacements, reversed victim Ack, unique final rows and resource reuse")

    // New ownership is available after E even while the old victim has no ReleaseAck.
    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8)
    await(c.tl.a.valid.peek().litToBoolean)
    val bufferedSrc = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    grant(bufferedSrc, 0x5678)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    val bufferedVictim = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    c.tl.e.valid.expect(false.B)
    // A T CPU store updates the refill buffer, then a Probe snapshot must see it.
    c.in.in_addr.poke(8.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_store.poke(true.B)
    c.in.in_data.poke(0xcafe.U)
    c.in.in_valid.poke(true.B)
    c.clock.step()
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    probe(8, TLPermissions.toB)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoB, 8, Seq(0xfe, 0xca))
    releaseAck(bufferedVictim)
    c.clock.step(5)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 8, Seq(0))
    println("PASS: victim Ack withheld while E completes; new buffered block accepts T store and Probe downgrade; install retains B")

    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8)
    await(c.tl.a.valid.peek().litToBoolean)
    val invalidatedSrc = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    grant(invalidatedSrc, 0x5678)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step()
    val invalidatedVictim = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(0x78, 0x56))
    releaseAck(invalidatedVictim)
    c.clock.step(5)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 8, Seq(0))
    c.clock.step(3)
    acquire(8, 0xbeef)
    println("PASS: Probe invalidates new buffered block before victim Ack; no T resurrection and entry can be reused")

    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8)
    await(c.tl.a.valid.peek().litToBoolean)
    val oldProbeSrc = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    c.clock.step(2)
    grant(oldProbeSrc, 0x5678)
    await(c.tl.e.valid.peek().litToBoolean)
    c.clock.step(5)
    for (_ <- 0 until 5) { c.tl.c.valid.expect(false.B); c.clock.step() }
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(0x78, 0x56))
    println("PASS: old victim Probe-to-N while Acquire waits eliminates unnecessary victim Release")


    resetDut()
    acquire(0, 0x1234)
    acquire(4, 0xabcd)
    request(8)
    await(c.tl.a.valid.peek().litToBoolean)
    val heldEntry = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    c.tl.e.ready.poke(false.B)
    grant(heldEntry, 0x5678)
    val heldVictim = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    c.tl.b.bits.opcode.poke(TLMessages.Probe)
    c.tl.b.bits.param.poke(TLPermissions.toN)
    c.tl.b.bits.size.poke(1.U)
    c.tl.b.bits.source.poke(3.U)
    c.tl.b.bits.address.poke(8.U)
    c.tl.b.valid.poke(true.B)
    c.tl.b.ready.expect(true.B)
    c.clock.step()
    c.tl.b.valid.poke(false.B)
    for (_ <- 0 until 3) {
      c.tl.e.valid.expect(true.B)
      c.tl.e.bits.sink.expect(heldEntry.U)
      c.tl.c.valid.expect(false.B)
      c.clock.step()
    }
    c.tl.e.ready.poke(true.B)
    c.clock.step()
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(0x78, 0x56))
    c.clock.step(3)
    val otherEntries = Seq(2, 6, 10).map { addr =>
      request(addr)
      await(c.tl.a.valid.peek().litToBoolean)
      val src = c.tl.a.bits.source.peek().litValue
      assert(src != heldEntry, "entry must remain allocated through victim ReleaseAck")
      c.clock.step()
      src
    }
    assert(otherEntries.distinct.size == 3)
    request(14)
    for (_ <- 0 until 4) { c.tl.a.valid.expect(false.B); c.clock.step() }
    releaseAck(heldVictim)
    c.clock.step(5)
    request(14)
    await(c.tl.a.valid.peek().litToBoolean)
    c.tl.a.bits.source.expect(heldEntry.U)
    c.clock.step()
    println("PASS: stalled E preserves Ack and defers queued Probe processing; public B enqueue succeeds; victim Ack consumes shared entry until acknowledgement, then source reusable")

    resetDut()
    acquire(0, 0x1234, trunk = false)
    acquire(4, 0xabcd, trunk = false)
    def replaceBranch(addr: Int, word: Int): BigInt = {
      request(addr, trunk = false)
      await(c.tl.a.valid.peek().litToBoolean)
      val src = c.tl.a.bits.source.peek().litValue
      c.clock.step()
      grant(src, word, trunk = false)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(3)
      src
    }
    val branchReplacement0 = replaceBranch(8, 0x5678)
    await(c.tl.c.valid.peek().litToBoolean)
    // A Branch cannot accept a store; the stalled victim must retain its cached copy.
    c.in.in_addr.poke(0.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(false.B)
    c.in.in_store.poke(true.B)
    c.in.in_data.poke(0xdead.U)
    c.in.in_valid.poke(true.B)
    for (_ <- 0 until 3) {
      c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
      c.tl.c.bits.param.expect(TLPermissions.BtoN)
      c.tl.c.bits.address.expect(0.U)
      c.tl.c.bits.data.expect(0x34.U)
      c.clock.step()
    }
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    val branchReplacement1 = replaceBranch(12, 0x9abc)
    val branchVictim0 = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 0, Seq(0x34, 0x12))
    c.in.in_data.poke(0xbeef.U)
    val branchVictim1 = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 4, Seq(0xcd, 0xab))
    assert(branchVictim0 == branchReplacement0 && branchVictim1 == branchReplacement1)
    assert(branchVictim0 != branchVictim1, "two Branch victims must await Ack on distinct sources")
    releaseAck(branchVictim1)
    releaseAck(branchVictim0)
    c.clock.step(6)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 8, Seq(0))
    probe(12, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 12, Seq(0))
    acquire(16, 0x55aa, trunk = false)
    println("PASS: two automatic Branch victims send exact two-beat BtoN ReleaseData snapshots under C stall and unrelated input data; distinct sources, reversed Ack, new Branch rows and source reuse")

    resetDut()
    acquire(0, 0x1234)
    request(0, acquire = false, trunk = false)
    val nextCycleNoData = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    c.tl.b.bits.mask.poke(1.U)
    c.tl.b.bits.corrupt.poke(false.B)
    c.tl.b.bits.data.poke(0.U)
    probe(0, TLPermissions.toN, probeSource = 0)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.denied.poke(false.B)
    c.tl.d.bits.corrupt.poke(false.B)
    c.tl.c.ready.poke(true.B)
    c.tl.c.valid.expect(false.B)
    releaseAck(nextCycleNoData)
    c.tl.c.valid.expect(true.B)
    c.tl.c.bits.opcode.expect(TLMessages.ProbeAck)
    c.tl.c.bits.param.expect(TLPermissions.NtoN)
    c.tl.c.bits.address.expect(0.U)
    c.tl.c.bits.source.expect(0.U)
    c.clock.step()
    c.tl.c.ready.poke(false.B)
    println("PASS: R9 default-false queued Probe emits no C before or on matching Ack and accepts NtoN ProbeAck exactly next cycle")

  }

  // The public sourceIdRange may have a nonzero start; sinks use their own namespace.
  val offsetTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(sourceStart = 1)(p)))(p)
  RawTester.test(offsetTop.module, Seq(WriteVcdAnnotation)) { c =>
    c.in.in_valid.poke(false.B)
    c.in.in_addr.poke(0.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_store.poke(false.B)
    c.in.in_data.poke(0.U)
    c.tl.a.ready.poke(false.B)
    c.tl.b.valid.poke(false.B)
    c.tl.c.ready.poke(false.B)
    c.tl.d.valid.poke(false.B)
    c.tl.e.ready.poke(true.B)
    c.clock.step(3)
    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "offset fixture handshake timed out")
    }
    val addresses = Seq(0, 2, 4, 6)
    def request(addr: Int, acquire: Boolean): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(acquire.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    addresses.foreach(request(_, true))
    c.tl.a.ready.poke(true.B)
    val sources = addresses.map { addr =>
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.address.expect(addr.U)
      val src = c.tl.a.bits.source.peek().litValue
      c.clock.step()
      src
    }
    assert(sources == Seq(1, 2, 3, 4).map(BigInt(_)), s"offset Acquire sources: $sources")
    for (((addr, src), sink) <- addresses.zip(sources).zipWithIndex) {
      val value = 0x1100 + addr
      for (beat <- 0 until 2) {
        c.tl.d.valid.poke(true.B)
        c.tl.d.bits.opcode.poke(TLMessages.GrantData)
        c.tl.d.bits.param.poke(TLPermissions.toT)
        c.tl.d.bits.size.poke(1.U)
        c.tl.d.bits.source.poke(src.U)
        c.tl.d.bits.sink.poke(sink.U)
        c.tl.d.bits.denied.poke(false.B)
        c.tl.d.bits.corrupt.poke(false.B)
        c.tl.d.bits.data.poke(((value >> (8 * beat)) & 255).U)
        c.clock.step()
      }
      c.tl.d.valid.poke(false.B)
      await(c.tl.e.valid.peek().litToBoolean)
      c.tl.e.bits.sink.expect(sink.U)
      c.clock.step(4)
    }
    addresses.foreach(request(_, false))
    val releaseSources = addresses.map { addr =>
      var source = BigInt(0)
      for (beat <- 0 until 2) {
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
        c.tl.c.bits.param.expect(TLPermissions.TtoN)
        c.tl.c.bits.address.expect(addr.U)
        c.tl.c.bits.data.expect((((0x1100 + addr) >> (8 * beat)) & 255).U)
        if (beat == 0) source = c.tl.c.bits.source.peek().litValue
        c.tl.c.bits.source.expect(source.U)
        c.tl.c.ready.poke(true.B)
        c.clock.step()
      }
      c.tl.c.ready.poke(false.B)
      source
    }
    assert(releaseSources == Seq(1, 2, 3, 4).map(BigInt(_)), s"offset Release sources: $releaseSources")
    for (src <- releaseSources.reverse) {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
    }
    c.tl.d.valid.poke(false.B)
    c.clock.step(3)
    request(8, true)
    await(c.tl.a.valid.peek().litToBoolean)
    c.tl.a.bits.source.expect(1.U)
    c.clock.step()
    println("PASS: nonzero IdRange(1,5) emits four A and four Release sources 1,2,3,4; independent sinks 0..3; reversed Ack and source reuse")
  }

  val nominalTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(ordinaryEntries = 3)(p)))(p)
  RawTester.test(nominalTop.module, Seq(WriteVcdAnnotation)) { c =>
    def initialize(): Unit = {
      c.in.in_valid.poke(false.B)
      c.in.in_addr.poke(0.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(false.B)
      c.in.in_data.poke(0.U)
      c.tl.a.ready.poke(true.B)
      c.tl.b.valid.poke(false.B)
      c.tl.c.ready.poke(false.B)
      c.tl.d.valid.poke(false.B)
      c.tl.e.ready.poke(true.B)
    }
    initialize()
    c.clock.step(3)
    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "nominal3 fixture handshake timed out")
    }
    def request(addr: Int, acquire: Boolean = true): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(acquire.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    def grant(src: BigInt, value: Int): Unit = {
      for (beat <- 0 until 2) {
        c.tl.d.valid.poke(true.B)
        c.tl.d.bits.opcode.poke(TLMessages.GrantData)
        c.tl.d.bits.param.poke(TLPermissions.toT)
        c.tl.d.bits.size.poke(1.U)
        c.tl.d.bits.source.poke(src.U)
        c.tl.d.bits.sink.poke(src.U)
        c.tl.d.bits.denied.poke(false.B)
        c.tl.d.bits.corrupt.poke(false.B)
        c.tl.d.bits.data.poke(((value >> (8 * beat)) & 255).U)
        c.clock.step()
      }
      c.tl.d.valid.poke(false.B)
    }
    def acquire(addr: Int, value: Int): Unit = {
      request(addr)
      await(c.tl.a.valid.peek().litToBoolean)
      val src = c.tl.a.bits.source.peek().litValue
      assert(src < 3, "namespace source3 must not create a fourth ordinary entry")
      c.clock.step()
      grant(src, value)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(3)
    }
    def expectC(op: UInt, param: UInt, addr: Int, bytes: Seq[Int]): BigInt = {
      var src = BigInt(0)
      for ((byte, beat) <- bytes.zipWithIndex) {
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.opcode.expect(op)
        c.tl.c.bits.param.expect(param)
        c.tl.c.bits.address.expect(addr.U)
        c.tl.c.bits.data.expect(byte.U)
        if (beat == 0) src = c.tl.c.bits.source.peek().litValue
        c.tl.c.bits.source.expect(src.U)
        c.tl.c.ready.poke(true.B)
        c.clock.step()
      }
      c.tl.c.ready.poke(false.B)
      src
    }
    def ack(src: BigInt): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def probe(addr: Int, cap: UInt): Unit = {
      c.tl.b.bits.opcode.poke(TLMessages.Probe)
      c.tl.b.bits.param.poke(cap)
      c.tl.b.bits.size.poke(1.U)
      c.tl.b.bits.source.poke(0.U)
      c.tl.b.bits.address.poke(addr.U)
      c.tl.b.valid.poke(true.B)
      await(c.tl.b.ready.peek().litToBoolean)
      c.clock.step()
      c.tl.b.valid.poke(false.B)
    }
    c.tl.a.ready.poke(false.B)
    Seq(0, 4, 8).foreach(request(_))
    c.tl.a.ready.poke(true.B)
    val sources = Seq(0, 4, 8).map { addr =>
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.address.expect(addr.U)
      val src = c.tl.a.bits.source.peek().litValue
      c.clock.step()
      src
    }
    assert(sources == Seq(0, 1, 2).map(BigInt(_)))
    for (_ <- 0 until 5) { request(12); c.tl.a.valid.expect(false.B) }
    c.tl.e.ready.poke(false.B)
    for ((src, i) <- sources.zipWithIndex) grant(src, 0x1100 + i)
    c.tl.e.valid.expect(true.B)
    c.tl.e.bits.sink.expect(0.U)
    c.clock.step(2)
    c.tl.e.bits.sink.expect(0.U)
    c.tl.e.ready.poke(true.B)
    for (sink <- 0 until 3) {
      await(c.tl.e.valid.peek().litToBoolean)
      c.tl.e.bits.sink.expect(sink.U)
      c.clock.step()
    }
    val victim = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0, 0x11))
    assert(victim == 2)
    probe(8, TLPermissions.toB)
    assert(expectC(TLMessages.ProbeAckData, TLPermissions.TtoB, 8, Seq(2, 0x11)) == 0)
    ack(victim)
    c.clock.step(5)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.BtoN, 8, Seq(0))

    initialize()
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
    c.clock.step(2)
    val addresses = Seq(0, 2, 4)
    val words = Seq(0x1234, 0xabcd, 0x5678)
    addresses.zip(words).foreach { case (addr, word) => acquire(addr, word) }
    addresses.foreach(request(_, acquire = false))
    val releaseSources = addresses.zip(words).map { case (addr, word) =>
      expectC(TLMessages.ReleaseData, TLPermissions.TtoN, addr, Seq(word & 255, word >> 8))
    }
    assert(releaseSources == Seq(0, 1, 2).map(BigInt(_)))
    for (_ <- 0 until 5) { request(8); c.tl.a.valid.expect(false.B) }
    // All ordinary entries await ReleaseAck; Probe must still use its independent path.
    probe(6, TLPermissions.toN)
    assert(expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 6, Seq(0)) == 0)
    for (src <- releaseSources.reverse) ack(src)
    c.clock.step(3)
    acquire(8, 0x9abc)
    println("PASS: nominal3 retains namespace4, caps same-set A at3 and blocks fourth; full-entry E progress, buffered Probe downgrade, three outstanding Releases, independent Probe with no free ordinary slot, reversed Ack and reuse")

    initialize()
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
    c.clock.step(2)
    acquire(0, 0x1234)
    acquire(2, 0xabcd)
    acquire(4, 0x5678)
    request(0, acquire = false)
    // Public B enqueues while the internal head must wait for this ReleaseAck.
    probe(0, TLPermissions.toN)
    probe(4, TLPermissions.toN)
    c.tl.b.bits.address.poke(6.U)
    c.tl.b.valid.poke(true.B)
    for (_ <- 0 until 3) {
      c.tl.b.ready.expect(false.B)
      c.clock.step()
    }
    c.tl.b.valid.poke(false.B)
    // A queued tail is not yet processed: its later snapshot sees this legal T store.
    c.in.in_addr.poke(4.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_store.poke(true.B)
    c.in.in_data.poke(0xcafe.U)
    c.in.in_valid.poke(true.B)
    c.clock.step()
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    val headRelease = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    request(2, acquire = false)
    val independentRelease = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 2, Seq(0xcd, 0xab))
    assert(headRelease != independentRelease)
    for (_ <- 0 until 4) {
      c.tl.c.valid.expect(false.B)
      c.tl.b.ready.expect(false.B)
      c.clock.step()
    }
    ack(independentRelease)
    c.tl.c.valid.expect(false.B)
    c.tl.b.ready.expect(false.B)
    // Still full before the matching Ack edge: pipe=false cannot accept a third B.
    c.tl.c.valid.expect(false.B)
    c.tl.b.ready.expect(false.B)
    ack(headRelease)
    // The Ack edge admits the queued head, freeing one FIFO slot and registering C.
    c.tl.c.valid.expect(true.B)
    c.tl.b.ready.expect(true.B)
    c.clock.step()
    c.tl.b.ready.expect(true.B)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 0, Seq(0))
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 4, Seq(0xfe, 0xca))
    println("PASS: B FIFO2 accepts two before ReleaseAck and rejects third; blocked B head does not block unrelated C Release; reverse Ack, pipe=false, FIFO order and live T-store snapshot preserved")

    initialize()
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
    c.clock.step(2)
    request(0)
    await(c.tl.a.valid.peek().litToBoolean)
    val partial = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    c.tl.e.ready.poke(false.B)
    def grantBeat(byte: Int): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.GrantData)
      c.tl.d.bits.param.poke(TLPermissions.toT)
      c.tl.d.bits.size.poke(1.U)
      c.tl.d.bits.source.poke(partial.U)
      c.tl.d.bits.sink.poke(partial.U)
      c.tl.d.bits.denied.poke(false.B)
      c.tl.d.bits.corrupt.poke(false.B)
      c.tl.d.bits.data.poke(byte.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    grantBeat(0x34)
    probe(0, TLPermissions.toN)
    for (_ <- 0 until 3) {
      c.tl.c.valid.expect(false.B)
      c.tl.e.valid.expect(false.B)
      c.clock.step()
    }
    grantBeat(0x12)
    for (_ <- 0 until 3) {
      c.tl.c.valid.expect(false.B)
      c.tl.e.valid.expect(true.B)
      c.tl.e.bits.sink.expect(partial.U)
      c.clock.step()
    }
    c.tl.e.ready.poke(true.B)
    c.clock.step()
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    c.clock.step(4)
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 0, Seq(0))
    println("PASS: public B queues during partial Grant and E stall; no early C, E progresses independently, Probe snapshots complete Grant buffer and invalidation cannot resurrect T")
  }

  val retainedTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(ordinaryEntries = 3, retainedGrantCopy = true)(p)))(p)
  RawTester.test(retainedTop.module, Seq(WriteVcdAnnotation)) { c =>
    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "retained-copy handshake timed out")
    }
    def resetDut(): Unit = {
      c.in.in_valid.poke(false.B)
      c.in.in_addr.poke(0.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(false.B)
      c.in.in_data.poke(0.U)
      c.tl.a.ready.poke(true.B)
      c.tl.b.valid.poke(false.B)
      c.tl.b.bits.data.poke(0.U)
      c.tl.c.ready.poke(false.B)
      c.tl.d.valid.poke(false.B)
      c.tl.e.ready.poke(true.B)
      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)
      c.clock.step(2)
    }
    def request(addr: Int, acquire: Boolean = true, trunk: Boolean = true): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(trunk.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    def acceptA(addr: Int): BigInt = {
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.address.expect(addr.U)
      val src = c.tl.a.bits.source.peek().litValue
      assert(src < 3)
      c.clock.step()
      src
    }
    def grantBeat(src: BigInt, byte: Int, trunk: Boolean = true): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.GrantData)
      c.tl.d.bits.param.poke(if (trunk) TLPermissions.toT else TLPermissions.toB)
      c.tl.d.bits.size.poke(1.U)
      c.tl.d.bits.source.poke(src.U)
      c.tl.d.bits.sink.poke(src.U)
      c.tl.d.bits.denied.poke(false.B)
      c.tl.d.bits.corrupt.poke(false.B)
      c.tl.d.bits.data.poke(byte.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def grant(src: BigInt, word: Int, trunk: Boolean = true): Unit = {
      grantBeat(src, word & 255, trunk)
      grantBeat(src, word >> 8, trunk)
      await(c.tl.e.valid.peek().litToBoolean)
      c.tl.e.bits.sink.expect(src.U)
      c.clock.step(3)
    }
    def acquire(addr: Int, word: Int, trunk: Boolean = true): Unit = {
      request(addr, trunk = trunk)
      grant(acceptA(addr), word, trunk)
    }
    def upgradeWithoutData(addr: Int): Unit = {
      request(addr)
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.param.expect(TLPermissions.BtoT)
      val src = acceptA(addr)
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.Grant)
      c.tl.d.bits.param.poke(TLPermissions.toT)
      c.tl.d.bits.size.poke(1.U)
      c.tl.d.bits.source.poke(src.U)
      c.tl.d.bits.sink.poke(src.U)
      c.tl.d.bits.denied.poke(false.B)
      c.tl.d.bits.corrupt.poke(false.B)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(3)
    }
    def probe(addr: Int, cap: UInt, required: Boolean = false, source: Int = 0): Unit = {
      c.tl.b.bits.opcode.poke(TLMessages.Probe)
      c.tl.b.bits.param.poke(cap)
      c.tl.b.bits.size.poke(1.U)
      c.tl.b.bits.source.poke(source.U)
      c.tl.b.bits.address.poke(addr.U)
      c.tl.b.bits.data.poke((if (required) 1 else 0).U)
      c.tl.b.valid.poke(true.B)
      await(c.tl.b.ready.peek().litToBoolean)
      c.clock.step()
      c.tl.b.valid.poke(false.B)
    }
    def expectC(op: UInt, param: UInt, addr: Int, bytes: Seq[Int]): BigInt = {
      var src = BigInt(0)
      for ((byte, beat) <- bytes.zipWithIndex) {
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.opcode.expect(op)
        c.tl.c.bits.param.expect(param)
        c.tl.c.bits.address.expect(addr.U)
        c.tl.c.bits.size.expect(1.U)
        c.tl.c.bits.data.expect(byte.U)
        if (beat == 0) src = c.tl.c.bits.source.peek().litValue
        c.tl.c.bits.source.expect(src.U)
        c.tl.c.ready.poke(true.B)
        c.clock.step()
      }
      c.tl.c.ready.poke(false.B)
      src
    }
    def ack(src: BigInt): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def store(addr: Int, word: Int): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(true.B)
      c.in.in_data.poke(word.U)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
      c.in.in_store.poke(false.B)
    }
    def holdStore(addr: Int, word: Int): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(true.B)
      c.in.in_data.poke(word.U)
      c.in.in_valid.poke(true.B)
    }
    def stopStore(): Unit = {
      c.in.in_valid.poke(false.B)
      c.in.in_store.poke(false.B)
    }
    def bufferedBlock(): BigInt = {
      resetDut()
      acquire(0, 0x1234)
      acquire(4, 0xabcd)
      acquire(8, 0xbeef)
      expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0x34, 0x12))
    }

    resetDut()
    acquire(0, 0xbeef)
    probe(0, TLPermissions.toB)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoB, 0, Seq(0))
    probe(0, TLPermissions.toN, required = true)
    expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, Seq(0xef, 0xbe))
    probe(0, TLPermissions.toN, required = true)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 0, Seq(0))
    println("PASS: retained-copy clean resident T sends NoData; required Branch returns cached data; missing line remains NtoN NoData")

    resetDut()
    acquire(0, 0xbeef)
    store(0, 0xcafe)
    probe(0, TLPermissions.toT)
    await(c.tl.c.valid.peek().litToBoolean)
    holdStore(0, 0xdead)
    for (_ <- 0 until 3) { c.tl.c.bits.data.expect(0xfe.U); c.clock.step() }
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoT, 0, Seq(0xfe, 0xca))
    stopStore()
    probe(0, TLPermissions.toT)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoT, 0, Seq(0))
    store(0, 0xf00d)
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 0, Seq(0x0d, 0xf0))
    println("PASS: retained-copy dirty resident TtoT snapshot survives C stalls and attempted stores; final accepted beat clears modification and later T store reasserts")

    resetDut()
    acquire(0, 0xbeef)
    acquire(2, 0xabcd)
    acquire(4, 0x5678)
    probe(0, TLPermissions.toT)
    await(c.tl.c.valid.peek().litToBoolean)
    probe(2, TLPermissions.toT, required = true, source = 1)
    probe(4, TLPermissions.toT, required = false, source = 2)
    c.tl.b.bits.data.poke(0.U)
    assert(expectC(TLMessages.ProbeAck, TLPermissions.TtoT, 0, Seq(0)) == 0)
    assert(expectC(TLMessages.ProbeAckData, TLPermissions.TtoT, 2, Seq(0xcd, 0xab)) == 1)
    c.tl.b.bits.data.poke(1.U)
    assert(expectC(TLMessages.ProbeAck, TLPermissions.TtoT, 4, Seq(0)) == 2)
    println("PASS: retained-copy FIFO2 captures both dataRequired bits independently of later live B.data and preserves Probe source/order")

    resetDut()
    acquire(0, 0xbeef, trunk = false)
    upgradeWithoutData(0)
    probe(0, TLPermissions.toT)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoT, 0, Seq(0))
    store(0, 0xcafe)
    probe(0, TLPermissions.toB)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoB, 0, Seq(0xfe, 0xca))
    upgradeWithoutData(0)
    probe(0, TLPermissions.toT, required = true)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoT, 0, Seq(0xfe, 0xca))
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoN, 0, Seq(0))
    println("PASS: retained-copy BtoT Grant without Data preserves Branch bytes and clean status; dirty TtoB writeback clears local modification before a later upgrade")

    val cleanBufferVictim = bufferedBlock()
    probe(8, TLPermissions.toT)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoT, 8, Seq(0))
    store(8, 0xcafe)
    probe(8, TLPermissions.toT)
    await(c.tl.c.valid.peek().litToBoolean)
    holdStore(8, 0xdead)
    for (_ <- 0 until 3) { c.tl.c.bits.data.expect(0xfe.U); c.clock.step() }
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoT, 8, Seq(0xfe, 0xca))
    stopStore()
    probe(8, TLPermissions.toB)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoB, 8, Seq(0))
    ack(cleanBufferVictim)
    c.clock.step(6)
    probe(8, TLPermissions.toN, required = true)
    expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 8, Seq(0xfe, 0xca))
    println("PASS: retained-copy clean and dirty transient Probes use exact snapshots; TtoT clears after stalled C completes and downgraded buffer installs as Branch")

    val dirtyBufferVictim = bufferedBlock()
    store(8, 0xcafe)
    ack(dirtyBufferVictim)
    c.clock.step(6)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(0xfe, 0xca))
    println("PASS: retained-copy transient local modification and bytes migrate into the resident row after victim Ack")

    val invalidatedBufferVictim = bufferedBlock()
    store(8, 0xcafe)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoN, 8, Seq(0xfe, 0xca))
    ack(invalidatedBufferVictim)
    c.clock.step(6)
    probe(8, TLPermissions.toN, required = true)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 8, Seq(0))
    acquire(8, 0x7788)
    probe(8, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.TtoN, 8, Seq(0))
    println("PASS: retained-copy invalidated dirty buffer cannot install or resurrect modification; source reuse with new GrantData starts locally clean")

    resetDut()
    acquire(0, 0xbeef)
    request(0, acquire = false, trunk = false)
    ack(expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 0, Seq(0xef, 0xbe)))
    c.clock.step(4)
    acquire(0, 0x1234, trunk = false)
    acquire(4, 0x5678, trunk = false)
    acquire(8, 0x9abc, trunk = false)
    ack(expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 0, Seq(0x34, 0x12)))
    c.clock.step(6)
    probe(8, TLPermissions.toN, required = true)
    expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 8, Seq(0xbc, 0x9a))
    println("PASS: retained-copy optimization changes only Probe choice; clean T explicit ReleaseData and automatic B victim ReleaseData remain exact")

    resetDut()
    request(0)
    val partial = acceptA(0)
    c.tl.e.ready.poke(false.B)
    grantBeat(partial, 0xef)
    probe(0, TLPermissions.toN)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.tl.e.valid.expect(false.B); c.clock.step() }
    grantBeat(partial, 0xbe)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.tl.e.valid.expect(true.B); c.clock.step() }
    c.tl.e.ready.poke(true.B)
    c.clock.step()
    expectC(TLMessages.ProbeAck, TLPermissions.TtoN, 0, Seq(0))
    c.clock.step(4)
    probe(0, TLPermissions.toN)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 0, Seq(0))
    println("PASS: retained-copy clean Probe waits for complete GrantData and E despite B enqueue, then invalidation prevents later installation")

    resetDut()
    acquire(0, 0x1234)
    store(0, 0xbeef)
    probe(0, TLPermissions.toB)
    expectC(TLMessages.ProbeAckData, TLPermissions.TtoB, 0, Seq(0xef, 0xbe))
    request(0, acquire = false, trunk = false)
    await(c.tl.c.valid.peek().litToBoolean)
    // The completed Probe lowered permission to B; the explicit Release retains its exact bytes.
    for (_ <- 0 until 4) {
      c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
      c.tl.c.bits.param.expect(TLPermissions.BtoN)
      c.tl.c.bits.address.expect(0.U)
      c.tl.c.bits.data.expect(0xef.U)
      store(0, 0xdead)
    }
    val afterProbeRelease = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 0, Seq(0xef, 0xbe))
    ack(afterProbeRelease)
    c.clock.step(4)
    println("PASS: explicit BtoN ReleaseData follows completed dirty TtoB ProbeAckData with exact local-store bytes frozen under C stall and rejected B stores")

    resetDut()
    acquire(0, 0x1234, trunk = false)
    acquire(2, 0xabcd, trunk = false)
    request(0, acquire = false, trunk = false)
    request(2, acquire = false, trunk = false)
    c.clock.step(4)
    val branchRelease0 = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 0, Seq(0x34, 0x12))
    val branchRelease2 = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 2, Seq(0xcd, 0xab))
    assert(branchRelease0 != branchRelease2, "explicit Branch Releases require distinct outstanding source IDs")
    println("PASS: two explicit BtoN ReleaseData snapshots coexist before any ReleaseAck with exact bytes and distinct sources")
    probe(0, TLPermissions.toN)
    for (_ <- 0 until 5) { c.tl.c.valid.expect(false.B); c.clock.step() }
    ack(branchRelease2)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
    ack(branchRelease0)
    expectC(TLMessages.ProbeAck, TLPermissions.NtoN, 0, Seq(0))
    c.clock.step(3)
    acquire(4, 0x55aa, trunk = false)
    println("PASS: same-block new Probe remains queued through unrelated reversed ReleaseAck and responds only after its own ReleaseAck; sources reusable")

    resetDut()
    acquire(0, 0x1234)
    store(0, 0xbeef)
    request(0, acquire = false, trunk = true)
    val retainedAck = expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, Seq(0xef, 0xbe))
    c.tl.b.bits.mask.poke(1.U)
    c.tl.b.bits.corrupt.poke(false.B)
    probe(0, TLPermissions.toN, required = true)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.denied.poke(false.B)
    c.tl.d.bits.corrupt.poke(false.B)
    c.tl.c.ready.poke(true.B)
    c.tl.c.valid.expect(false.B)
    ack(retainedAck)
    c.tl.c.valid.expect(true.B)
    c.tl.c.bits.opcode.expect(TLMessages.ProbeAckData)
    c.tl.c.bits.param.expect(TLPermissions.BtoN)
    c.tl.c.bits.address.expect(0.U)
    c.tl.c.bits.source.expect(0.U)
    c.tl.c.bits.data.expect(0xef.U)
    c.clock.step()
    c.tl.c.valid.expect(true.B)
    c.tl.c.bits.data.expect(0xbe.U)
    c.clock.step()
    c.tl.c.ready.poke(false.B)
    println("PASS: R9 retained-copy matching Ack admits ProbeData only after Ack with first beat exactly next cycle and exact retained BEEF bytes")


  }

  val lookaheadOffsetTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(sourceStart = 1, ordinaryEntries = 3, retainedGrantCopy = true)(p)))(p)
  RawTester.test(lookaheadOffsetTop.module, Seq(WriteVcdAnnotation)) { c =>
    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "retained-copy handshake timed out")
    }
    def resetDut(): Unit = {
      c.in.in_valid.poke(false.B)
      c.in.in_addr.poke(0.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(false.B)
      c.in.in_data.poke(0.U)
      c.tl.a.ready.poke(true.B)
      c.tl.b.valid.poke(false.B)
      c.tl.b.bits.data.poke(0.U)
      c.tl.c.ready.poke(false.B)
      c.tl.d.valid.poke(false.B)
      c.tl.e.ready.poke(true.B)
      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)
      c.clock.step(2)
    }
    def request(addr: Int, acquire: Boolean = true, trunk: Boolean = true): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(trunk.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    def acceptA(addr: Int): BigInt = {
      await(c.tl.a.valid.peek().litToBoolean)
      c.tl.a.bits.address.expect(addr.U)
      val src = c.tl.a.bits.source.peek().litValue
      assert(src >= 1 && src < 4)
      c.clock.step()
      src
    }
    def grantBeat(src: BigInt, byte: Int, trunk: Boolean = true): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.GrantData)
      c.tl.d.bits.param.poke(if (trunk) TLPermissions.toT else TLPermissions.toB)
      c.tl.d.bits.size.poke(1.U)
      c.tl.d.bits.source.poke(src.U)
      c.tl.d.bits.sink.poke(src.U)
      c.tl.d.bits.denied.poke(false.B)
      c.tl.d.bits.corrupt.poke(false.B)
      c.tl.d.bits.data.poke(byte.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def grant(src: BigInt, word: Int, trunk: Boolean = true): Unit = {
      grantBeat(src, word & 255, trunk)
      grantBeat(src, word >> 8, trunk)
      await(c.tl.e.valid.peek().litToBoolean)
      c.tl.e.bits.sink.expect(src.U)
      c.clock.step(3)
    }
    def acquire(addr: Int, word: Int, trunk: Boolean = true): Unit = {
      request(addr, trunk = trunk)
      grant(acceptA(addr), word, trunk)
    }
    def probe(addr: Int, cap: UInt, required: Boolean = false, source: Int = 0): Unit = {
      c.tl.b.bits.mask.poke(1.U)
      c.tl.b.bits.corrupt.poke(false.B)
      c.tl.b.bits.opcode.poke(TLMessages.Probe)
      c.tl.b.bits.param.poke(cap)
      c.tl.b.bits.size.poke(1.U)
      c.tl.b.bits.source.poke(source.U)
      c.tl.b.bits.address.poke(addr.U)
      c.tl.b.bits.data.poke((if (required) 1 else 0).U)
      c.tl.b.valid.poke(true.B)
      await(c.tl.b.ready.peek().litToBoolean)
      c.clock.step()
      c.tl.b.valid.poke(false.B)
    }
    def expectC(op: UInt, param: UInt, addr: Int, bytes: Seq[Int]): BigInt = {
      var src = BigInt(0)
      for ((byte, beat) <- bytes.zipWithIndex) {
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.opcode.expect(op)
        c.tl.c.bits.param.expect(param)
        c.tl.c.bits.address.expect(addr.U)
        c.tl.c.bits.size.expect(1.U)
        c.tl.c.bits.data.expect(byte.U)
        if (beat == 0) src = c.tl.c.bits.source.peek().litValue
        c.tl.c.bits.source.expect(src.U)
        c.tl.c.ready.poke(true.B)
        c.clock.step()
      }
      c.tl.c.ready.poke(false.B)
      src
    }
    def ack(src: BigInt): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
      c.tl.d.bits.source.poke(src.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
    }
    def store(addr: Int, word: Int): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(true.B)
      c.in.in_param.poke(true.B)
      c.in.in_store.poke(true.B)
      c.in.in_data.poke(word.U)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
      c.in.in_store.poke(false.B)
    }

    resetDut()
    acquire(0, 0x1234)
    store(0, 0xbeef)
    acquire(2, 0xcafe, trunk = false)
    request(4)
    val heldAcquireSource = acceptA(4)
    assert(heldAcquireSource == 1, "first source remains occupied by a legitimate outstanding A")
    request(0, acquire = false, trunk = true)
    request(2, acquire = false, trunk = false)
    val waitingSource = expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, Seq(0xef, 0xbe))
    val unrelatedSource = expectC(TLMessages.ReleaseData, TLPermissions.BtoN, 2, Seq(0xfe, 0xca))
    assert(waitingSource == 2 && unrelatedSource == 3, "offset Release matching must use actual IDs, not entry indices")
    probe(0, TLPermissions.toN, required = true, source = 1)
    for (_ <- 0 until 4) { c.tl.c.valid.expect(false.B); c.clock.step() }
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.denied.poke(false.B)
    c.tl.d.bits.corrupt.poke(false.B)
    // Legal Ack for another live Release; wrong source for Probe0's blocker.
    ack(unrelatedSource)
    c.tl.c.valid.expect(false.B)
    for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
    c.tl.c.valid.expect(false.B)
    ack(waitingSource)
    c.tl.c.valid.expect(true.B)
    c.tl.c.bits.opcode.expect(TLMessages.ProbeAckData)
    c.tl.c.bits.param.expect(TLPermissions.BtoN)
    c.tl.c.bits.source.expect(1.U)
    for (_ <- 0 until 4) {
      c.tl.c.bits.address.expect(0.U)
      c.tl.c.bits.data.expect(0xef.U)
      c.tl.c.bits.source.expect(1.U)
      c.tl.a.valid.expect(false.B)
      store(0, 0xdead)
    }
    expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, Seq(0xef, 0xbe))
    grant(heldAcquireSource, 0x7788)
    println("PASS: R9 offset1 wrong-target live ReleaseAck cannot unblock Probe; matching source2 gives next-cycle C, frozen BEEF under stall and blocked same-block store")

    resetDut()
    acquire(2, 0xcafe)
    acquire(0, 0x1234)
    store(0, 0xbeef)
    request(4)
    val crossHeldA = acceptA(4)
    assert(crossHeldA == 1)
    request(2, acquire = false, trunk = false)
    await(c.tl.c.valid.peek().litToBoolean)
    c.tl.c.bits.address.expect(2.U)
    c.tl.c.bits.data.expect(0xfe.U)
    c.clock.step(2)
    request(0, acquire = false, trunk = true)
    c.clock.step(2)
    probe(0, TLPermissions.toB, required = true, source = 1)
    c.clock.step(3)
    for (_ <- 0 until 3) {
      c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
      c.tl.c.bits.address.expect(2.U)
      c.tl.c.bits.data.expect(0xfe.U)
      store(0, 0xdead)
    }
    val ySource = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 2, Seq(0xfe, 0xca))
    val xSource = expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, Seq(0xef, 0xbe))
    assert(ySource == 2 && xSource == 3)
    assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoB, 0, Seq(0xef, 0xbe)) == 1)
    // No ReleaseAck for X has been driven; its earlier Probe snapshot can now finish.
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.denied.poke(false.B)
    c.tl.d.bits.corrupt.poke(false.B)
    ack(ySource)
    ack(xSource)
    grant(crossHeldA, 0x7788)
    println("PASS: R10 legal offset crossing snapshots ProbeX while different ReleaseY stalls, then exact Y/X/ProbeData FIFO completes before X Ack without CPU-store corruption")

    resetDut()
    acquire(2, 0xcafe)
    acquire(0, 0x1234)
    store(0, 0xbeef)
    request(4)
    val lateHeldA = acceptA(4)
    request(2, acquire = false, trunk = false)
    val lateY = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 2, Seq(0xfe, 0xca))
    request(0, acquire = false, trunk = true)
    await(c.tl.c.valid.peek().litToBoolean)
    c.tl.c.bits.address.expect(0.U)
    val lateX = c.tl.c.bits.source.peek().litValue
    assert(lateY == 2 && lateX == 3)
    // B enters only after X has already been publicly presented, even though C is stalled.
    probe(0, TLPermissions.toN, required = true, source = 1)
    c.clock.step(3)
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.denied.poke(false.B)
    c.tl.d.bits.corrupt.poke(false.B)
    ack(lateY)
    c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
    c.tl.c.bits.address.expect(0.U)
    c.tl.c.bits.data.expect(0xef.U)
    assert(expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, Seq(0xef, 0xbe)) == lateX)
    for (_ <- 0 until 4) { c.tl.c.valid.expect(false.B); c.clock.step() }
    ack(lateX)
    c.tl.c.valid.expect(true.B)
    for (_ <- 0 until 4) {
      c.tl.c.bits.opcode.expect(TLMessages.ProbeAckData)
      c.tl.c.bits.data.expect(0xef.U)
      c.tl.c.bits.source.expect(1.U)
      store(0, 0xdead)
    }
    assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, Seq(0xef, 0xbe)) == 1)
    grant(lateHeldA, 0x8877)
    println("PASS: R10 Probe arriving after X Cvalid cannot cross; legal Y Ack does not unblock, matching X Ack enables exact stalled ProbeData and source1 snapshot")

  }

  val fastAckTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(ordinaryEntries = 3, retainedGrantCopy = true, beatBytes = 2)(p)))(p)
  RawTester.test(fastAckTop.module, Seq(WriteVcdAnnotation)) { c =>
    def await(cond: => Boolean): Unit = {
      var left = 100
      while (!cond && left > 0) { c.clock.step(); left -= 1 }
      assert(left > 0, "singlebeat fast Ack control timed out")
    }
    c.in.in_valid.poke(false.B)
    c.in.in_addr.poke(0.U)
    c.in.in_isAcquire.poke(true.B)
    c.in.in_param.poke(true.B)
    c.in.in_store.poke(false.B)
    c.in.in_data.poke(0.U)
    c.tl.a.ready.poke(true.B)
    c.tl.b.valid.poke(false.B)
    c.tl.c.ready.poke(false.B)
    c.tl.d.valid.poke(false.B)
    c.tl.e.ready.poke(true.B)
    c.reset.poke(true.B)
    c.clock.step(2)
    c.reset.poke(false.B)
    c.clock.step(2)
    def request(addr: Int, acquire: Boolean = true): Unit = {
      c.in.in_addr.poke(addr.U)
      c.in.in_isAcquire.poke(acquire.B)
      c.in.in_param.poke(true.B)
      c.in.in_valid.poke(true.B)
      c.clock.step()
      c.in.in_valid.poke(false.B)
    }
    def grant(src: BigInt, word: Int): Unit = {
      c.tl.d.valid.poke(true.B)
      c.tl.d.bits.opcode.poke(TLMessages.GrantData)
      c.tl.d.bits.param.poke(TLPermissions.toT)
      c.tl.d.bits.size.poke(1.U)
      c.tl.d.bits.source.poke(src.U)
      c.tl.d.bits.sink.poke(src.U)
      c.tl.d.bits.denied.poke(false.B)
      c.tl.d.bits.corrupt.poke(false.B)
      c.tl.d.bits.data.poke(word.U)
      c.clock.step()
      c.tl.d.valid.poke(false.B)
      await(c.tl.e.valid.peek().litToBoolean)
      c.clock.step(3)
    }
    request(0)
    await(c.tl.a.valid.peek().litToBoolean)
    val src = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    grant(src, 0x1234)
    c.in.in_valid.poke(true.B)
    c.in.in_store.poke(true.B)
    c.in.in_data.poke(0xbeef.U)
    c.clock.step()
    c.in.in_valid.poke(false.B)
    c.in.in_store.poke(false.B)
    request(0, acquire = false)
    await(c.tl.c.valid.peek().litToBoolean)
    c.tl.c.bits.opcode.expect(TLMessages.ReleaseData)
    c.tl.c.bits.param.expect(TLPermissions.TtoB)
    c.tl.c.bits.data.expect(0xbeef.U)
    val releaseSrc = c.tl.c.bits.source.peek().litValue
    c.tl.c.ready.poke(true.B)
    c.tl.d.valid.poke(true.B)
    c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
    c.tl.d.bits.param.poke(0.U)
    c.tl.d.bits.size.poke(1.U)
    c.tl.d.bits.source.poke(releaseSrc.U)
    c.clock.step()
    c.tl.d.valid.poke(false.B)
    c.tl.c.ready.poke(false.B)
    c.tl.c.valid.expect(false.B)
    request(2)
    await(c.tl.a.valid.peek().litToBoolean)
    c.tl.a.bits.source.expect(releaseSrc.U)
    val nextSrc = c.tl.a.bits.source.peek().litValue
    c.clock.step()
    c.tl.b.bits.opcode.poke(TLMessages.Probe)
    c.tl.b.bits.param.poke(TLPermissions.toN)
    c.tl.b.bits.source.poke(0.U)
    c.tl.b.bits.size.poke(1.U)
    c.tl.b.bits.address.poke(0.U)
    c.tl.b.bits.mask.poke(3.U)
    c.tl.b.bits.data.poke(1.U)
    c.tl.b.bits.corrupt.poke(false.B)
    c.tl.b.valid.poke(true.B)
    await(c.tl.b.ready.peek().litToBoolean)
    c.clock.step()
    c.tl.b.valid.poke(false.B)
    await(c.tl.c.valid.peek().litToBoolean)
    c.tl.c.bits.opcode.expect(TLMessages.ProbeAckData)
    c.tl.c.bits.param.expect(TLPermissions.BtoN)
    c.tl.c.bits.source.expect(0.U)
    c.tl.c.bits.data.expect(0xbeef.U)
    c.tl.c.ready.poke(true.B)
    c.clock.step()
    c.tl.c.ready.poke(false.B)
    grant(nextSrc, 0x7788)
    println("PASS: R10 singlebeat first-presented Release and matching Ack fire together, Ack-clear wins visibility-write, exact BEEF survives and source immediately reusable")
  }
  def runHoldDequeueControls(beatBytes: Int): Unit = {
    val holdTop = DisableMonitors(p => LazyModule(new GeneratorTestTop(sourceStart = 1, ordinaryEntries = 4, retainedGrantCopy = true, beatBytes = beatBytes)(p)))(p)
    RawTester.test(holdTop.module, Seq(WriteVcdAnnotation)) { c =>
      def await(cond: => Boolean): Unit = {
        var left = 150
        while (!cond && left > 0) { c.clock.step(); left -= 1 }
        assert(left > 0, "R11 hold-dequeue control timed out")
      }
      def resetDut(): Unit = {
        c.in.in_valid.poke(false.B); c.in.in_addr.poke(0.U)
        c.in.in_isAcquire.poke(true.B); c.in.in_param.poke(true.B)
        c.in.in_store.poke(false.B); c.in.in_data.poke(0.U)
        c.tl.a.ready.poke(true.B); c.tl.c.ready.poke(false.B); c.tl.e.ready.poke(true.B)
        c.tl.b.valid.poke(false.B); c.tl.b.bits.opcode.poke(TLMessages.Probe)
        c.tl.b.bits.param.poke(TLPermissions.toB); c.tl.b.bits.size.poke(1.U)
        c.tl.b.bits.source.poke(1.U); c.tl.b.bits.address.poke(0.U)
        c.tl.b.bits.mask.poke(((1 << beatBytes) - 1).U)
        c.tl.b.bits.data.poke(0.U); c.tl.b.bits.corrupt.poke(false.B)
        c.tl.d.valid.poke(false.B); c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
        c.tl.d.bits.param.poke(0.U); c.tl.d.bits.size.poke(1.U)
        c.tl.d.bits.source.poke(1.U); c.tl.d.bits.sink.poke(0.U)
        c.tl.d.bits.data.poke(0.U); c.tl.d.bits.denied.poke(false.B); c.tl.d.bits.corrupt.poke(false.B)
        c.reset.poke(true.B); c.clock.step(2); c.reset.poke(false.B); c.clock.step(2)
      }
      def request(addr: Int, acquire: Boolean = true, trunk: Boolean = true): Unit = {
        c.in.in_addr.poke(addr.U); c.in.in_isAcquire.poke(acquire.B)
        c.in.in_param.poke(trunk.B); c.in.in_valid.poke(true.B)
        c.clock.step(); c.in.in_valid.poke(false.B)
      }
      def acceptA(addr: Int): BigInt = {
        await(c.tl.a.valid.peek().litToBoolean); c.tl.a.bits.address.expect(addr.U)
        val src = c.tl.a.bits.source.peek().litValue
        assert(src >= 1 && src <= 4, "R11 offset source range")
        c.clock.step(); src
      }
      def bytes(word: Int): Seq[Int] = if (beatBytes == 2) Seq(word) else Seq(word & 255, word >> 8)
      def grant(src: BigInt, word: Int, trunk: Boolean = true): Unit = {
        for (value <- bytes(word)) {
          c.tl.d.valid.poke(true.B); c.tl.d.bits.opcode.poke(TLMessages.GrantData)
          c.tl.d.bits.param.poke(if (trunk) TLPermissions.toT else TLPermissions.toB)
          c.tl.d.bits.size.poke(1.U); c.tl.d.bits.source.poke(src.U)
          c.tl.d.bits.sink.poke((src - 1).U); c.tl.d.bits.data.poke(value.U)
          c.tl.d.bits.denied.poke(false.B); c.tl.d.bits.corrupt.poke(false.B)
          c.clock.step()
        }
        c.tl.d.valid.poke(false.B); await(c.tl.e.valid.peek().litToBoolean)
        c.tl.e.bits.sink.expect((src - 1).U); c.clock.step(3)
      }
      def acquire(addr: Int, word: Int): Unit = { request(addr); grant(acceptA(addr), word) }
      def store(addr: Int, word: Int): Unit = {
        c.in.in_addr.poke(addr.U); c.in.in_isAcquire.poke(true.B); c.in.in_param.poke(true.B)
        c.in.in_store.poke(true.B); c.in.in_data.poke(word.U); c.in.in_valid.poke(true.B)
        c.clock.step(); c.in.in_valid.poke(false.B); c.in.in_store.poke(false.B)
      }
      def armProbe(addr: Int, cap: UInt, required: Boolean): Unit = {
        c.tl.b.bits.opcode.poke(TLMessages.Probe); c.tl.b.bits.param.poke(cap)
        c.tl.b.bits.size.poke(1.U); c.tl.b.bits.source.poke(1.U)
        c.tl.b.bits.address.poke(addr.U); c.tl.b.bits.mask.poke(((1 << beatBytes) - 1).U)
        c.tl.b.bits.data.poke((if (required) 1 else 0).U); c.tl.b.bits.corrupt.poke(false.B)
        c.tl.b.valid.poke(true.B); c.tl.b.ready.expect(true.B)
      }
      def probe(addr: Int, cap: UInt, required: Boolean): Unit = {
        armProbe(addr, cap, required); c.clock.step(); c.tl.b.valid.poke(false.B)
      }
      def expectC(op: UInt, param: UInt, addr: Int, values: Seq[Int]): BigInt = {
        var src = BigInt(0)
        for ((value, beat) <- values.zipWithIndex) {
          await(c.tl.c.valid.peek().litToBoolean)
          c.tl.c.bits.opcode.expect(op); c.tl.c.bits.param.expect(param)
          c.tl.c.bits.address.expect(addr.U); c.tl.c.bits.size.expect(1.U)
          c.tl.c.bits.data.expect(value.U); c.tl.c.bits.corrupt.expect(false.B)
          if (beat == 0) src = c.tl.c.bits.source.peek().litValue
          c.tl.c.bits.source.expect(src.U); c.tl.c.ready.poke(true.B); c.clock.step()
        }
        c.tl.c.ready.poke(false.B); src
      }
      def ack(src: BigInt): Unit = {
        c.tl.d.valid.poke(true.B); c.tl.d.bits.opcode.poke(TLMessages.ReleaseAck)
        c.tl.d.bits.param.poke(0.U); c.tl.d.bits.size.poke(1.U)
        c.tl.d.bits.source.poke(src.U); c.tl.d.bits.denied.poke(false.B); c.tl.d.bits.corrupt.poke(false.B)
        c.clock.step(); c.tl.d.valid.poke(false.B)
      }
      def queueXBehindY(): BigInt = {
        request(2, acquire = false, trunk = false)
        await(c.tl.c.valid.peek().litToBoolean)
        c.tl.c.bits.address.expect(2.U); c.tl.c.bits.data.expect(bytes(0xcafe).head.U)
        val y = c.tl.c.bits.source.peek().litValue
        request(0, acquire = false, trunk = true); c.clock.step(2)
        assert(y == 1, "Y owns offset source1")
        y
      }
      def finishYAndEnterBubble(cap: UInt, required: Boolean, holdExpected: Boolean): BigInt = {
        c.tl.c.bits.address.expect(2.U)
        if (beatBytes == 1) {
          c.tl.c.bits.data.expect(0xfe.U); c.tl.c.ready.poke(true.B); c.clock.step()
          c.tl.c.bits.data.expect(0xca.U)
        }
        armProbe(0, cap, required); c.tl.c.ready.poke(true.B); c.clock.step()
        c.tl.b.valid.poke(false.B); c.tl.c.ready.poke(false.B)
        c.tl.c.valid.expect(false.B)
        // E0: a real Probe snapshot holds dequeue/load; the full-FIFO negative must not hold.
        c.clock.step()
        c.tl.c.valid.expect((!holdExpected).B)
        if (holdExpected) { c.clock.step(); c.tl.c.valid.expect(true.B) }
        c.tl.c.bits.opcode.expect(TLMessages.ReleaseData); c.tl.c.bits.address.expect(0.U)
        c.tl.c.bits.param.expect(TLPermissions.TtoB); c.tl.c.bits.data.expect(bytes(0xbeef).head.U)
        val x = c.tl.c.bits.source.peek().litValue
        assert(x == 2, "X owns offset source2")
        x
      }

      if (beatBytes == 1) {
        resetDut(); acquire(2, 0xcafe); acquire(0, 0x1234); store(0, 0xbeef)
        val y = queueXBehindY()
        val x = finishYAndEnterBubble(TLPermissions.toB, required = false, holdExpected = true)
        for (_ <- 0 until 4) { c.tl.c.bits.data.expect(0xef.U); store(0, 0xdead) }
        assert(expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, bytes(0xbeef)) == x)
        assert(expectC(TLMessages.ProbeAck, TLPermissions.BtoB, 0, Seq(0)) == 1)
        // Only after the first ProbeAck completes may the manager send a second Probe to X.
        probe(0, TLPermissions.toN, required = true)
        for (_ <- 0 until 4) { c.tl.c.valid.expect(false.B); c.clock.step() }
        ack(y)
        for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
        ack(x); c.tl.c.valid.expect(true.B)
        assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, bytes(0xbeef)) == 1)
        println("PASS: R11 idle bubble NoData snapshot holds X unpublished for a full cycle; later fresh Probe waits matching Ack and unrelated Ack cannot unblock")

        resetDut(); acquire(2, 0xcafe); acquire(0, 0x1234); store(0, 0xbeef)
        val dy = queueXBehindY()
        val dx = finishYAndEnterBubble(TLPermissions.toN, required = true, holdExpected = true)
        for (_ <- 0 until 4) {
          c.tl.c.bits.data.expect(0xef.U); c.tl.b.bits.address.poke(6.U); c.tl.b.bits.data.poke(0.U)
          store(0, 0xdead)
        }
        assert(expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, bytes(0xbeef)) == dx)
        c.tl.c.ready.poke(false.B)
        await(c.tl.c.valid.peek().litToBoolean)
        for (_ <- 0 until 3) { c.tl.c.bits.data.expect(0xef.U); c.clock.step() }
        assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, bytes(0xbeef)) == 1)
        ack(dy); ack(dx); acquire(0, 0x8877)
        println("PASS: R11 idle bubble ProbeData freezes BEEF before first public X; exact X/Probe FIFO bytes survive C stalls and live input changes without T resurrection")

        resetDut(); acquire(2, 0xcafe); acquire(0, 0x1234); store(0, 0xbeef)
        acquire(4, 0x4567); acquire(6, 0x89ab)
        val fy = queueXBehindY()
        request(4, acquire = false, trunk = false); c.clock.step(2)
        request(6, acquire = false, trunk = false); c.clock.step(2)
        val fx = finishYAndEnterBubble(TLPermissions.toN, required = true, holdExpected = false)
        assert(expectC(TLMessages.ReleaseData, TLPermissions.TtoB, 0, bytes(0xbeef)) == fx)
        val fz = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 4, bytes(0x4567))
        val fw = expectC(TLMessages.ReleaseData, TLPermissions.TtoN, 6, bytes(0x89ab))
        assert(Seq(fy, fx, fz, fw) == Seq(BigInt(1), BigInt(2), BigInt(3), BigInt(4)))
        for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
        ack(fy)
        for (_ <- 0 until 3) { c.tl.c.valid.expect(false.B); c.clock.step() }
        ack(fx)
        assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoN, 0, bytes(0xbeef)) == 1)
        ack(fz); ack(fw); request(8); val reuse = acceptA(8); assert(reuse == 1); grant(reuse, 0x7788)
        println("PASS: R11 n4 full SourceC FIFO never holds without actual Probe.fire; X/Z/W drain, all four source IDs remain legal, late Probe waits own Ack and slots reuse")
      } else {
        resetDut(); acquire(2, 0xcafe); acquire(0, 0x1234); store(0, 0xbeef)
        val sy = queueXBehindY()
        val sx = finishYAndEnterBubble(TLPermissions.toB, required = true, holdExpected = true)
        // Earliest legal Ack: X is now publicly valid and this single beat is accepted with it.
        c.tl.c.ready.poke(true.B); ack(sx); c.tl.c.ready.poke(false.B)
        c.tl.c.valid.expect(false.B)
        request(4); val reuse = acceptA(4); assert(reuse == sx, "Ack clear must win and free X source")
        assert(expectC(TLMessages.ProbeAckData, TLPermissions.BtoB, 0, bytes(0xbeef)) == 1)
        grant(reuse, 0x7788); ack(sy)
        println("PASS: R11 singlebeat held bubble keeps snapshot strictly before first Cvalid; matching first-C Ack clears Idle over visibility write and source reuses while Probe FIFO remains exact")
      }
    }
  }
  runHoldDequeueControls(1)
  runHoldDequeueControls(2)

}
