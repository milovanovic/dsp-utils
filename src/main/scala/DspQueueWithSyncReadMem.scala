package dsputils

import chisel3._
import chisel3.util._

import dspblocks._ // included because of DspQueue

import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._

case class DspQueueCustomParams(
  queueDepth: Int = 16384,
  progFull: Boolean = false,
  useSyncReadMem: Boolean = true, // use block ram
  addEnProgFullOut: Boolean = false
) {
  // add some requirements
}

trait AXI4DspQueueStandaloneBlock extends AXI4DspQueueWithSyncReadMem {
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  val ioMem = mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}

  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    streamNode :=
    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
    ioInNode

  val in = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }
}

// This block is implemented mostly for debug purposes

abstract class DspQueueWithSyncReadMem [D, U, E, O, B <: Data] (val params: DspQueueCustomParams) extends LazyModule()(Parameters.empty) with DspBlock[D, U, E, O, B] with HasCSR {
  val streamNode = AXI4StreamIdentityNode()
  val depth = params.queueDepth

  lazy val module = new LazyModuleImp(this) {
    val (streamIn, _)  = streamNode.in(0)
    val (streamOut, _) = streamNode.out(0)
    val queuedStream = Module(new QueueWithSyncReadMem(chiselTypeOf(streamIn.bits), entries = depth, useSyncReadMem = params.useSyncReadMem, flow = false, pipe = true))

    queuedStream.io.enq.valid := streamIn.valid
    queuedStream.io.enq.bits.data := streamIn.bits.data
    queuedStream.io.enq.bits.strb := DontCare
    queuedStream.io.enq.bits.keep := DontCare
    queuedStream.io.enq.bits.id := DontCare
    queuedStream.io.enq.bits.dest := DontCare
    queuedStream.io.enq.bits.last := streamIn.bits.last
    queuedStream.io.enq.bits.user := DontCare

    streamIn.ready := queuedStream.io.enq.ready
    streamOut.bits := queuedStream.io.deq.bits

    val queueProgFullVal = RegInit((depth/2).U(log2Ceil(depth).W))
    val enProgFull = RegInit(true.B)
    val progFull = RegInit(false.B)
    // this is just debug thing
    val enProgFullReg = if (params.addEnProgFullOut) Some(IO(Output(Bool()))) else None

    if (params.addEnProgFullOut) {
      enProgFullReg.get := enProgFull
    }

    if (params.progFull) {
      when (queuedStream.io.count === queueProgFullVal) {
        progFull := true.B
      }
      when (queuedStream.io.count === 0.U) {
        progFull := false.B
      }
      when (enProgFull) {
        queuedStream.io.deq.ready := Mux(progFull, streamOut.ready, false.B)
        streamOut.valid := Mux(progFull, queuedStream.io.deq.valid, false.B)
      }
      .otherwise {
        streamOut.valid := queuedStream.io.deq.valid
        queuedStream.io.deq.ready := streamOut.ready
      }
    }
    else {
      streamOut.valid := queuedStream.io.deq.valid
      queuedStream.io.deq.ready := streamOut.ready
    }
    // TODO: Make whole memory mapped optional
    regmap(0 ->
      Seq(RegField(32, queueProgFullVal,
        RegFieldDesc("queueProgFullVal", "Fill queue even though output is ready to accept data"))),
      32 ->
      Seq(RegField(1, enProgFull,
        RegFieldDesc("enProgFull", "Enable programable full logic"))))
  }
}

class AXI4DspQueueWithSyncReadMem(params: DspQueueCustomParams, address: AddressSet, _beatBytes: Int = 4)(implicit p: Parameters) extends DspQueueWithSyncReadMem[AXI4MasterPortParameters, AXI4SlavePortParameters, AXI4EdgeParameters, AXI4EdgeParameters, AXI4Bundle](params) with AXI4DspBlock with AXI4HasCSR {
  override val mem = Some(AXI4RegisterNode(address = address, beatBytes = _beatBytes))
}

object DspQueueApp extends App
{
  val params: DspQueueCustomParams = DspQueueCustomParams()

  val baseAddress = 0x500
  implicit val p: Parameters = Parameters.empty
  val queueModule = LazyModule(new AXI4DspQueueWithSyncReadMem(params, AddressSet(baseAddress + 0x100, 0xFF), _beatBytes = 4) with AXI4DspQueueStandaloneBlock)
  chisel3.Driver.execute(args, ()=> queueModule.module)
}

