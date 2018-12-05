package ru.serbis.svc.ddd

import akka.actor.{Actor, ActorLogging}
import akka.util.Timeout
import ru.serbis.svc.logger.StreamLogger

import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Base actor definition for other actors in the bookstore app to extend from
  */
trait CommonActor extends Actor with StreamLogger {
  import akka.pattern.pipe
  import context.dispatcher

  implicit val askTimeout = Timeout(5 second)
  //PF to be used with the .recover combinator to convert an exception on a failed Future into a
  //Failure ServiceResult
  private val toFailure:PartialFunction[Throwable, ServiceResult[Nothing]] = {
    case ex => Failure(FailureType.Service, ServiceResult.UnexpectedFailure, Some(ex))
  }

  /**
    * Pipes the response from a request to a service actor back to the sender, first
    * converting to a ServiceResult per the contract of communicating with a bookstore service
    * @param f The Future to map the result from into a ServiceResult
    */
  def pipeResponse[T](f:Future[T]):Unit =
    f.
      map{
        case o:Option[_] => ServiceResult.fromOption(o)
        case f:Failure => f
        case other => FullResult(other)
      }.
      recover(toFailure).
      pipeTo(sender())
}