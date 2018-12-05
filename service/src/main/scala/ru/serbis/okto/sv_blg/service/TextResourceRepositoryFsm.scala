package ru.serbis.okto.sv_blg.service

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import ru.serbis.okto.sv_blg.endpoint.Headers.SessionHeader
import ru.serbis.okto.sv_blg.proxy.http.HttpProxy
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands._
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Responses.PacketResult
import ru.serbis.okto.sv_blg.service.TextResourceRepositoryFsm.Internals.{RequestResult, Stricter}
import ru.serbis.svc.FsmDefaults.{Data, State}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._


/** This actor is a repository for working with the sv_res microservice in terms of performing CRUD operations with text
  * resources */
object TextResourceRepositoryFsm {

  /** @param svResBase microservice uri, for example https://oktu.su:5001
    * @param http akka http proxy object
    * @param tm test mode flag
    */
  def props(svResBase: String, http: HttpProxy, tm: Boolean = false) = Props(new TextResourceRepositoryFsm(svResBase, http, tm))

  object States {
    /** see description of the state code */
    case object Idle extends State

    /** see description of the state code */
    case object WaitSvResResponses extends State

    /** see description of the state code */
    case object WaitEntityStrict extends State
  }

  object StatesData {

    /** n/c */
    case object Uninitialized extends Data

    /** @param requests collected request results,initial is's empty
      * @param total total result that must be collected
      */
    case class InWaitSvResResponses(requests: List[RequestResult], total: Int) extends Data

    /** @param requests collected struct results,initial is's empty
      * @param total total result that must be collected
      */
    case class InWaitEntityStrict(requests: List[Stricter], total: Int) extends Data
  }

  object Commands {

    /** Launches the workflow for the CRUD model. Operations are determined by the heirs of the Action class (see the
      * corresponding description). The response to this command is the PacketResult message, in which each action from
      * the original message is reflected as a separate result. For a detailed description of what can be returned as the
      * result of an action, see the description of the PacketResult message.
      *
      * @param actions actions list
      * @param session user session
      */
    case class Exec(actions: List[Action], session: Option[String])
  }

  object Internals {
    trait RequestResult { val resource: String }
    case class RequestResponse(resource: String, httpResponse: HttpResponse) extends RequestResult
    case class RequestFailure(resource: String, ex: Throwable) extends RequestResult
    case class RequestStub(resource: String) extends RequestResult

    trait Stricter
    case class StrictEntity(resource: String, entity: HttpEntity.Strict) extends Stricter
    case class FailedRequest(resource: String, ex: Throwable) extends Stricter
    case class FailedStrict(resource: String, ex: Throwable) extends Stricter
    case class LostResponse(resource: String) extends Stricter

    case class ResEndpointResult(code: Int, value: String)
    case class ResTextResource(body: String)
  }
}

class TextResourceRepositoryFsm(svResBase: String, http: HttpProxy, tm: Boolean) extends FSM[State, Data] with StreamLogger with SprayJsonSupport with DefaultJsonProtocol {
  import TextResourceRepositoryFsm.Commands._
  import TextResourceRepositoryFsm.Internals._
  import TextResourceRepositoryFsm.States._
  import TextResourceRepositoryFsm.StatesData._

  setLogSourceName(s"TextResourceRepositoryFsm*${self.path.name}")
  setLogKeys(Seq("TextResourceRepositoryFsm"))

  implicit val logQualifier = LogEntryQualifier("static")

  /** Json formats for auth operation from sv_res */
  implicit val resEndpointResultFormat = jsonFormat2(ResEndpointResult.apply)
  implicit val resTextResource = jsonFormat1(ResTextResource.apply)


  /** Original sender */
  var orig = ActorRef.noSender

  var actionList: List[Action] = List.empty

  startWith(Idle, Uninitialized)

  logger.debug("Start text resource repository interactor...")

  when(Idle, 5 second) {
    case Event(Exec(actions, session), _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_Exec")

      orig = sender()
      actionList = actions

      actions foreach { v =>
        val request = v match {
          case CreateAction(resource, value) =>
            log.debug(s"Action 'create', value size '${value.length}', marker '$resource', session '${session.getOrElse("")}'")
            val entity =  HttpEntity(ContentTypes.`application/json`, ResTextResource(value).toJson.compactPrint)
            HttpRequest(uri = s"$svResBase/resource/text", method = HttpMethods.POST, entity = entity).withHeaders(SessionHeader(session.getOrElse("")))
          case ReadAction(resource) =>
            log.debug(s"Action 'read', resource '$resource', session '${session.getOrElse("")}'")
            val x = HttpRequest(uri = s"$svResBase/resource/text/$resource/body", method = HttpMethods.GET).withHeaders(SessionHeader(session.getOrElse("")))
            println(x)
            x
          case UpdateAction(resource, value) =>
            val entity =  HttpEntity(ContentTypes.`text/plain(UTF-8)`, value)
            log.debug(s"Action 'update', value size '${value.length}', resource '$resource', session '${session.getOrElse("")}'")
            HttpRequest(uri = s"$svResBase/resource/text/$resource/body", method = HttpMethods.PUT, entity = entity).withHeaders(SessionHeader(session.getOrElse("")))
          case DeleteAction(resource) =>
            log.debug(s"Action 'delete', resource '$resource', session '${session.getOrElse("")}'")
            HttpRequest(uri = s"$svResBase/resource/text/$resource", method = HttpMethods.DELETE).withHeaders(SessionHeader(session.getOrElse("")))
        }
        pipe {
          http.singleRequest(request).map(r => RequestResponse(v.resource, r)).recoverWith {
            case e: Throwable => Future { RequestFailure(v.resource, e) }
          }
        } to self
      }

      goto(WaitSvResResponses) using InWaitSvResResponses(List.empty, actions.length)

    //NOT TESTABLE
    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_StateTimeout")
      logger.warning("FSM doest not start with expected timeout")
      stop
  }

  when(WaitSvResResponses, if (tm) 0.5 second else 5 second) {
    case Event(r: RequestResult, d @ InWaitSvResResponses(requests, total)) =>
      val rq = r +: requests
      if (rq.length == total) {
        val rs = collectReqResults(rq)
        goto(WaitEntityStrict) using InWaitEntityStrict(rs, total)
      } else {
        stay using d.copy(requests = rq)
      }

    case Event(StateTimeout, d @ InWaitSvResResponses(requests, total)) =>
      val a = requests.map(v => RequestStub(v.resource))
      val b = actionList.map(v => RequestStub(v.resource))
      val diff = b.diff(a)
      val rq = requests ++ diff
      val rs = collectReqResults(rq)

      println(actionList)
      goto(WaitEntityStrict) using InWaitEntityStrict(rs, total)
  }

  when(WaitEntityStrict, if (tm) 0.5 second else 5 second) {
    case Event(r: StrictEntity, d @ InWaitEntityStrict(requests, total)) =>
      val rq = r +: requests
      if (rq.length == total) {
        val result = finishResult(rq)
        orig ! PacketResult(result)
        stop
      } else {
        stay using d.copy(requests = rq)
      }

    //NOT TESTABLE
    case Event(Status.Failure(ex), d @ InWaitEntityStrict(requests, total)) =>
      val rq = FailedStrict("_", ex) +: requests
      if (rq.length == total) {
        val result = finishResult(rq)
        orig ! PacketResult(result)
        stop
      } else {
        stay using d.copy(requests = rq)
      }

    //NOT TESTABLE
    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("WaitEntityStrict_StateTimeout")
      logger.warning("Strictification timeout")
      stop
  }

  def collectReqResults(rq: List[RequestResult]) = {
    rq filter {
      case t: RequestResponse =>
        pipe {
          t.httpResponse.entity.toStrict(5 second)(ActorMaterializer.create(context.system)).map(r => StrictEntity(t.resource, r)).recoverWith {
            case e: Throwable => Future { FailedRequest(t.resource, e) } //NOT TESTABLE
          }
        } to self
        false

      case _: RequestFailure => true
      case _: RequestStub => true
    } map {
      case RequestFailure(resource, ex) => FailedRequest(resource, ex)
      case RequestStub(resource) => LostResponse(resource)

    }
  }

  def finishResult(rq: List[Stricter]) = {
    implicit val logQualifier = LogEntryQualifier("WaitEntityStrict_finishResult")

    rq map {
      case v: StrictEntity =>
        try {
          val resResult = v.entity.data.utf8String.parseJson.convertTo[ResEndpointResult]
          if (resResult.code == 200) {
            logger.debug(s"Success operation for resource '${v.resource}', content size '${resResult.value.length}'")
            v.resource -> Right(resResult.value)
          } else {
            logger.info(s"Failed operation for resource '${v.resource}', sv_res return app error code '${resResult.code}'")
            v.resource -> Left(resResult.code)
          }
        } catch {
          case ex: Throwable =>
            logger.error(s"Failed operation for resource '${v.resource}', sv_res return bad json '${v.entity.data.utf8String}', error '${ex.getMessage}'")
            v.resource -> Left(1001)
        }
      case FailedRequest(resource, ex) =>
        logger.error(s"Failed operation for resource '$resource', request to sv_res was fail because '${ex.getMessage}'")
        resource -> Left(1000)
      case LostResponse(resource) =>
        logger.error(s"Failed operation for resource '$resource', request to sv_res was lost by response timeout")
        resource -> Left(1002)
    } toMap
  }

  initialize()
}

//TODO было бы неплохо описать как оно все таки работает
