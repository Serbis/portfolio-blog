package ru.serbis.okto.sv_blg.domain.entry
import java.util.UUID

import akka.actor.{ActorRef, Props}
import akka.pattern.{ask, pipe}
import ru.serbis.okto.sv_blg.domain.StandardFailures
import ru.serbis.okto.sv_blg.domain.entry.read.EntryView
import ru.serbis.okto.sv_blg.domain.entry.write.{Entry, EntryFO}
import ru.serbis.okto.sv_blg.service.PermissionsChecker.Responses.{CheckPong, InternalError}
import ru.serbis.svc.ddd._
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.okto.sv_blg.service.PermissionsChecker.withPerms

import scala.concurrent.ExecutionContext

/** The aggregate of Entry. Implements the level of applied interaction with the structure of the entity.
  * Controlled by access to aggregate commands via sv_sec system */
object EntryAggregate {
  val Name = "EntryAggregate"

  /** @param entryVr entity view actor ref
    * @param trRepo text resource repository ref
    * @param permsChecker permissions checker actor ref
    * @param testMode test mode flag
    */
  def props(entryVr: ActorRef, trRepo: ActorRef, permsChecker: ActorRef, testMode: Boolean = false) =
    Props(new EntryAggregate(entryVr, trRepo, testMode)(permsChecker))

  object Commands {

    /** Create new empty entry. Respond with id of the created entity in FullResult */
    case class Create(session: Option[String])

    /** Search entry with specified id. Return Vector with single rm element or empty in FullResult */
    case class FindById(id: String, session: Option[String])

    /** Search all entry's. Return Vector with finds entity's rm or empty in FullResult */
    case class FindAll(session: Option[String])

    /** Return total count of entry's*/
    case class GetCount(session: Option[String])

    /** Search entry's in range in space sorted by timestamp field in desc mode. . Return Vector with finds entity's rm
      * or empty in FullResult  */
    case class FindInRange(from: Int, size: Int, session: Option[String])

    /** Update entry with specified id */
    case class UpdateById(id: String, locale: String, title: Option[String], aText: Option[String], bText: Option[String], session : Option[String])

    /** Delete entry with specified id. Normally respond with EmptyResult */
    case class DeleteById(id: String, session: Option[String])
  }

  class EntryAggregate(v: ActorRef, trRepo: ActorRef, tm: Boolean)(implicit permsChecker: ActorRef) extends Aggregate[EntryFO, Entry] {
    import EntryAggregate.Commands._

    setLogSourceName(s"EntryAggregate")
    setLogKeys(Seq("EntryAggregate"))

    implicit val logQualifier = LogEntryQualifier("static")
    implicit val futureEc = ExecutionContext.global

    logger.info("EntryAggregate actor was started")

    override val viewRef = v

    override def entityProps(id: String) = Entry.props(id, trRepo)

    override def testMode = tm

    override def receive = {

      //NOT TESTABLE
      /** See the message description */
      case Create(session) =>
        withPermsWrap(session, s"blog:/entry/*", p => p.contains("c")) { (_, orig) =>
          implicit val logQualifier = LogEntryQualifier("CreateTextResource")

          logger.debug("Creating new TextResource entity")
          val id = UUID.randomUUID().toString
          val fo = EntryFO(id = id, timestamp = System.currentTimeMillis())
          forwardCommandToEntity(id, Entry.Commands.Create(fo), orig)
        }


      /** See the message description */
      case FindById(id, session) =>
        withPermsWrap(session, s"blog:/entry/$id", p => p.contains("r")) { (_, orig) =>
          v.ask(EntryView.Commands.FindById(id, session))(timeout = askTimeout, sender = self).pipeTo(orig)
        }

      /** See the message description */
      case FindAll(session) =>
        withPermsWrap(session, s"blog:/entry/*", p => p.contains("r")) { (_, orig) =>
          v.ask(EntryView.Commands.FindAll(session))(timeout = askTimeout, sender = self).pipeTo(orig)
        }

      /** See the message description */
      case FindInRange(from, to, session) =>
        withPermsWrap(session, s"blog:/entry/*", p => p.contains("r")) { (_, orig) =>
          v.ask(EntryView.Commands.FindInRange(from, to, session))(timeout = askTimeout, sender = self).pipeTo(orig)
        }

      /** See the message description */
      case GetCount(session) =>
        withPermsWrap(session, s"blog:/entry/#/count", p => p.contains("r")) { (_, orig) =>
          v.ask(EntryView.Commands.GetCount)(timeout = askTimeout, sender = self).pipeTo(orig)
        }

      /** See the message description */
      case UpdateById(id, locale, title, aText, bText, session) =>
        withPermsWrap(session, s"blog:/entry/$id", p => p.contains("u")) { (_, orig) =>
          forwardCommandToEntity(id, Entry.Commands.Update(locale, title, aText, bText, session), orig)
        }

      /** See the message description */
      case DeleteById(id, session) =>
        withPermsWrap(session, s"blog:/entry/$id", p => p.contains("d")) { (_, orig) =>
          forwardCommandToEntity(id, Entry.Commands.Delete(session), orig)
        }

      case CheckPong(permissions, orig, f) =>
        f(Some(permissions), orig)

      case InternalError(_, orig, f) =>
        f(None, orig)
    }

    def withPermsWrap(session: Option[String], resource: String, p: String => Boolean)(f: (Option[String], ActorRef) => Unit): Unit = {
      withPerms(session, resource) { (perms, orig) =>
        if (perms.isDefined) {
          if (p(perms.get)) {
            f(perms, orig)
          } else {
            orig ! StandardFailures.AccessDenied
          }
        } else {
          orig ! StandardFailures.SecurityError
        }
      } (sender(), self, permsChecker)
    }
  }


}