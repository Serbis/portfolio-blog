package ru.serbis.okto.sv_blg.domain.entry.write

import java.lang.IllegalArgumentException

import akka.actor.{ActorRef, Props, Stash, Status, Timers}
import akka.persistence.RecoveryCompleted
import akka.util.{ByteString, Timeout}
import ru.serbis.okto.sv_blg.domain.StandardFailures
import ru.serbis.svc.ddd._
import ru.serbis.okto.sv_blg.proto.entry.{entry => proto}
import ru.serbis.okto.sv_blg.proto.union.{union => protoUnion}
import ru.serbis.okto.sv_blg.service.TextResourceRepository
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands._
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Responses.PacketResult
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import akka.pattern.{ask, pipe}
import StandardFailures._
import ru.serbis.okto.sv_blg.domain.entry.write.Entry.Internals.UpdateElem

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try


/** Entity field object constructor */
object EntryFO {
  def empty = EntryFO("", Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, 0L)
}

/** Entity field object */
case class EntryFO (
  id: String,
  title: Map[String, String] = Map.empty,
  aText: Map[String, String] = Map.empty,
  bText: Map[String, String] = Map.empty,
  titleMat: Map[String, String] = Map.empty,
  aTextMat: Map[String, String] = Map.empty,
  bTextMat: Map[String, String] = Map.empty,
  timestamp: Long,
  initialized: Boolean = false,
  deleted: Boolean = false) extends EntityFieldsObject[String, EntryFO] {
  def assignId(id: String) = this.copy(id = id)
  def markDeleted = this.copy(deleted = true)
}

object Entry {

  val EntityType = "Entry"

  /** @param id entity id
    * @param trRep text resource repository ref
    * @param tm test mode flag
    */
  def props(id: String, trRep: ActorRef, tm: Boolean = false) = Props(new Entry(id, trRep, tm))

  object Commands {

    case class Create(fo: EntryFO)

    case class Update(locale: String, title: Option[String], aText: Option[String], bText: Option[String], session: Option[String])

    case class Delete(session: Option[String])
  }

  object Internals {
    case object RepositoryTimeout
    case class UpdateElem(field: Int, locale: String, value: String)
  }

  object Events {

    trait LocalEvent extends EntityEvent {
      def entityType = EntityType
    }

    case class Created(fo: EntryFO) extends LocalEvent {
      override def toDatamodel = proto.Created(Some(proto.Entry(
        fo.id, fo.deleted, fo.title, fo.bText, fo.aText, fo.timestamp
      )))
    }

    object Created extends DatamodelReader {
      override def fromDatamodel = {
        case e: proto.Created =>
          val t = e.entry.get
          Created(EntryFO(t.id, t.title, t.aText, t.bText, Map.empty, Map.empty, Map.empty, t.timestamp, t.deleted))
      }
    }

    case class Updated(elems: Seq[UpdateElem]) extends LocalEvent {
      override def toDatamodel = proto.Updated(elems.map(v => proto.UpdateElem(v.field, v.locale, v.value)))
    }

    object Updated extends DatamodelReader {
      override def fromDatamodel = {
        case e: proto.Updated =>
          Updated(e.elems.map(v => UpdateElem(v.field, v.locale, v.value)))
      }
    }

    case class Deleted() extends LocalEvent {
      override def toDatamodel = protoUnion.Deleted()
    }

    case object Deleted extends DatamodelReader {
      override def fromDatamodel = {
        case e: protoUnion.Deleted => Deleted()
      }
    }
  }
}

class Entry(id: String, trRep: ActorRef, tm: Boolean) extends PersistentEntity[EntryFO](id) with Timers with Stash {
  import Entry.Commands._
  import Entry.Events._
  import Entry.Internals._

  setLogSourceName(s"Entry*$id")
  setLogKeys(Seq("Entry"))

  implicit val logQualifier = LogEntryQualifier("static")
  implicit val askTimeout = Timeout(if (tm) 0.5 second else 5 second)

  logger.debug("Entity was started")

  override def initialState = EntryFO.empty

  override def snapshotAfterCount = Some(5)

  override def newDeleteEvent = Some(Deleted())

  override def isCreateMessage(cmd: Any) = cmd match {
    case c: Create => true
    case _ => false
  }

  /** Initialization receive. At this stage, actor wait response from the repository with full texts of each entry
    * element. If all texts was fetched, it execute original function, from init was starts. If some error was
    * occurred, respond with error to the originator */
  def initRecv(f: ActorRef => Unit, orig: ActorRef): Receive = {

    case PacketResult(results) =>
      implicit val logQualifier = LogEntryQualifier("initRecv_PacketResult")

      def extractResult(v: (String, String)) = {
        val r = results.find(p => {
          if (p._2.isLeft)
            if (p._1 != "_")
              throw new IllegalStateException(s"code '${p._2.left.get.toString}' for resource '${p._1}'")
          p._1 == v._2
        })

        if (r.isEmpty)
          throw new IllegalArgumentException(s"resource '${v._2}' not fetched")
        v._1 -> r.get._2.right.get
      }

      val r = Try {
        val nTitle = state.title.map(extractResult)
        val nAText = state.aText.map(extractResult)
        val nBText = state.bText.map(extractResult)
        state = state.copy(titleMat = nTitle, aTextMat = nAText, bTextMat = nBText)
      }

      if (r.isSuccess) {
        logger.debug("Entity was successfully initialized")
        context.unbecome()
        f(orig)
      } else {
        val msg = r.failed.get.getMessage
        logger.error(s"Entity initialization was failed be application error '$msg'")
        orig ! InitializationFailure(msg)
        context.unbecome()
        unstashAll()
      }

    case Status.Failure(ex) =>
      implicit val logQualifier = LogEntryQualifier("initRecv_Failure")
      logger.error(s"Entity initialization was failed be repository timeout")
      context.unbecome()
      unstashAll()
      orig ! InitializationFailure("repository does not respond")

    case _ => stash()
  }

  def updateRecv(orig: ActorRef, persists: List[(String, String)]): Receive = {

    case PacketResult(results) =>
      implicit val logQualifier = LogEntryQualifier("updateRecv_PacketResult")

      val r = Try {
        results.foreach(v => {
          if (v._1 != "_") {
            if (v._2.isLeft)
              throw new IllegalStateException(s"code '${v._2.left.get.toString}' for resource '${v._1}'")
          } else {
            throw new IllegalStateException(s"response strictification error")
          }
        })
      }

      if (r.isSuccess) {
        if (persists.isEmpty) {
          logger.debug("Entity was successfully updated")
          context.unbecome()
          unstashAll()
          orig ! EmptyResult
        } else {
          logger.debug("Persist new resources links")
          val event = Updated(persists.map(v => {
            val re = results.find(p => p._1 == v._1).get
            UpdateElem(re._1.toInt, v._2, re._2.right.get)
          }))
          persist(event)(handleEventAndRespond(orig))
        }
      } else {
        val msg = r.failed.get.getMessage
        logger.error(s"Entity updating was failed be application error '$msg'")
        unstashAll()
        context.unbecome()
        orig ! EntryUpdateFailure(msg)
      }

    case Status.Failure(_) =>
      implicit val logQualifier = LogEntryQualifier("updateRecv_Failure")
      logger.error(s"Entity updating was failed be repository timeout")
      unstashAll()
      context.unbecome()
      orig ! EntryUpdateFailure("repository does not respond")

    case _ =>
      stash()
  }

  def deleteRecv(orig: ActorRef): Receive = {

    case PacketResult(results) =>
      implicit val logQualifier = LogEntryQualifier("deleteRecv_PacketResult")

      val r = Try {
        results.foreach(v => {
          if (v._1 != "_") {
            if (v._2.isLeft)
              throw new IllegalStateException(s"code '${v._2.left.get.toString}' for resource '${v._1}'")
          } else {
            throw new IllegalStateException(s"response strictification error")
          }
        })
      }

      if (r.isSuccess) {
        logger.debug("Persist delete event")
        persist(Deleted())(handleEventAndRespond(orig))
      } else {
        val msg = r.failed.get.getMessage
        logger.error(s"Entity deleting was failed be application error '$msg'")
        unstashAll()
        context.unbecome()
        orig ! EntryDeleteFailure(msg)
      }

    case Status.Failure(_) =>
      implicit val logQualifier = LogEntryQualifier("deleteRecv_Failure")
      logger.error(s"Entity deleting was failed be repository timeout")
      unstashAll()
      context.unbecome()
      orig ! EntryUpdateFailure("repository does not respond")

    case _ =>
      stash()
  }


  override def additionalCommandHandling = {

    /** See the message description */
    case Create(fo) =>
      if (state != initialState) {
        sender() ! StandardFailures.EntityAlreadyExist
      } else {
        persist(Created(fo))(handleEventAndRespond())
      }

    /** See the message description */
    case Update(locale, title, aText, bText, session) =>
      withInitialization(session) { orig =>
        def createActions(e: Map[String, String], v: Option[String], l: String, t: Map[String, String], m: Int) = {
          if (v.isDefined) {
            if (e.contains(l)) {
              val tl = t(l)
              if (tl != v.get)
                List(UpdateAction(e(l), v.get))
              else
                List.empty
            } else {
              List(CreateAction(m.toString, v.get))
            }
          } else {
            List.empty
          }
        }

        val titleActions = createActions(state.title, title, locale, state.titleMat, 0)
        val aTextActions = createActions(state.aText, aText, locale, state.aTextMat, 1)
        val bTextActions = createActions(state.bText, bText, locale, state.bTextMat, 2)
        val actions = titleActions ++ aTextActions ++ bTextActions

        val persists = actions.filter(p => p.isInstanceOf[CreateAction]).map(v => (v.resource, locale))

        if (actions.nonEmpty) {
          logger.debug(s"Requested update operation for '${actions.size}' actions, '${persists.size}' is persistent")
          context.become(updateRecv(orig, persists))
          pipe (trRep ? TextResourceRepository.Commands.ExecutePacket(actions, session)) to self
        } else {
          logger.debug(s"Requested update operation but nothing to change")
          orig ! EmptyResult
        }
      }

    /** See the message description */
    case Delete(session) =>
      //withInitialization(session) { orig =>
        def createActions(field: Map[String, String]) =  if (field.nonEmpty) field.map(v => DeleteAction(v._2)).toList else List.empty

        val titleActions = createActions(state.title)
        val aTextActions = createActions(state.aText)
        val bTextActions = createActions(state.bText)
        val actions = titleActions ++ aTextActions ++ bTextActions

        logger.debug(s"Requested delete operation")
        context.become(deleteRecv(sender()))
        pipe (trRep ? TextResourceRepository.Commands.ExecutePacket(actions, session)) to self
      //persist(Deleted())(handleEventAndRespond(sender()))
      //}

    //ATTENTION!!! IF MESSAGE USE withInitialization IT MUST CALL unstashAll AFTER IT'S WORK COMPLETE
  }

  override def handleEvent(event: EntityEvent): ServiceResult[Any] = event match {
    case Created(fo) =>
      implicit val logQualifier = LogEntryQualifier("Created")

      logger.debug(s"Entity was persisted with state '$fo'")
      state = fo
      FullResult(fo.id)

    case Updated(elems) =>
      def extractUpdate(field: Int, target: Map[String, String]) =
        target ++ elems.filter(p => p.field == field).map(v => v.locale -> v.value)

      state = state.copy(
        title = extractUpdate(0, state.title),
        aText = extractUpdate(1, state.aTextMat),
        bText = extractUpdate(2, state.bTextMat),
        initialized = false
      )

      logger.debug("Entity was successfully updated")

      unstashAll()
      context.unbecome()

      EmptyResult

    case Deleted() =>
      logger.debug(s"Entity marked for deletion")
      deleteMessages(Long.MaxValue)
      state = state.copy(deleted = true)
      EmptyResult
  }


  /**
    * Optional custom recovery message handling that a subclass can provide if necessary
    */
  override def customRecover = {
    case RecoveryCompleted =>
      println(state)
  }

  override def customEventRespond(result: ServiceResult[Any], orig: ActorRef): Unit = orig ! result

  def withInitialization(session: Option[String])(f: ActorRef => Unit): Unit = {
    if (!state.initialized) {
      logger.debug("Entity does not initialized. Start initialization...")
      val titles = state.title.map(v => ReadAction(v._2))
      val aTexts = state.aText.map(v => ReadAction(v._2))
      val bTexts = state.bText.map(v => ReadAction(v._2))
      val total = titles ++ aTexts ++ bTexts
      if (total.nonEmpty) {
        logger.debug(s"Detected '${total.size}' resources for initialisation '${total.foldLeft("")((a, v) => s"$a$v/").dropRight(1)}'")
        context.become(initRecv(f, sender()))
        pipe(trRep ? TextResourceRepository.Commands.ExecutePacket(total.toList, session)) to self
      } else {
        logger.debug("Entity data is empty, nothing to initialize")
        f(sender())
      }
    } else {
      f(sender())
    }
  }
}
