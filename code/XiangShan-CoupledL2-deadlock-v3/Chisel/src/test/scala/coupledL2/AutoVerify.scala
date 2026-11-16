package coupledL2

import scala.sys.process._
import chisel3.stage.ChiselStage
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import huancun.{DirtyField, HCCacheParameters, HCCacheParamsKey}
import org.chipsalliance.cde.config.Config

import java.io._
import scala.collection.mutable.ArrayBuffer
import org.chipsalliance.cde.config.Parameters

object AutoVerify_L2L3L2 extends App {
  def modifyPy(filename: String): Unit = {
    val pylines = new ArrayBuffer[String]()
    val pyFile = new BufferedReader(new FileReader(new File("set_verify.py")))
    var line = pyFile.readLine()
    while(line != null) {
      pylines.append(
        line.replaceFirst("open\\('.*', 'w'\\) as fout:", s"open('${filename}', 'w') as fout:")
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

  val config = new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
     // echoField = Seq(DirtyField())
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
  })

  val suffix = "acquire"
  val useLarge = VerifyMode.resolveUseLarge()
  println(s"VERIFY_MODE=${if (useLarge) "large" else "small"}")
  val path = "../Verilog"
  val top = DisableMonitors(p => LazyModule(
    if (useLarge) new VerifyTop_large()(p) else new VerifyTop_small()(p)
  ))(config)

  (new ChiselStage).emitSystemVerilog(
    top.module,
    Array("--target-dir", s"Verilog/L2L3L2")
  )
  val cp = s"cp Verilog/L2L3L2/VerifyTop.sv .".!
  require(cp == 0, "Failed to stage generated SystemVerilog")
  val filename = s"VerifyTop_${suffix}.sv"
  modifyPy(filename)
  val py = "python set_verify_.py".!
  require(py == 0, "SystemVerilog post-processing failed")
  val cpjg = s"cp ${filename} ${path}/${filename}".!
  require(cpjg == 0, "Failed to copy formal top")
}
