package ru.serbis.okto.sv_blg.domain.entry.read

import akka.actor.{ActorRef, FSM, Props}
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.service.TextResourceRepository
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.ReadAction
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Responses.PacketResult
import ru.serbis.svc.FsmDefaults.{Data, State}
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger

import scala.concurrent.duration._

/** The actor will inject text into the fields of RM objects */
object TextsCollectorFsm {

  /** @param trRepo text resources repository ref
    * @param tm test mode flag
    */
  def props(trRepo: ActorRef, tm: Boolean = false) = Props(new TextsCollectorFsm(trRepo, tm))

  object States {
    /** see description of the state code */
    case object Idle extends State
    /** see description of the state code */
    case object WaitRepoResponse extends State
  }

  object StatesData {
    import Internals._

    /** n/c */
    case object Uninitialized extends Data

    /** @param mapper mapper object for matching received text resource with appropriate filed in RM object
      */
    case class InWaitRepoResponse(mapper: Map[EntryRM, List[TextNavigator]], orms: List[EntryRM]) extends Data
  }

  object Commands {

    /** Start fsm execution. Accepts a list of RM objects and user session. An RM object from a database contains text
      * resource identifiers instead of text. The task of this actor is to replace these identifiers with the resources
      * themselves. For this, the actor through the repository makes a group of requests to the microservice sv_res, and
      * replaces the target identity in the RM object with the received text. As a result, a list of the same models is
      * returned, only with the injected text in the CollectResult message. Also, the result of the actor's work can be a
      * CollectError message, in case the repository could not send a response.
      *
      * @param rms rm list
      * @param session user session
      */
    case class Exec(rms: List[EntryRM], session: Option[String])
  }

  object Internals {
    case class TextNavigator(field: String, locale: String, resource: String)
  }

  object Responses {

    /** Success result of the actor operation */
    case class CollectResult(rms: List[EntryRM])

    /** Some error occurred during fsm logic processing */
    case class CollectError(reason: String)
  }
}

class TextsCollectorFsm(trRepo: ActorRef, tm: Boolean = false) extends FSM[State, Data] with StreamLogger {
  import TextsCollectorFsm.Commands._
  import TextsCollectorFsm.States._
  import TextsCollectorFsm.StatesData._
  import TextsCollectorFsm.Internals._
  import TextsCollectorFsm.Responses._


  setLogSourceName(s"TextsCollectorFsm*${self.path.name}")
  setLogKeys(Seq("TextsCollectorFsm"))

  implicit val logQualifier = LogEntryQualifier("static")

  /** Original sender */
  var orig = ActorRef.noSender

  startWith(Idle, Uninitialized)

  logger.debug("Start entry text collector...")

  /** Forms ReadActions from input RM objects and send this actions as packet operation to the repository */
  when(Idle, 5 second) {
    case Event(Exec(rms, session), _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_Exec")

      orig = sender()

      val mapper = rms.map { v =>
        val _0 = v.title.map(m => TextNavigator("title", m._1, m._2))
        val _1 = v.aText.map(m => TextNavigator("aText", m._1, m._2))
        val _2 = v.bText.map(m => TextNavigator("bText", m._1, m._2))
        Map(v -> (_0 ++ _1 ++ _2).toList)
      } .foldLeft(Map.empty[EntryRM, List[TextNavigator]])((a, v) => a ++ v)

      val actions = mapper.map { v =>
        v._2.map { m =>
          logger.debug(s"Requested text for '${v._1.id}->${m.field}->${m.locale}->${m.resource}'")
          ReadAction(m.resource)
        }
      } .foldLeft(List.empty[ReadAction])((a, n) => a ++ n)

      trRepo ! TextResourceRepository.Commands.ExecutePacket(actions, session)

      goto(WaitRepoResponse) using InWaitRepoResponse(mapper, rms)

    //NOT TESTABLE
    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("Idle_StateTimeout")
      logger.warning("FSM doest not start with expected timeout")
      stop
  }

  /** Map received texts to the appropriate fields of the RM objects and return result */
  when(WaitRepoResponse, if(tm) 0.5 second else 5 second) {
    case Event(PacketResult(results), InWaitRepoResponse(mapper,orms)) =>
      implicit val logQualifier = LogEntryQualifier("WaitRepoResponse_PacketResult")

      def setField(field: String, locale: String, value: String, rm: EntryRM) = field match {
        case "title" => rm.copy(title = rm.title + (locale -> value))
        case "aText" => rm.copy(aText = rm.aText + (locale -> value))
        case "bText" => rm.copy(bText = rm.bText + (locale -> value))
      }

      val r = orms.map { u =>
        val v = mapper(u)
        v.foldLeft(u)((a, m) => {
          val r = results.get(m.resource)
          if (r.isDefined) {
            val rg = r.get
            if (rg.isRight) {
              logger.debug(s"Received text for '${a.id}->${m.field}->${m.locale}=${rg.right.get}'")
              setField(m.field, m.locale, rg.right.get, a)
            } else {
              logger.warning(s"Set empty text for '${a.id}->${m.field}->${m.locale}' because repository respond with code '${rg.left.get}'")
              setField(m.field, m.locale, "", a)
            }
          } else {
            logger.warning(s"Set empty text for '${a.id}->${m.field}->${m.locale}' because repository does not fetch target resource")
            setField(m.field, m.locale, "", a)
          }
        })
      }

      logger.debug("Entry text collector logic was finished")
      orig ! CollectResult(r)

      stop

    case Event(StateTimeout, _) =>
      implicit val logQualifier = LogEntryQualifier("WaitRepoResponse_StateTimeout")
      logger.warning("Repository resonse does not received with expected timeout")
      orig ! CollectError("repository timeout")
      stop
  }

  initialize()
}

