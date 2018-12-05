package ru.serbis.okto.sv_blg.unit

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.proxy.http.RealHttpProxy
import ru.serbis.okto.sv_blg.proxy.system.TestActorSystemProxy
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.ReadAction
import ru.serbis.okto.sv_blg.service.{TextResourceRepository, TextResourceRepositoryFsm}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}


class TextResourceRepositorySpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "For ExecutePacket message" must {
    "Create and exec new TextResourceRepositoryFsm" in {
      val actorSystemProbe = TestProbe()
      val actorSystemProxy = new TestActorSystemProxy(actorSystemProbe.ref)
      val probe = TestProbe()

      val target = system.actorOf(TextResourceRepository.props("abc", actorSystemProxy, new RealHttpProxy))

      probe.send(target, TextResourceRepository.Commands.ExecutePacket(List(ReadAction("xxx")), Some("aaa")))

      val props = actorSystemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextResourceRepositoryFsm.getClass.toString
      val fsm = TestProbe()
      actorSystemProbe.reply(fsm.ref)
      fsm.expectMsg(TextResourceRepositoryFsm.Commands.Exec(List(ReadAction("xxx")), Some("aaa")))
    }
  }
}