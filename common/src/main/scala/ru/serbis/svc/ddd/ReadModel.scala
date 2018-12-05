package ru.serbis.svc.ddd

import java.util.Date

import akka.actor.ActorRef
import akka.pattern.ask
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import com.datastax.driver.core.utils.UUIDs
import ru.serbis.svc.ddd.ElasticsearchApi.{IndexingResult, UpdateRequest, UpdateScript}
import ru.serbis.svc.ddd.ElasticsearchRepository.Commands.{ClearIndex, CreateEntry, DeleteEntry, UpdateEntry}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import spray.json.JsonFormat

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait ReadModelObject extends AnyRef {
  def id:String
}

object ViewBuilder{
  sealed trait IndexAction
  case class UpdateAction(id: String, expression:List[String], params:Map[String,Any]) extends IndexAction
  case class DeleteAction(id: String) extends IndexAction
  /*object UpdateAction{
    def apply(id:String, expression:String, params:Map[String,Any]):UpdateAction =
      UpdateAction(id, List(expression), params)
  }*/
  case class EnvelopeAndAction(env:EventEnvelope, action:IndexAction)
  case class EnvelopeAndFunction(env:EventEnvelope, f: () => Future[IndexingResult])
  case class InsertAction(id:String, rmJson: String) extends IndexAction
  case class NoAction(id:String) extends IndexAction
  case class DeferredCreate(flow:Flow[EnvelopeAndAction,EnvelopeAndAction,akka.NotUsed]) extends IndexAction
  case class LatestOffsetResult(offset:Option[Long])
}

abstract class ViewBuilder[RM <: ReadModelObject : ClassTag] extends CommonActor {
  import ViewBuilder._
  import akka.pattern.pipe
  import context.dispatcher

  //Set up the persistence query to listen for events for the target entity type
  val journal = PersistenceQuery(context.system).
    readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  val decider: Supervision.Decider = {
    case NonFatal(ex) =>
      implicit val logQualifier = LogEntryQualifier("decider")
      logger.error(s"Got non fatal exception in ViewBuilder flow -> ${ex.getMessage}")
      Supervision.Resume
    case ex  =>
      implicit val logQualifier = LogEntryQualifier("decider")
      logger.error(s"Got fatal exception in ViewBuilder flow, stream will be stopped -> ${ex.getMessage}")
      Supervision.Stop
  }
  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system).
      withSupervisionStrategy(decider)
  )
  implicit val rmFormats:JsonFormat[RM]

  val resumableProjection = ResumableProjection(projectionId, context.system)
  resumableProjection.
    fetchLatestOffset.
    map(LatestOffsetResult.apply).
    pipeTo(self)

  val eventsFlow =
    Flow[EventEnvelope].
      map{ env =>
        val id = env.persistenceId.toLowerCase().drop(entityType.length() + 1)
        EnvelopeAndAction(env, actionFor(id, env))
      }.
      flatMapConcat{
        case ea @ EnvelopeAndAction(env, cr:DeferredCreate) =>
          Source.single(ea).via(cr.flow )

        case ea:EnvelopeAndAction =>
          Source.single(ea).via(Flow[EnvelopeAndAction])
      }.
      collect{
        case EnvelopeAndAction(env, InsertAction(id, rm: String)) =>
          EnvelopeAndFunction(env, () =>  (repoRef ? CreateEntry(id, rm)).mapTo[IndexingResult] /*updateIndex(id, rm, None)*/)

        case EnvelopeAndAction(env, u:UpdateAction) =>
          EnvelopeAndFunction(env, () => updateDocumentField(u.id, env.sequenceNr - 1, u.expression, u.params))

        case EnvelopeAndAction(env, DeleteAction(id)) =>
          EnvelopeAndFunction(env, () => (repoRef ? DeleteEntry(id)).mapTo[IndexingResult] /*updateIndex(id, rm, None)*/)

        case EnvelopeAndAction(env, NoAction(id)) =>
          EnvelopeAndFunction(env, () => updateDocumentField(id, env.sequenceNr - 1, Nil, Map.empty[String,Any]))

      }.
      mapAsync(1) {
        case EnvelopeAndFunction(env, f) => f.apply.map(_ => env)
      }.
      mapAsync(1)(env => {
        //println(env)
        resumableProjection.storeLatestOffset(UUIDs.unixTimestamp(env.offset.asInstanceOf[TimeBasedUUID].value))
      })

  def projectionId:String

  def repoRef: ActorRef

  def entityType: String

  def actionFor(id:String, env:EventEnvelope):IndexAction

  def receive = {
    case LatestOffsetResult(o) =>
      implicit val logQualifier = LogEntryQualifier("receive_LatestOffsetResult")

      val cur = 1514754000000L//System.currentTimeMillis()
      val offset = o.getOrElse(cur)
      if (offset == cur){
        repoRef ! ClearIndex()
      }

      val offsetDate = new Date(offset)

      logger.debug(s"Starting up view builder for entity '$entityType' with offset time of '$offsetDate'")
      val eventsSource = journal.eventsByTag(entityType, journal.timeBasedUUIDFrom(offset)) //timeBasedUUIDFrom(offset)
      eventsSource.
        via(eventsFlow).
        runWith(Sink.ignore)
  }

  def updateDocumentField(id:String, seq:Long, expressions:List[String], params:Map[String,Any]):Future[IndexingResult] = {
    val script = expressions.map(e => s"ctx._source.$e").mkString(";")
    val request = UpdateRequest(UpdateScript(script, params))
    (repoRef ? UpdateEntry(id, request, Some(seq))).mapTo[IndexingResult]
    //updateIndex(id, request, Some(seq))
  }

}
