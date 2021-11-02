package constellation.router

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.DecodeLogic

import constellation._

class RouteComputerReq(val cParam: ChannelParams)(implicit val p: Parameters) extends Bundle with HasChannelParams {
  val src_virt_id = UInt(virtualChannelBits.W)
  val src_vnet_id = UInt(vNetBits.W)
  val dest_id = UInt(nodeIdBits.W)
}

class RouteComputerResp(val cParam: ChannelParams,
  val outParams: Seq[ChannelParams],
  val egressParams: Seq[ChannelParams])(implicit val p: Parameters) extends Bundle
    with HasChannelParams with HasRouterOutputParams {

  val src_virt_id = UInt(virtualChannelBits.W)
  val vc_sel = MixedVec(allOutParams.map { u => Vec(u.nVirtualChannels, Bool()) })
}



class RouteComputer(val rP: RouterParams)(implicit val p: Parameters) extends Module with HasRouterParams {
  val io = IO(new Bundle {
    val req = MixedVec(allInParams.map { u => Flipped(Decoupled(new RouteComputerReq(u))) })
    val resp = MixedVec(allInParams.map { u => Valid(new RouteComputerResp(u, outParams, egressParams)) })
  })

  (io.req zip io.resp).zipWithIndex.map { case ((req, resp), i) =>
    req.ready := true.B
    resp.valid := req.valid
    resp.bits.src_virt_id := req.bits.src_virt_id
    if (outParams.size == 0) {
      assert(!req.valid)
      resp.bits.vc_sel := DontCare
    } else {
      (0 until nAllOutputs).map { o =>
        if (o < nOutputs) {
          (0 until outParams(o).nVirtualChannels).map { outVId =>
            val table = allInParams(i).possiblePackets
              .toSeq.map { t => (egressNodes(t._1), t._2) }.distinct.map { case (dest, vNetId) =>
              Seq.tabulate(allInParams(i).nVirtualChannels) { inVId =>
                val v = rP.masterAllocTable(
                  allInParams(i).srcId, inVId,
                  outParams(o).destId, outVId,
                  dest, vNetId)
                ((((inVId << vNetBits) + vNetId) << nodeIdBits) + dest, v)
              }
            }.flatten
            val trues = table.filter(_._2).map(_._1.U)
            val falses = table.filter(!_._2).map(_._1.U)
            val addr = Cat(req.bits.src_virt_id, req.bits.src_vnet_id, req.bits.dest_id)

            resp.bits.vc_sel(o)(outVId) := (if (falses.size == 0) {
              true.B
            } else if (trues.size == 0) {
              false.B
            } else {
              // The Quine-McCluskey impl in rocketchip memory leaks sometimes here...
              if (trues.size + falses.size >= 100) {
                if (trues.size > falses.size) {
                  falses.map(_ =/= addr).andR
                } else {
                  trues.map(_ === addr).orR
                }
              } else {
                DecodeLogic(addr, trues, falses)
              }
            })
          }
        } else {
          resp.bits.vc_sel(o)(0) := false.B
        }
      }
    }
  }
}
