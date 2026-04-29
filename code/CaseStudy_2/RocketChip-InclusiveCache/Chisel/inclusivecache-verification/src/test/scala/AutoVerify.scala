package verifyl2

import circt.stage.ChiselStage
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}

import java.io.{BufferedWriter, File, FileWriter}

object AutoVerify extends App {
  private def writeText(file: File, text: String): Unit = {
    val writer = new BufferedWriter(new FileWriter(file))
    try {
      writer.write(text)
    } finally {
      writer.close()
    }
  }

  val cfg = new MiniL2Config
  val top = DisableMonitors(p => LazyModule(new TestTop()(p)))(cfg)

  val verilog = ChiselStage.emitSystemVerilog(
    top.module,
    args = Array("--warn-conf", "id=4:s"),
    firtoolOpts = Array("--disable-annotation-unknown")
  )

  val outDir = new File("Verilog")
  if (!outDir.exists()) {
    outDir.mkdirs()
  }

  val baseFile = new File(outDir, "VerifyTop.sv")
  val targetName = "VerifyTop_deadlock_freeness.sv"
  val targetFile = new File(outDir, targetName)

  writeText(baseFile, verilog)
  writeText(targetFile, verilog)
  println(s"Verilog File Name: ${targetName}")
}
