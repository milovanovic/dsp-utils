package dsputils

import chisel3._
import chisel3.util._
import chisel3.experimental.IO
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

case class AXI4StreamAsyncQueueWithControlParams(
  channels   : Int,
  dataBytes  : Int,
  ctrlBits   : Int,
  isFullFlag : Boolean,
  // AsyncQueue params
  sync       : Int,
  depth      : Int,
  safe       : Boolean
) {
  // add some requirements
}

/* AXI4StreamAsyncQueueWithControl Bundle */
class AXI4StreamAsyncQueueWithControlIO(dataBytes: Int, channels: Int, ctrlBits: Int) extends Bundle {
    // Input
    val in_data  = Input(UInt((dataBytes*channels*8).W))
    val in_valid = Input(Bool())
    val in_ctrl  = Input(UInt(ctrlBits.W))

    // Output
    val out_ctrl = Output(UInt(ctrlBits.W))
    val fire     = Output(Bool())

    override def cloneType: this.type = AXI4StreamAsyncQueueWithControlIO(dataBytes, channels, ctrlBits).asInstanceOf[this.type]
}
object AXI4StreamAsyncQueueWithControlIO{
  def apply(dataBytes: Int, channels: Int, ctrlBits: Int): AXI4StreamAsyncQueueWithControlIO = new AXI4StreamAsyncQueueWithControlIO(dataBytes, channels, ctrlBits)
}

class AXI4StreamAsyncQueueWithControl(params: AXI4StreamAsyncQueueWithControlParams) extends LazyModule()(Parameters.empty) {
  // Master Node parameters
  val masterParams = AXI4StreamMasterParameters(
    name = "outStream",
    n    = params.dataBytes,
    u    = 0,
    numMasters = 1
  )
  val streamNode = Seq.fill(params.channels){AXI4StreamMasterNode(masterParams)}

  // IOs
  lazy val io = Wire(new AXI4StreamAsyncQueueWithControlIO(params.dataBytes, params.channels, params.ctrlBits))

  lazy val module = new LazyModuleImp(this) {
    // Output
    val out = streamNode.map(m => m.out(0)._1)

    // Clock & reset (Write side)
    val write_clock  = IO(Input(Clock()))
    val write_reset = IO(Input(Bool()))

    // Optional QueueFull flag (~in.ready), can be used as a potential monitoring help
    val queueFull = if (params.isFullFlag) Some(IO(Output(Bool()))) else None

    // AsyncQueue
    val asyncQueue = Module(new AsyncQueueModule(chiselTypeOf(Cat(io.in_ctrl, io.in_data)), depth = params.depth, sync = params.sync, safe = params.safe))

    // Connect asyncQueue (Write side)
    asyncQueue.io.enq_clock := write_clock
    asyncQueue.io.enq_reset := write_reset.asBool
    asyncQueue.io.enq.bits  := Cat(io.in_ctrl, io.in_data).asUInt
    asyncQueue.io.enq.valid := io.in_valid
    if (params.isFullFlag) {
      queueFull.get := ~asyncQueue.io.enq.ready
    }

    // Connect asyncQueue (Read side)
    asyncQueue.io.deq_clock := clock
    asyncQueue.io.deq_reset := reset.asBool
    asyncQueue.io.deq.ready := out.map(m => m.ready).reduce((a,b) => a && b)
    out.zipWithIndex.map{ case (m, i) => {
      m.valid := asyncQueue.io.deq.valid
      m.bits.data := asyncQueue.io.deq.bits(((i+1)*params.dataBytes*8 - 1), i*params.dataBytes*8).asUInt
    }}
    io.out_ctrl := asyncQueue.io.deq.bits(io.in_data.getWidth + io.in_ctrl.getWidth - 1, io.in_data.getWidth)

    io.fire := asyncQueue.io.deq.fire()
  }
}

trait AXI4StreamAsyncQueueWithControlStandalone extends AXI4StreamAsyncQueueWithControl {
  // output stream node
  val streamLen = streamNode.length

  val ioOutNode = Seq.fill(streamLen){BundleBridgeSink[AXI4StreamBundle]()}

  val outPins = for (i <- 0 until streamLen) yield {
    ioOutNode(i) := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := streamNode(i)
    val out = InModuleBody { 
      implicit val valName = ValName(s"out_$i")
      ioOutNode(i).makeIO()
    }
    out
  }

  // pins
  def makeCustomIO(): AXI4StreamAsyncQueueWithControlIO = {
    val io2: AXI4StreamAsyncQueueWithControlIO = IO(io.cloneType)
    io2.suggestName("io")
    io2 <> io
    io2
  }
  val ioBlock = InModuleBody { makeCustomIO() }
}

object AXI4StreamAsyncQueueWithControlApp extends App
{
  implicit val p: Parameters = Parameters.empty

  val params: AXI4StreamAsyncQueueWithControlParams = AXI4StreamAsyncQueueWithControlParams(
    channels   = 4,
    dataBytes  = 2,
    ctrlBits   = 3,
    isFullFlag = false,
    sync       = 4,
    depth      = 2048,
    safe       = true
  )
  val lazyDUT = LazyModule(new AXI4StreamAsyncQueueWithControl(params) with AXI4StreamAsyncQueueWithControlStandalone)
  (new ChiselStage).execute(Array("--target-dir", "verilog/AXI4StreamAsyncQueueWithControl"), Seq(ChiselGeneratorAnnotation(() => lazyDUT.module)))
}