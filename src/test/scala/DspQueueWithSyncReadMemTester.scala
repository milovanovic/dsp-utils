package dsputils

import chisel3._
import chisel3.util._
import chisel3.experimental._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.Parameters

import chisel3.iotesters.PeekPokeTester

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source

import java.io._

class DspQueueWithSyncReadMemTester(
  dut: AXI4DspQueueWithSyncReadMem with AXI4DspQueueStandaloneBlock,
  address: AddressSet,
  beatBytes: Int = 4,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {

  override def memAXI: AXI4Bundle = dut.ioMem.get
  val mod     = dut.module

  // Connect AXI4StreamModel to DUT
  //val master = bindMaster(dut.in)

  // dspQueue should output data after specific number of samples
  memWriteWord(address.base, 2084)
  memWriteWord(address.base + beatBytes, 1)

  poke(dut.out.ready, true) // make output always ready to accept data
  val testData = (1 until 16000)
  var cntOutData =0
  // do not use add transactions, use for loop for generating input signals
  for (x <-1 to 16000) {
    poke(dut.in.valid, 1)
    poke(dut.in.bits.data, x)
    if (peek(dut.out.valid) == 1) {
      println("I am here")
      expect(dut.out.bits.data, testData(cntOutData))
      cntOutData += 1
    }
    step(1)
    poke(dut.in.valid, 0)
    if (peek(dut.out.valid) == 1) {
      println("I am here")
      expect(dut.out.bits.data, testData(cntOutData))
      cntOutData += 1
    }
    step(1)
  }
  step(512)
}

class DspQueueWithSyncReadMem_Spec extends AnyFlatSpec with Matchers {
  // define parameters
  val params = DspQueueCustomParams(progFull = true, addEnProgFullOut = true)
  val beatBytes = 4
  val address = AddressSet(0x000000, 0xFFFF)

  implicit val p: Parameters = Parameters.empty

  // run test
  val testModule = LazyModule(new AXI4DspQueueWithSyncReadMem(params, address, beatBytes) with AXI4DspQueueStandaloneBlock)

  it should "Test rsp chain version 1 without asyncQueue and jtag" in {
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => testModule.module) {
          c => new DspQueueWithSyncReadMemTester(dut = testModule,
                                address = address,
                                beatBytes = beatBytes,
                                silentFail  = false
                                )
    } should be (true)
  }
}

