package ru.serbis.okto.sv_blg.unit.domain.entry

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.domain.entry.read.TextsCollectorFsm
import ru.serbis.okto.sv_blg.service.TextResourceRepository
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.ReadAction
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}


class TextsCollectorFsmSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "Process positive test" in {
    val samples = List(
      EntryRM("1", Map("ru" -> "ru_title_1", "en" -> "en_title_1"), Map("ru" -> "ru_a_1", "en" -> "en_a_1"), Map("ru" -> "ru_b_1", "en" -> "en_b_1"), 0L),
      EntryRM("2", Map("ru" -> "ru_title_2", "en" -> "en_title_2"), Map("en" -> "en_a_2"), Map.empty, 0L),
      EntryRM("3", Map.empty, Map.empty, Map.empty, 0L)
    )
    val trRepo = TestProbe()
    val probe = TestProbe()

    val target = system.actorOf(TextsCollectorFsm.props(trRepo.ref))
    probe.send(target, TextsCollectorFsm.Commands.Exec(samples, Some("sss")))
    val m = trRepo.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
    m.session shouldEqual Some("sss")
    m.actions should contain (ReadAction("ru_title_1"))
    m.actions should contain (ReadAction("en_title_1"))
    m.actions should contain (ReadAction("ru_a_1"))
    m.actions should contain (ReadAction("en_a_1"))
    m.actions should contain (ReadAction("ru_b_1"))
    m.actions should contain (ReadAction("en_b_1"))
    m.actions should contain (ReadAction("ru_title_2"))
    m.actions should contain (ReadAction("en_title_2"))
    m.actions should contain (ReadAction("en_a_2"))
    trRepo.reply(TextResourceRepository.Responses.PacketResult(Map(
      "ru_title_1" -> Right("ru_title_1_text"),
      "en_title_1" -> Right("en_title_1_text"),
      "ru_a_1" -> Right("ru_a_1_text"),
      "en_a_1" -> Right("en_a_1_text"),
      "ru_b_1" -> Right("ru_b_1_text"),
      "en_b_1" -> Right("en_b_1_text"),
      "ru_title_2" -> Right("ru_title_2_text"),
      "en_title_2" -> Right("en_title_2_text"),
      "en_a_2" -> Right("en_a_2_text")
    )))
    probe.expectMsg(TextsCollectorFsm.Responses.CollectResult(List(
      EntryRM("1", Map("ru" -> "ru_title_1_text", "en" -> "en_title_1_text"), Map("ru" -> "ru_a_1_text", "en" -> "en_a_1_text"), Map("ru" -> "ru_b_1_text", "en" -> "en_b_1_text"), 0L),
      EntryRM("2", Map("ru" -> "ru_title_2_text", "en" -> "en_title_2_text"), Map("en" -> "en_a_2_text"), Map.empty, 0L),
      EntryRM("3", Map.empty, Map.empty, Map.empty, 0L)
    )))
  }

  "Return error if repository does not respond with expected timeout" in {
    val samples = List(
      EntryRM("1", Map("ru" -> "ru_title_1", "en" -> "en_title_1"), Map("ru" -> "ru_a_1", "en" -> "en_a_1"), Map("ru" -> "ru_b_1", "en" -> "en_b_1"), 0L),
      EntryRM("2", Map("ru" -> "ru_title_2", "en" -> "en_title_2"), Map("en" -> "en_a_2"), Map.empty, 0L),
      EntryRM("3", Map.empty, Map.empty, Map.empty, 0L)
    )
    val trRepo = TestProbe()
    val probe = TestProbe()

    val target = system.actorOf(TextsCollectorFsm.props(trRepo.ref, tm = true))
    probe.send(target, TextsCollectorFsm.Commands.Exec(samples, Some("sss")))
    trRepo.expectMsgType[TextResourceRepository.Commands.ExecutePacket]
    probe.expectMsg(TextsCollectorFsm.Responses.CollectError("repository timeout"))
  }
}