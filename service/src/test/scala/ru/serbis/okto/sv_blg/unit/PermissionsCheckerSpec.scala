package ru.serbis.okto.sv_blg.unit

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.proxy.http.RealHttpProxy
import ru.serbis.okto.sv_blg.proxy.system.TestActorSystemProxy
import ru.serbis.okto.sv_blg.service.{PermissionsChecker, PermissionsCheckerFsm}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.util.{Failure, Success}


class PermissionsCheckerSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "For CheckPing message" must {
    "Create and exec new PermissionsCheckerFsm" in {
      val actorSystemProbe = TestProbe()
      val actorSystemProxy = new TestActorSystemProxy(actorSystemProbe.ref)
      val probe = TestProbe()

      val target = system.actorOf(PermissionsChecker.props("abc", actorSystemProxy, new RealHttpProxy))

      val f: (Option[String], ActorRef) => Unit = (_, _) => {}

      probe.send(target, PermissionsChecker.Commands.CheckPing(Some("aaa"), "bbb", probe.ref, f))

      val props = actorSystemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual PermissionsCheckerFsm.getClass.toString
      val fsm = TestProbe()
      actorSystemProbe.reply(fsm.ref)
      fsm.expectMsg(PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    }
  }

  "withPerms function must process own logic" in {
    val senderProbe = TestProbe()
    val selfProbe = TestProbe()
    val permCheckerProbe = TestProbe()

    val p = Promise[String]

    val f: (Option[String], ActorRef) => Unit = (perms, sender) => {
      p success(perms.get + sender.path.name)
    }

    PermissionsChecker.withPerms(Some("sss"), "abc")(f)(senderProbe.ref, selfProbe.ref, permCheckerProbe.ref)

    permCheckerProbe.expectMsg(PermissionsChecker.Commands.CheckPing(Some("sss"), "abc", senderProbe.ref, f))
    selfProbe.ref.tell(PermissionsChecker.Responses.CheckPong("xxx", senderProbe.ref, f), permCheckerProbe.ref)
    val r = selfProbe.expectMsgType[PermissionsChecker.Responses.CheckPong]
    r.f(Some(r.permissions), r.orig)
    Await.ready(p.future, 3 second) andThen  {
      case Success(v) => v shouldEqual "xxx" + senderProbe.ref.path.name
      case Failure(_) => fail()
    }
  }
}