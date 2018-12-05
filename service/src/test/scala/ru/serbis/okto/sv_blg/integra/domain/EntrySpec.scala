package ru.serbis.okto.sv_blg.integra.domain

import java.io.File
import java.nio.file.Files
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.domain.StandardFailures
import ru.serbis.okto.sv_blg.domain.entry.write.{Entry, EntryFO}
import ru.serbis.okto.sv_blg.service.TextResourceRepository
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.{CreateAction, DeleteAction, ReadAction, UpdateAction}
import ru.serbis.svc.ddd._
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}
import StandardFailures._
import ru.serbis.okto.sv_blg.domain.entry.write.Entry.Internals.UpdateElem

import scala.concurrent.Await
import scala.concurrent.duration._

object EntrySpec {
  def asConfig = {
    val appConf = new String(Files.readAllBytes(new File(s"dist/sv_blg/application.conf").toPath))
    val serviceConf = new String(Files.readAllBytes(new File(s"dist/sv_blg/service.conf").toPath))
    ConfigFactory.parseString(appConf + "\n" + serviceConf)
  }
}

class EntrySpec extends TestKit(ActorSystem("TestSystem", EntrySpec.asConfig)) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  val fo = EntryFO("", Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, 0L)
  val prefix = "Entry"
  val expTimeout = 60 second
  val journal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  def compareEvents[E](pid: String, expects: Vector[E]): Boolean = {
    val eventsSource = journal.currentEventsByPersistenceId(pid, 0L, Long.MaxValue)
    val events = Await.result(eventsSource.runWith(Sink.collection), expTimeout)
    events.corresponds(expects)((e, o) => e.event == o)
  }

  "When processing a message, Create" should {
    "return id for created entity, persist state and write the body" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe()

      val target = system.actorOf(Entry.props(rid, ActorRef.noSender))

      probe.send(target, Entry.Commands.Create(fo.copy(id = rid)))
      val r = probe.expectMsgType[FullResult[String]](expTimeout)
      r.value shouldEqual rid
      compareEvents(s"$prefix-$rid", Vector(Entry.Events.Created(fo.copy(id = rid)))) shouldEqual true
    }

    "return failure if entity already created" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe()

      val target = system.actorOf(Entry.props(rid, ActorRef.noSender))

      probe.send(target, Entry.Commands.Create(fo.copy(id = rid)))
      probe.expectMsgType[FullResult[String]](20 second)
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid)))
      probe.expectMsg(StandardFailures.EntityAlreadyExist)
    }
  }

  "At initialization" should {
    "fetch all resources from repository" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", None, None, None, Some("sss")))

      //Initialization stage
      val m = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m.actions should contain (ReadAction("ru_title"))
      m.actions should contain (ReadAction("en_title"))
      m.actions should contain (ReadAction("ru_a"))
      m.actions should contain (ReadAction("en_a"))
      m.actions should contain (ReadAction("ru_b"))
      m.actions should contain (ReadAction("en_b"))
      m.session shouldEqual Some("sss")
    }

    "fail initialization if some of the resource was not be loaded by error code" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", None, None, None, Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Left(100),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))

      probe.expectMsg(InitializationFailure("code '100' for resource 'ru_a'"))
    }

    "fail initialization if some of the resource was not be loaded by strict error" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", None, None, None, Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "_" -> Left(100),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))

      probe.expectMsg(InitializationFailure("resource 'ru_a' not fetched"))
    }

    "fail initialization if repository response timeout was reached" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref, tm = true))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", None, None, None, Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      probe.expectMsg(InitializationFailure("repository does not respond"))
    }
  }


  "When processing a message, Update" should {
    "update changed text thorough repository" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m1.actions should contain (UpdateAction("ru_b", "xxx"))

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_b" -> Right("")
      )))

      probe.expectMsg(EmptyResult)
    }

    "return error if some text does not update by app error" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m1.actions should contain (UpdateAction("ru_b", "xxx"))

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_b" -> Left(100)
      )))

      probe.expectMsg(EntryUpdateFailure("code '100' for resource 'ru_b'"))
    }

    "return error if some text does not update by strict error" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m1.actions should contain (UpdateAction("ru_b", "xxx"))

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "_" -> Left(100)
      )))

      probe.expectMsg(EntryUpdateFailure("response strictification error"))
    }

    "return error if repository does not respond" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref, tm = true))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m1.actions should contain (UpdateAction("ru_b", "xxx"))

      probe.expectMsg(EntryUpdateFailure("repository does not respond"))
    }

    "create new text fro non exist locale thorough repository and persist new state" in {
      val rid = UUID.randomUUID().toString

      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val tfo = fo.copy(id = rid, title = title, aText = aText, bText = bText)
      val probe = TestProbe()
      val trRepProbe = TestProbe()

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("de", Some("de_title_text"), Some("de_a_text"), Some("de_b_text"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m2 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m2.actions should contain (CreateAction("0", "de_title_text"))
      m2.actions should contain (CreateAction("1", "de_a_text"))
      m2.actions should contain (CreateAction("2", "de_b_text"))

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "0" -> Right("id1"),
        "1" -> Right("id2"),
        "2" -> Right("id3")
      )))

      probe.expectMsg(EmptyResult)
      compareEvents(s"$prefix-$rid", Vector(Entry.Events.Created(tfo), Entry.Events.Updated(Vector(UpdateElem(0, "de", "id1"), UpdateElem(1, "de", "id2"), UpdateElem(2, "de", "id3"))))) shouldEqual true
    }
  }

  "When processing a message, Delete" should {
    "delete all registered resources thorough the repository" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Delete(Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
      m1.actions should contain (DeleteAction("ru_title"))
      m1.actions should contain (DeleteAction("en_title"))
      m1.actions should contain (DeleteAction("ru_a"))
      m1.actions should contain (DeleteAction("en_a"))
      m1.actions should contain (DeleteAction("ru_b"))
      m1.actions should contain (DeleteAction("en_b"))

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right(""),
        "en_title" -> Right(""),
        "ru_a" -> Right(""),
        "en_a" -> Right(""),
        "ru_b" -> Right(""),
        "en_b" -> Right("")
      )))

      probe.expectMsg(EmptyResult)
    }

    "return error if some reousource does not dekete by app error" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_b" -> Left(100)
      )))

      probe.expectMsg(EntryUpdateFailure("code '100' for resource 'ru_b'"))
    }

    "return error if some resource does not delete by strict error" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "_" -> Left(100)
      )))

      probe.expectMsg(EntryUpdateFailure("response strictification error"))
    }

    "return error if repository does not respond" in {
      val title = Map("ru" -> "ru_title", "en" -> "en_title")
      val aText = Map("ru" -> "ru_a", "en" -> "en_a")
      val bText = Map("ru" -> "ru_b", "en" -> "en_b")
      val probe = TestProbe()
      val trRepProbe = TestProbe()
      val rid = UUID.randomUUID().toString

      //Create entity
      val target = system.actorOf(Entry.props(rid, trRepProbe.ref, tm = true))
      probe.send(target, Entry.Commands.Create(fo.copy(id = rid, title = title, aText = aText, bText = bText)))
      probe.expectMsgType[FullResult[String]](expTimeout)

      probe.send(target, Entry.Commands.Update("ru", Some("ru_title_text"), None, Some("xxx"), Some("sss")))

      //Initialization stage
      trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      trRepProbe.reply(TextResourceRepository.Responses.PacketResult(Map(
        "ru_title" -> Right("ru_title_text"),
        "en_title" -> Right("en_title_text"),
        "ru_a" -> Right("ru_a_text"),
        "en_a" -> Right("en_a_text"),
        "ru_b" -> Right("ru_b_text"),
        "en_b" -> Right("en_b_text")
      )))


      //Write stage
      val m1 = trRepProbe.expectMsgType[TextResourceRepository.Commands.ExecutePacket]

      probe.expectMsg(EntryUpdateFailure("repository does not respond"))
    }
  }
}