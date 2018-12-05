package ru.serbis.okto.sv_blg.service

import akka.actor.{Actor, ActorRef, Props}
import ru.serbis.okto.sv_blg.proxy.http.HttpProxy
import ru.serbis.okto.sv_blg.proxy.system.ActorSystemProxy
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger

object TextResourceRepository {

  /** @param svResBase sv_res microservice http path base. For example https://okto.su:5101
    * @param systemProxy actor system proxy
    * @param httpProxy http proxy
    */
  def props(svResBase: String, systemProxy: ActorSystemProxy, httpProxy: HttpProxy) = Props(new TextResourceRepository(svResBase, systemProxy, httpProxy))


  object Commands {

    /** Launches the workflow for the CRUD model. Operations are determined by the heirs of the Action class (see the
      * corresponding description). The response to this command is the PacketResult message, in which each action from
      * the original message is reflected as a separate result. For a detailed description of what can be returned as the
      * result of an action, see the description of the PacketResult message.
      *
      * @param actions actions list
      * @param session user session
      */
    case class ExecutePacket(actions: List[Action], session: Option[String])

    /** See descriptions of heirs */
    trait Action { val resource: String }

    /** Create new resource with specified body
      *
      * @param resource resource id
      * @param value initial resource body
      */
    case class CreateAction(resource: String, value: String) extends Action

    /** Read body of the resource with specified id
      *
      * @param resource resource id
      */
    case class ReadAction(resource: String) extends Action

    /** Set new body for the resource with specified id
      *
      * @param resource resource id
      * @param value new resource body
      */
    case class UpdateAction(resource:String, value: String) extends Action

    /** Delete some resource with with specified id
      *
      * @param resource resource id
      */
    case class DeleteAction(resource:String) extends Action
  }

  object Responses {

    /** The final result of the actor. This result is a map, where the Resource ID as a key is one of two values ​​— Left as
      * a marker of a failed operation that contains an error code or Right with a marker of success of the operation with
      * the result inside. The following error codes are possible:
      *
      * - Application error code returned by microservice
      * - 1000 = Request for microservice failed
      * - 1001 = Microservice replied bad json
      * - 1002 = Microservice did not respond to the request in a timely manner.

      * This repository does not guarantee 100% result mapping for the corresponding actions, since a rare situation is
      * possible (error strictification of the request entity) at which the resource id will be replaced with '_', therefore
      * when processing the result from this repository, you should always check the presence of the desired result in the
      * collection
      */
    case class PacketResult(results: Map[String, Either[Int, String]])
  }
}

class TextResourceRepository(svResBase: String, systemProxy: ActorSystemProxy, httpProxy: HttpProxy) extends Actor with StreamLogger {
  import TextResourceRepository.Commands._
  import TextResourceRepository.Responses._

  setLogSourceName(s"TextResourceRepository")
  setLogKeys(Seq("TextResourceRepository"))

  implicit val logQualifier = LogEntryQualifier("static")

  logger.info("TextResourceRepository actor was started")

  override def receive: Receive = {

    /** See the message description*/
    case ExecutePacket(actions, session) =>
      implicit val logQualifier = LogEntryQualifier("ExecutePacket")

      if (actions.nonEmpty) {
        logger.info(s"Initiated text resource repository packet operation...")
        val fsm = systemProxy.actorOf(TextResourceRepositoryFsm.props(svResBase, httpProxy))
        fsm.tell(TextResourceRepositoryFsm.Commands.Exec(actions, session), sender())
      } else {
        logger.info(s"Actions list is empty, return empty resource map")
        sender ! PacketResult(Map.empty)
      }
  }
}
