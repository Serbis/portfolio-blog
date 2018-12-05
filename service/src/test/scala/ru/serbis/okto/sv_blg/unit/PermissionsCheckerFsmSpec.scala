package ru.serbis.okto.sv_blg.unit

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.{Location, RawHeader}
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.proxy.http.{HttpProxy, TestHttpProxy}
import ru.serbis.okto.sv_blg.proxy.system.TestActorSystemProxy
import ru.serbis.okto.sv_blg.service.{PermissionsChecker, PermissionsCheckerFsm}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}

import scala.concurrent.Promise


class PermissionsCheckerFsmSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "Process positive test" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)
    p success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":200,"value":"{\"permissions\":\"xyz\"}"}"""))

    probe.expectMsg(PermissionsChecker.Responses.CheckPong("xyz", probe.ref, f))
  }

  "Return empty permissions if sv_sev respond with code 401" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)
    p success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":401,"value":""}"""))

    probe.expectMsg(PermissionsChecker.Responses.CheckPong("", probe.ref, f))
  }

  "Return InternalError if http return failure" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy, tm = true))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)
    p failure new Exception("err")

    probe.expectMsg(PermissionsChecker.Responses.InternalError("sv_sec connection error", probe.ref, f))
  }

  "Return InternalError if sv_sec does not respond with expected timeout" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy, tm = true))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)

    probe.expectMsg(PermissionsChecker.Responses.InternalError("sv_sec response timeout", probe.ref, f))
  }

  "Return InternalError if sv_sec return bad json" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy, tm = true))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)
    p success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":"200","value":{"permission:"xyz"}}"""))

    probe.expectMsg(PermissionsChecker.Responses.InternalError("bad sv_sec response", probe.ref, f))
  }

  "Return InternalError if sv_sec respond with unexpected code" in {
    val testPath = "https://okto.su:5100"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(PermissionsCheckerFsm.props(testPath, httpProxy, tm = true))

    val f: (Option[String], ActorRef) => Unit = (_, _) => {}

    probe.send(target, PermissionsCheckerFsm.Commands.Exec(Some("aaa"), "bbb", probe.ref, f))
    val request = HttpRequest(uri = s"$testPath/auth", method = HttpMethods.POST, entity = HttpEntity(ContentTypes.`application/json`, """{"resource":"bbb","session":"aaa"}"""))
    httpProbe.expectMsg(TestHttpProxy.Actions.SingleRequest(request))
    val p = Promise[HttpResponse]()
    httpProbe.reply(p.future)
    p success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":800,"value":"{\"permissions\":\"xyz\"}"}"""))

    probe.expectMsg(PermissionsChecker.Responses.InternalError("sv_sec inconsistent code", probe.ref, f))
  }
}