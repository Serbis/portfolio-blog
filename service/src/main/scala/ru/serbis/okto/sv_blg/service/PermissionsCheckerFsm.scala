package ru.serbis.okto.sv_blg.service

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.http.scaladsl.model._
import ru.serbis.okto.sv_blg.proxy.http.HttpProxy
import ru.serbis.svc.FsmDefaults.{Data, State}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import akka.pattern.pipe
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Failure

/** Produces a request to the microservice sv_sec in order to obtain access rights to a specific resource in ping-pong mode.
  * Upon receipt of the response, returns the obtained rights and the objective function in the CheckPong reply message */
object PermissionsCheckerFsm {
  /** @param svSecBase sv_sec microservice http path base. For example https://okto.su:5100
    * @param http http proxy
    * @param tm test mode flag
    */
  def props(svSecBase: String, http: HttpProxy, tm: Boolean = false) = Props(new PermissionsCheckerFsm(svSecBase, http, tm))

  object States {
    /** see description of the state code */
    case object Idle extends State
    /** see description of the state code */
    case object WaitSvSecResponse extends State
    /** see description of the state code */
    case object WaitEntityStrict extends State
  }

  object StatesData {
    /** n/c */
    case object Uninitialized extends Data
    /** n/c */
    case class InWaitSvSecResponse(f: (Option[String], ActorRef) => Unit) extends Data
    /** n/c */
    case class InWaitEntityStrict(f: (Option[String], ActorRef) => Unit) extends Data
  }

  object Commands {

    /** @param session user session
      * @param resource accessed resource path
      * @param f target function
      */
    case class Exec(session: Option[String], resource: String, remoteSender: ActorRef, f: (Option[String], ActorRef) => Unit)
  }

  object Internals {
    /** Auth endpoint result from sv_sec */
    case class AuthEndpointResult(code: Int, value: String)

    /** Auth data http request body from sv_sec */
    case class AuthData(resource: String, session: String)

    /** Auth http response body from sv_sec */
    case class AuthResult(permissions: String)
  }
}

class PermissionsCheckerFsm(svSecBase: String, http: HttpProxy, tm: Boolean) extends FSM[State, Data] with StreamLogger with SprayJsonSupport with DefaultJsonProtocol {
  import PermissionsCheckerFsm.Commands._
  import PermissionsCheckerFsm.States._
  import PermissionsCheckerFsm.StatesData._
  import PermissionsCheckerFsm.Internals._
  import PermissionsChecker.Responses._


  setLogSourceName(s"ProcessConstructor*${self.path.name}")
  setLogKeys(Seq("ProcessConstructor"))

  implicit val logQualifier = LogEntryQualifier("static")

  /** Json formats for auth operation from sv_sec */
  implicit val authDataFormat = jsonFormat2(AuthData.apply)
  implicit val authResultFormat = jsonFormat1(AuthResult.apply)
  implicit val authEndpointResultFormat = jsonFormat2(AuthEndpointResult.apply)

  /** Original sender */
  var orig = ActorRef.noSender

  /** Sender of the security check initiator */
  var remoteSender = ActorRef.noSender

  startWith(Idle, Uninitialized)

  logger.debug("Start sv_sec permissions check fsm...")

  /** Send auth request to the sv_sec microservice */
  when(Idle, 5 second) {
    case Event(Exec(session, resource, remoteSenderRef, f), _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_Exec")

      orig = sender()
      remoteSender = remoteSenderRef

      val entity = HttpEntity(ContentTypes.`application/json`, AuthData(resource, if (session.isDefined) session.get else "").toJson.compactPrint)
      val request = HttpRequest(uri = s"$svSecBase/auth", method = HttpMethods.POST, entity = entity)
      logger.debug(s"Requested sv_sec permission check for session '$session' and resource '$resource'")
      pipe {
        http.singleRequest(request)
      } to self

      goto(WaitSvSecResponse) using InWaitSvSecResponse(f)

    //NOT TESTABLE
    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_StateTimeout")
      logger.warning("FSM doest not start with expected timeout")
      stop
  }

  /** Receive auth response from sv_sec, trying get strict entity*/
  when(WaitSvSecResponse, if (tm) 0.5 second else 5 second) {
    case Event(resp: HttpResponse, InWaitSvSecResponse(f)) =>
      implicit val logQualifier = LogEntryQualifier("WaitSvSecResponse_HttpResponse")

      logger.debug(s"Requested response from sv_sec, start entity strictification")
      resp.entity.toStrict(10 second)(ActorMaterializer.create(context.system)) pipeTo self

      goto(WaitEntityStrict) using InWaitEntityStrict(f)

    case Event(Status.Failure(e), InWaitSvSecResponse(f)) =>
      logger.error(s"Unable to establish connection with sv_sev. Reason '${e.getMessage}'")
      orig ! InternalError("sv_sec connection error", remoteSender, f)
      stop

    case Event(StateTimeout, InWaitSvSecResponse(f)) =>
      implicit val logQualifier = LogEntryQualifier("WaitSvSecResponse_StateTimeout")
      logger.warning("sv_sec does not respond with expected timeout")
      orig ! InternalError("sv_sec response timeout", remoteSender, f)
      stop
  }

  /** Entity strictification finished, parse json to response object. If this operaion was success, respond to the
    * originator with received permissions, else return InternalError */
  when(WaitEntityStrict, if (tm) 0.5 second else 5 second) {
    case Event(entity: HttpEntity.Strict, InWaitEntityStrict(f)) =>
      implicit val logQualifier = LogEntryQualifier("WaitEntityStrict_HttpEntity")
      logger.debug(s"Entity strictification finished")
      try {
        val au = entity.data.utf8String.parseJson.convertTo[AuthEndpointResult]
        au.code match {
          case 200 =>
            val result = au.value.parseJson.convertTo[AuthResult]
            logger.debug(s"Received permissions '${result.permissions}'")
            orig ! CheckPong(result.permissions, remoteSender, f)

          case 401 =>
            logger.debug(s"Received access denied response")
            orig ! CheckPong("", remoteSender, f)

          case e =>
            logger.error(s"Received some inconsistent code as result of auth operation '$e'")
            orig ! InternalError("sv_sec inconsistent code", remoteSender, f)
        }

      } catch {
        case ex: Exception =>
          logger.error(s"sv_sec return some incorrect json value. Value '${entity.data.utf8String}' exception message '${ex.getMessage}'")
          orig ! InternalError("bad sv_sec response", remoteSender, f)
      }

      stop

    //NOT TESTABLE
    case Event(StateTimeout, InWaitEntityStrict(f)) =>
      implicit val logQualifier = LogEntryQualifier("WaitEntityStrict_StateTimeout")
      orig ! InternalError("strictification timeout", remoteSender, f)
      logger.warning("Entity strictification timeout")
      stop
  }

  initialize()
}
