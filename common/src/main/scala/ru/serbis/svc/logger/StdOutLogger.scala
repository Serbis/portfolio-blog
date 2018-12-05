package ru.serbis.svc.logger

import akka.actor.{Actor, Props}
import ru.serbis.svc.logger.Logger._
import ru.serbis.svc.streams.RouterSink2.Ack

object StdOutLogger {
  def props = Props(new StdOutLogger)
}

class StdOutLogger extends Actor {
  override def receive = {
    case t: LogMessage =>
      println(format(t))
      sender() ! Ack()
  }

  def format(logMessage: LogMessage) = logMessage match {
    case t: FatalMessage =>  s"FATAL/${t.from.name}*${t.qualifier.name} ---> ${t.msg}"
    case t: ErrorMessage =>  s"ERROR/${t.from.name}*${t.qualifier.name} ---> ${t.msg}"
    case t: DebugMessage => s"DEBUG/${t.from.name}*${t.qualifier.name} ---> ${t.msg}"
    case t: WarningMessage => s"WARNING/${t.from.name}*${t.qualifier.name} ---> ${t.msg}"
    case t: InfoMessage => s"INFO/${t.from.name}*${t.qualifier.name} ---> ${t.msg}"
  }
}
