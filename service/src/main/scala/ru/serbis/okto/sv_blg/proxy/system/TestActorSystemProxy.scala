package ru.serbis.okto.sv_blg.proxy.system
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.Await

object TestActorSystemProxy {
  object Actions {
    case class ActorOf(props: Props)
  }

  object Predicts {
    case class Throw(ex: Throwable)
  }
}

class TestActorSystemProxy(tpRef: ActorRef) extends ActorSystemProxy {
  import TestActorSystemProxy._

  override def actorOf(props: Props): ActorRef = {
    Await.result(tpRef.ask(Actions.ActorOf(props))(3 second), 3 second) match {
      case Predicts.Throw(ex) => throw ex
      case v: ActorRef => v
    }
  }
}
