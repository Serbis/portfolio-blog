package ru.serbis.svc

import akka.actor.ActorSystem
import akka.dispatch.{PriorityGenerator, UnboundedStablePriorityMailbox}
import com.typesafe.config.Config

object MessagesPriority {
  trait Priority0
  trait Priority1
}

class DefaultPriorityMailbox(settings: ActorSystem.Settings, config: Config) extends UnboundedStablePriorityMailbox (
    // Create a new PriorityGenerator, lower prio means more important
    PriorityGenerator {
      case _: MessagesPriority.Priority0  => 0
      case _: MessagesPriority.Priority1  => 2
      case _ => 1
    })