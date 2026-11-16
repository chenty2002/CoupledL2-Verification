package coupledL2Verification

import circt.stage.ChiselStage
import coupledL2._
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import huancun.{DirtyField, HCCacheParameters, HCCacheParamsKey}
import utility._

import java.io._
import scala.collection.mutable.ArrayBuffer
import scala.sys.process._

object AutoVerify extends App {
  def modifyPy(filename: String): Unit = {
    val pylines = new ArrayBuffer[String]()
    val pyFile = new BufferedReader(new FileReader(new File("set_verify.py")))
    var line = pyFile.readLine()
    while(line != null) {
      pylines.append(
        line.replaceFirst("open\\('.*'\\) as fin:", "open('Verilog/VerifyTop.sv') as fin:")
          .replaceFirst("open\\('.*', 'w'\\) as fout:", s"open('${filename}', 'w') as fout:")
      )
      line = pyFile.readLine()
    }
    pyFile.close()

    val newPy = new BufferedWriter(new FileWriter(new File("set_verify_.py")))
    pylines.foreach { line =>
      newPy.write(line + "\n")
    }
    newPy.close()
  }

  val config = baseConfig(1).alterPartial({
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })

  val useLarge = VerifyMode.resolveUseLarge()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")

  val top = DisableMonitors(p => LazyModule(
    if (useLarge) new VerifyTop_large()(p) else new VerifyTop_small()(p)
  ))(config)

  new File("Verilog").mkdirs()
  FileRegisters.writeOutputFile(
    "Verilog",
    "VerifyTop.sv",
    ChiselStage.emitSystemVerilog(top.module, 
                                  args = Array("--warn-conf", "id=4:s"),
                                  firtoolOpts = Array("--disable-annotation-unknown"))
  )
  val suffix = "write_read"
  val filename = s"VerifyTop_${suffix}.sv"
  modifyPy(filename)
  val py = "python set_verify_.py".!
  require(py == 0, "SystemVerilog post-processing failed")
  println(s"Verilog File Name: ${filename}")
  val path = "../Verilog"
  val cpjg = s"cp ${filename} ${path}/${filename}".!
  require(cpjg == 0, "Failed to copy formal top")
}
