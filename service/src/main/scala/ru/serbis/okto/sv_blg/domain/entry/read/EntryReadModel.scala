package ru.serbis.okto.sv_blg.domain.entry.read

import akka.actor.{ActorRef, Props, Stash, Timers}
import akka.pattern.ask
import akka.persistence.query.EventEnvelope
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import ru.serbis.okto.sv_blg.domain.DomainJsonProtocol
import ru.serbis.okto.sv_blg.domain.entry.write.Entry
import ru.serbis.okto.sv_blg.proxy.system.ActorSystemProxy
import ru.serbis.svc.ddd.ElasticsearchRepository.Commands.{GetTotalCount, Query, QueryDsl, QueryElasticsearch}
import ru.serbis.svc.ddd.ViewBuilder.{DeleteAction, InsertAction, UpdateAction}
import ru.serbis.svc.ddd._
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import spray.json._
import akka.pattern.pipe
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.ReadAction
import ru.serbis.okto.sv_blg.domain.StandardFailures._
import ru.serbis.svc.JsonWritable
import ru.serbis.svc.ddd.ElasticsearchRepository.Responses.{QueryError, TotalCount}

import scala.concurrent.duration._

/** Entry read model */
trait EntryReadModel extends View {
  def indexRoot = "sv_blg"
  def entityType = Entry.EntityType
}

/** Entry view builder */
object EntryViewBuilder {
  val Name = "entry-view-builder"

  case class EntryRM(
    id: String,
    title: Map[String, String] = Map.empty,
    aText: Map[String, String] = Map.empty,
    bText: Map[String, String] = Map.empty,
    timestamp: Long,
    deleted: Boolean = false) extends ReadModelObject

  /** @param rpRef  text resource repository ref */
  def props(rpRef: ActorRef) = Props(new EntryViewBuilder(rpRef))
}

/** Entry projection view bulder */
class  EntryViewBuilder(rpRef: ActorRef) extends ViewBuilder[EntryViewBuilder.EntryRM]
  with EntryReadModel with DomainJsonProtocol {
  import Entry.Events._
  import EntryViewBuilder.EntryRM

  setLogSourceName(s"EntryViewBuilder")
  setLogKeys(Seq("EntryViewBuilder"))

  implicit val logQualifier = LogEntryQualifier("static")

  logger.info("EntryViewBuilder actor was started")

  def projectionId = "entry-view-builder"

  override def repoRef = rpRef

  implicit val rmFormats = entryRmFormat

  override def actionFor(id: String, env: EventEnvelope) = env.event match {
    case Created(fo) =>
      implicit val logQualifier = LogEntryQualifier("Created")

      logger.debug(s"Saving a new Entry entity into the elasticsearch index: '$fo'")
      val rm = EntryRM(fo.id, fo.title, fo.aText, fo.bText, fo.timestamp, fo.deleted)
      InsertAction(id, rm.toJson.compactPrint)

    case Updated(elems) =>
      def fieldToStr(field: Int) = field match {
        case 0 => "title"
        case 1 => "aText"
        case 2 => "bText"
      }
      logger.debug(s"Update Entry with id '$id' values '${elems.foldLeft("")((a, v) => s"$a${fieldToStr(v.field)}->${v.locale}->${v.value} | ").dropRight(3)}'")
      val exps = elems.map(v => s"""${fieldToStr(v.field)}.put("${v.locale}","${v.value}")""")
      UpdateAction(id, exps.toList, Map.empty)

    case Deleted() =>
      logger.debug(s"Delete Entry entity with id '$id' from the elasticsearch index")
      DeleteAction(id)
  }
}

/** Representation of the side of reading the Entry entity. A feature of this presentation is that it is a composite.
  * The structure of the view is selected from the database, and the text fields is taken from the text resource
  * repository */
object EntryView {
  val Name = "Entry-view"

  /** @param rpRef projection repostiry
    * @param trRep text reousrces repository
    * @param systemProxy actor system
    * @param tm test mode flag
    */
  def props(rpRef: ActorRef, trRep: ActorRef, systemProxy: ActorSystemProxy, tm: Boolean = false) = Props(new EntryView(rpRef, trRep, systemProxy, tm))

  object Commands {

    /** Search projection with specified id
      *
      * @param id entity id
      * @param session user session
      */
    case class FindById(id: String, session: Option[String])

    /** Search all existed projections
      *
      * @param session user session
      */
    case class FindAll(session: Option[String])

    /** Return total count of entity's*/
    case object GetCount

    /** Search projections in range in space sorted by timestamp field in desc mode
      *
      * @param from start position
      * @param size cound of entity's after start
      * @param session user session
      */
    case class FindInRange(from: Int, size: Int, session: Option[String])
  }
}

class EntryView(rpRef: ActorRef, trRep: ActorRef, systemProxy: ActorSystemProxy, tm: Boolean) extends EntryReadModel with CommonActor with DomainJsonProtocol with Timers with Stash {
  import EntryView.Commands._
  import context.dispatcher

  setLogSourceName(s"EntryView")
  setLogKeys(Seq("EntryView"))

  implicit val logQualifier = LogEntryQualifier("static")
  override implicit val askTimeout = Timeout(if (tm) 0.5 second else 5 second)

  logger.info("TextResourceView actor was started")

  def receive = {

    /** See description of the message */
    case FindById(id, session) =>
      implicit val logQualifier = LogEntryQualifier("FindById")
      logger.debug(s"Start search request for entity with id '$id'")
      searchReq(session, QueryElasticsearch(s"id:$id&size=1"))


    /** See description of the message */
    case FindAll(session) =>
      implicit val logQualifier = LogEntryQualifier("FindAll")
      logger.debug(s"Start search request for all registered entity")
      searchReq(session, QueryElasticsearch(s"id:*&size=10000"))

    /** See description of the message */
    case FindInRange(from, size, session) =>
      implicit val logQualifier = LogEntryQualifier("FindInRange")

      if (from < 0 || size <= 0) {
        logger.warning("Request does not pass validation by condition 'from < 0 || size <= 0'")
        sender() ! ParamsValidationError("from < 0 or size <= 0")
      } else {
        logger.debug(s"Start search request for entry's in range '$from' with size '$size'")
        searchReq(session, QueryDsl(s"""{"from": $from, "size": $size, "sort": [{"timestamp": "desc"}]}"""))
      }

    /** See description of the message */
    case GetCount =>
      implicit val logQualifier = LogEntryQualifier("GetCount")
      logger.debug(s"Start search request for total registered entity count")
      (rpRef ? GetTotalCount) map {
        case TotalCount(count) =>
          logger.debug(s"Success search request for total registered entity count, returned '$count'")
          FullResult(count.toString)
        case QueryError(reason) =>
          logger.warning(s"Failed search request for total registered entity count by repository error, reason '$reason'")
          ReadSideRepoError(reason)
      } recover {
        case ex: Throwable =>
          logger.warning(s"Failed search request for total registered entity count by repository timeout, reason '${ex.getMessage}'")
          ReadSideRepoTimeout(ex.getMessage)
      } pipeTo sender()
  }

  /** This function used as search query wrapper. The search query consist two operation: Select RM from the read side
    * repository and replace all text fields with real text content, instead of the content id that contain RM in fields
    * initially. If at this operations some goes wrong, error is intercepted and replaced as ServivceFailire result.
    */
  def searchReq(session: Option[String], query: Query) = {
    val orig = sender()
    (rpRef ? query) map { v =>
      val t = v.asInstanceOf[List[EntryRM]]
      logger.debug(s"Received response from read side repository for request '$query' with '$t' RM's. Start texts collecting")
      val fsm = systemProxy.actorOf(TextsCollectorFsm.props(trRep))
      (fsm ? TextsCollectorFsm.Commands.Exec(t , session)) map {
        case TextsCollectorFsm.Responses.CollectResult(rms) =>
          logger.debug(s"Text collecting finished for request '$query' with '$t' RM's. Result returned to the sender")
          orig ! FullResult(rms)
        case TextsCollectorFsm.Responses.CollectError(reason) => orig ! EntityResourceFetchFailure(s"text collector respond with error - $reason")
      }  recover {
        case ex: Throwable =>
          logger.error(s"Error while interaction with texts collector fsm for request '$query' reason '${ex.getMessage}'")
          orig ! TextsCollectorTimeout(ex.getMessage)
      }
    } recover {
      case ex: Throwable =>
        logger.error(s"Error while interaction with read side repository for request '$query' reason '${ex.getMessage}'")
        orig ! ReadSideRepoTimeout(ex.getMessage)
    }
  }
}
