package ru.serbis.okto.sv_blg.endpoint

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion, RawHeader}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import ru.serbis.okto.sv_blg.endpoint.HttpRoute.Aggregates
import ru.serbis.okto.sv_blg.endpoint.Models._
import ru.serbis.svc.ddd.{EmptyResult, FullResult, Failure => ServiceFailure}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger
import spray.json._
import Headers._
import ru.serbis.okto.sv_blg.domain.{DomainJsonProtocol, StandardFailures}
import ru.serbis.okto.sv_blg.domain.entry.EntryAggregate
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.svc.JsonWritable

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/** n/c */
object HttpRoute {

  /** @param aggregates domain aggregates list
    * @param tm test mode flag */
  def apply(aggregates: Aggregates, tm: Boolean = false): HttpRoute = new HttpRoute(aggregates, tm)

  /** Domain aggregates list
    *
    * @param entryAgr entry domain aggregate */
  case class Aggregates(entryAgr: ActorRef)
}

/** Main http microservice router */
class HttpRoute(ags: Aggregates, tm: Boolean) extends JsonProtocol with StreamLogger {

  setLogSourceName(s"HttpRoute")
  setLogKeys(Seq("HttpRoute"))

  private implicit val logQualifier = LogEntryQualifier("static")

  /** Default actor ask timeout. If some aggregate does not respond with specified timeout, rest return code 500 */
  private implicit val askTimeout = Timeout( if (tm) 0.5 second else 3 second )

  /** Default headers list. This headers will be attached to any response from this route */
  private val defHeaders = List(
    RawHeader("Access-Control-Allow-Origin", "*"),
    RawHeader("Access-Control-Allow-Credentials", "true")
  )

  /** Main EndPoint rest router */
  def routes: Route = {
    implicit val logQualifier = LogEntryQualifier("routes")

    path("entry") {
      post { //  curl --cacert localca.crt -XPOST -H "Content-type: application/json" -H "Session: b1c515fc-d6a2-4b3c-8bc4-7d3ed1bbb629" 'https://localhost:5102/entry' | json_pp
        pathEndOrSingleSlash {
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("entry->POST")
            logger.info(s"Created new entry requested")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.Create(session)) {
              case Success(value: FullResult[String]) =>
                logger.info(s"New entry was created with id '${value.value}'")
                completeWithCode(200, value.value)

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Access error when creating a entry, code '${v.message.code}'")
                completeWithCode(401, v.message.shortText.get)

              case Success(v @ StandardFailures.EntityAlreadyExist) =>
                logger.warning(s"Service error when creating a new entry, code '${v.message.code}'")
                completeWithCode(400, v.message.shortText.get)

              case Success(v: ServiceFailure) =>
                logger.warning(s"Service error when creating a new entry, code '${v.message.code}'")
                completeWithCode(500, v.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when creating a new entry, reason  '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when creating a new entry, reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      }
    } ~
    path("entry" / "total") {
      get { //  curl --cacert localca.crt -XGET -H "Content-type: application/json" -H "Session: b1c515fc-d6a2-4b3c-8bc4-7d3ed1bbb629" 'https://localhost:5102/entry/total' | json_pp
        pathEndOrSingleSlash {
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("entry/total->GET")
            logger.info(s"Requested tital entry's count")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.GetCount(session)) {
              case Success(value: FullResult[String]) =>
                logger.info(s"Return '${value.value}' total count")
                completeWithCode(200, value.value)

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Access error when get total entry's count, code '${v.message.code}")
                completeWithCode(401, v.message.shortText.get)

              case Success(v: ServiceFailure) =>
                logger.warning(s"Service error when get total entry's count, code '${v.message.code}")
                completeWithCode(500, v.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when get total entry's count, reason '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when get total entry's count, reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      }
    } ~
    path("entry" / Segment ) { id =>
      get { //  curl --cacert localca.crt -XGET -H "Content-type: application/json" -H "Session: b1c515fc-d6a2-4b3c-8bc4-7d3ed1bbb629" 'https://localhost:5102/entry/21158e6b-2db1-4a70-88d2-0beae5f014f4' | json_pp
        pathEndOrSingleSlash { //TODO если в id добавить кириллический символ, то ответа со стороны домена не приходит
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("entry/*->GET")
            logger.info(s"Requested to get entry with id '$id'")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.FindById(id, session)) {
              case Success(value: FullResult[List[EntryRM]]) =>
                if (value.value.nonEmpty) {
                  logger.info(s"Entry with '$id' was returned")
                  completeWithCode(200, value.value.head)
                } else {
                  logger.info(s"Entry with '$id' not found")
                  completeWithCode(404, "")
                }

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Access error when get entry with id '$id', code '${v.message.code}")
                completeWithCode(401, v.message.shortText.get)

              case Success(v: ServiceFailure) =>
                logger.warning(s"Service error when get entry with id '$id', code '${v.message.code}")
                completeWithCode(500, v.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when get entry with id '$id', reason '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when get entry with id '$id', reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      } ~
      put { //  curl --cacert localca.crt -XPUT -H "Content-type: application/json" -H "Session: b1c515fc-d6a2-4b3c-8bc4-7d3ed1bbb629" -d '{"locale":"ru","title":"TITLE","aText":"ATEXT","bText":"BTEXT"}' 'https://localhost:5102/entry/21158e6b-2db1-4a70-88d2-0beae5f014f4' | json_pp
        (pathEndOrSingleSlash & entity(as[UpdatedEntry])) { e =>
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("resource/*->PUT")
            logger.info(s"Requested to update entry with id '$id'")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.UpdateById(id, e.locale, e.title, e.aText, e.bText, session)) {
              case Success(EmptyResult) =>
                logger.info(s"Entry with '$id' was successfully updated")
                completeWithCode(200, "")

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Service error when update entry id '$id', code '${v.message.code}")
                completeWithCode(401, v.message.shortText.get)

              case Success(v @ StandardFailures.SecurityError) =>
                logger.warning(s"Service error when update entry with id '$id', code '${v.message.code}")
                completeWithCode(500, v.message.shortText.get)

              case Success(t: ServiceFailure) =>
                logger.warning(s"Service error when update entry with id '$id', code '${t.message.code}")
                completeWithCode(500, t.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when update entry with id '$id', reason '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when update entry with id '$id', reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      } ~
      delete {
        pathEndOrSingleSlash {
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("resource/*->DELETE")
            logger.info(s"Requested to delete entry with id '$id'")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.DeleteById(id, session)) {
              case Success(EmptyResult) =>
                logger.info(s"Entry with id '$id' was successfully deleted")
                completeWithCode(200, "")

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Service error when deleting the entry with id '$id', code '${v.message.code}")
                completeWithCode(401, v.message.shortText.get)

              case Success(v @ StandardFailures.SecurityError) =>
                logger.warning(s"Service error when deleting the entry with id '$id', code '${v.message.code}")
                completeWithCode(500, v.message.shortText.get)

              case Success(t: ServiceFailure) =>
                logger.warning(s"Service error when deleting the entry with id '$id', code '${t.message.code}")
                completeWithCode(500, t.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when deleting the entry with id '$id', reason '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when deleting the entry with id '$id', reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      }
    } ~
    path("entry" / "range" / IntNumber / IntNumber) { (from, size) =>
      get { //  curl --cacert localca.crt -XGET -H "Content-type: application/json" -H "Session: b1c515fc-d6a2-4b3c-8bc4-7d3ed1bbb629" 'https://localhost:5102/entry/range/0/10' | json_pp
        pathEndOrSingleSlash {
          optionalHeaderValue(extractSession) { session =>
            implicit val logQualifier = LogEntryQualifier("entry/range/*/*->GET")
            logger.info(s"Requested to get entry's in range from '$from' with size '$size'")
            onComplete(ags.entryAgr ? EntryAggregate.Commands.FindInRange(from, size, session)) {
              case Success(value: FullResult[List[EntryRM]]) =>
                if (value.value.nonEmpty) {
                  logger.info(s"Return '${value.value.size}' for range from '$from' with size '$size'")
                  completeWithCode(200, value.value)
                } else {
                  logger.info(s"No entry's in range from '$from' with size '$size'")
                  completeWithCode(404, "")
                }

              case Success(v @ StandardFailures.AccessDenied) =>
                logger.warning(s"Access error when get entry's range from '$from' with size '$size', code '${v.message.code}")
                completeWithCode(401, v.message.shortText.get)

              case Success(v: ServiceFailure) =>
                logger.warning(s"Service error when get entry's range from '$from' with size '$size', code '${v.message.code}")
                completeWithCode(500, v.message.shortText.get)

              case Failure(ex) =>
                logger.error(s"Program error when get entry's range from '$from' with size '$size', reason '${ex.getMessage}'")
                completeWithCode(500, "No response from aggregate")

              case m =>
                logger.error(s"Program error when get entry's range from '$from' with size '$size', reason 'Aggregate respond with bad message <$m>'")
                completeWithCode(500, "Wrong aggregate response")
            }
          }
        }
      }
    } ~
    options {
        val respHeaders = List(
          RawHeader("Access-Control-Allow-Headers", "Content-type, Session"),
          RawHeader("Access-Control-Allow-Methods", "POST, PUT, GET, OPTIONS, DELETE"),
          RawHeader("Access-Control-Allow-Origin", "*"),
          RawHeader("Access-Control-Allow-Credentials", "true")
        )
        complete(HttpResponse(headers = respHeaders))
    }
  }

  private def completeWithCode(code: Int, value: Any) = {
    complete(HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, EndpointResult(code, value).toJson.compactPrint), headers = defHeaders))
  }

  private def extractSession: HttpHeader => Option[String] = {
    case SessionHeader(value) => Some(value)
    case _  => None
  }
}