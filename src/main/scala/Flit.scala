package astronoc

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}

class Flit(implicit val p: Parameters) extends Bundle with HasAstroNoCParams{
  val head = Bool()
  val tail = Bool()
  val prio = UInt(prioBits.W)
  val dest_id = UInt(idBits.W)
  val virt_channel_id = UInt(virtChannelBits.W)
  val flits_in_packet = UInt((1+log2Ceil(maxFlits)).W)

  val payload = UInt(flitPayloadBits.W)
}
