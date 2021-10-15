package astronoc

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}

class Channel(val cParams: ChannelParams)(implicit val p: Parameters) extends Bundle with HasAstroNoCParams {
  val flit = Valid(new Flit)
  val credit_return = Input(Valid(UInt(log2Up(cParams.virtualChannelParams.size).W)))
  val vc_free = Input(Valid(UInt(log2Up(cParams.virtualChannelParams.size).W)))
}

class IOChannel(val cParams: ChannelParams)(implicit val p: Parameters) extends Bundle with HasAstroNoCParams {
  val flit = Decoupled(new Flit)
}

case class RouterParams(
  id: Int,
  inParams: Seq[ChannelParams],
  outParams: Seq[ChannelParams],
  terminalInParams: Seq[ChannelParams],
  terminalOutParams: Seq[ChannelParams],
  vcAllocLegalPaths: (Int, Int, Int, Int) => Int => Boolean,
  routingFunction: (Int, Int) => Int => Boolean
)

trait HasRouterParams extends HasAstroNoCParams {
  val rParams: RouterParams
  val id = rParams.id
  val inParams = rParams.inParams
  val outParams = rParams.outParams
  val terminalInParams = rParams.terminalInParams
  val terminalOutParams = rParams.terminalOutParams
  val allInParams = inParams ++ terminalInParams
  val allOutParams = outParams ++ terminalOutParams

  val nInputs = inParams.size
  val nOutputs = outParams.size
  val nTerminalInputs = terminalInParams.size
  val nTerminalOutputs = terminalOutParams.size
}

class Router(val rParams: RouterParams)(implicit val p: Parameters) extends Module with HasRouterParams {
  val io = IO(new Bundle {
    val in = MixedVec(inParams.map { u => Flipped(new Channel(u)) })
    val terminal_in = MixedVec(terminalInParams.map { u => Flipped(new IOChannel(u)) })
    val out = MixedVec(outParams.map { u => new Channel(u) })
    val terminal_out = MixedVec(terminalOutParams.map { u => new IOChannel(u) })
  })

  require(nInputs + nTerminalInputs >= 1)
  require(nOutputs + nTerminalOutputs >= 1)
  require(id < (1 << idBits))

  val input_units = inParams.map { u =>
    Module(new InputUnit(u, outParams, terminalOutParams)) }
  val terminal_input_units = terminalInParams.map { u =>
    Module(new TerminalInputUnit(u, outParams, terminalOutParams)) }
  val all_input_units = input_units ++ terminal_input_units

  val output_units = outParams.map { u =>
    Module(new OutputUnit(inParams, terminalInParams, u)) }
  val terminal_output_units = terminalOutParams.map { u =>
    Module(new TerminalOutputUnit(inParams, terminalInParams, u)) }
  val all_output_units = output_units ++ terminal_output_units

  val switch = Module(new Switch(nInputs + nTerminalInputs, nOutputs + nTerminalOutputs))
  val switch_allocator = Module(new SwitchAllocator(rParams))
  val vc_allocator = Module(new VCAllocator(rParams))
  val route_computer = Module(new RouteComputer(rParams))

  (io.in zip input_units).foreach {
    case (i,u) => u.io.in <> i }
  (io.terminal_in zip terminal_input_units).foreach {
    case (i,u) => u.io.in <> i.flit }
  (output_units zip io.out).foreach {
    case (u,o) => o <> u.io.out }
  (terminal_output_units zip io.terminal_out).foreach {
    case (u,o) => o.flit <> u.io.out }

  (route_computer.io.req zip all_input_units).foreach {
    case (i,u) => i <> u.io.router_req }
  (all_input_units zip route_computer.io.resp).foreach {
    case (u,o) => u.io.router_resp <> o }

  (vc_allocator.io.req zip all_input_units).foreach {
    case (i,u) => i <> u.io.vcalloc_req }
  (all_input_units zip vc_allocator.io.resp).foreach {
    case (u,o) => u.io.vcalloc_resp <> o }


  (all_output_units zip vc_allocator.io.out_alloc).foreach {
    case (u,a) => u.io.alloc <> a }
  (vc_allocator.io.channel_available zip all_output_units).foreach {
    case (a,u) => a := u.io.channel_available }

  all_input_units.foreach(u => (u.io.out_credit_available zip all_output_units).foreach {
    case (l,r) => l := r.io.credit_available })
  (all_input_units zip switch_allocator.io.req).foreach {
    case (u,r) => r <> u.io.salloc_req }
  (output_units zip switch_allocator.io.credit_alloc).foreach {
    case (u,a) => u.io.credit_alloc := a }

  (switch.io.in zip all_input_units).foreach {
    case (i,u) => i <> u.io.out }
  (all_output_units zip switch.io.out).foreach {
    case (u,o) => u.io.in <> o }

}
