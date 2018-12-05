package ru.serbis.okto.sv_blg.proxy.system
import akka.actor.{ActorRef, ActorSystem, Props}

class RealActorSystemProxy(system: ActorSystem) extends ActorSystemProxy {
  override def actorOf(props: Props): ActorRef = system.actorOf(props)
}
