package ru.serbis.okto.sv_blg.unit

import akka.actor.{ActorRef, ActorSystem, Status}
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.proxy.http.TestHttpProxy
import ru.serbis.okto.sv_blg.service.{PermissionsChecker, PermissionsCheckerFsm, TextResourceRepository, TextResourceRepositoryFsm}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.okto.sv_blg.endpoint.Headers._
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands._
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}

import scala.concurrent.Promise


class TextResourceRepositoryFsmSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "Process positive test" in {
    val testPath = "https://okto.su:5101"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(TextResourceRepositoryFsm.props(testPath, httpProxy))

    probe.send(target, TextResourceRepositoryFsm.Commands.Exec(
      List(
        CreateAction("a", "A"),
        ReadAction("b"),
        UpdateAction("c", "C"),
        DeleteAction("d"),
        ReadAction("e")
      ), Some("sss")))

    val rA = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    rA.request.method shouldEqual HttpMethods.POST
    rA.request.uri shouldEqual Uri(s"$testPath/resource/text")
    rA.request.entity shouldEqual HttpEntity(ContentTypes.`application/json`, """{"body":"A"}""")
    val pA = Promise[HttpResponse]()
    httpProbe.reply(pA.future)

    val rB = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    rB.request.method shouldEqual HttpMethods.GET
    rB.request.uri shouldEqual Uri(s"$testPath/resource/text/b/body")
    val pB = Promise[HttpResponse]()
    httpProbe.reply(pB.future)

    val rC = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    rC.request.method shouldEqual HttpMethods.PUT
    rC.request.uri shouldEqual Uri(s"$testPath/resource/text/c/body")
    rC.request.entity shouldEqual HttpEntity(ContentTypes.`text/plain(UTF-8)`, "C")
    val pC = Promise[HttpResponse]()
    httpProbe.reply(pC.future)

    val rD = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    rD.request.method shouldEqual HttpMethods.DELETE
    rD.request.uri shouldEqual Uri(s"$testPath/resource/text/d")
    val pD = Promise[HttpResponse]()
    httpProbe.reply(pD.future)

    val rE = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    rE.request.method shouldEqual HttpMethods.GET
    rE.request.uri shouldEqual Uri(s"$testPath/resource/text/e/body")
    val pE = Promise[HttpResponse]()
    httpProbe.reply(pE.future)


    pE success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":200,"value":"E"}"""))
    pC success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":200,"value:"C"}"""))
    pA success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":200,"value":"A"}"""))
    pD failure new Exception("Some err")
    pB success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":401,"value":"Error text"}"""))

    val r = probe.expectMsgType[TextResourceRepository.Responses.PacketResult]
    r.results("a") shouldEqual Right("A")
    r.results("b") shouldEqual Left(401)
    r.results("c") shouldEqual Left(1001)
    r.results("d") shouldEqual Left(1000)
    r.results("e") shouldEqual Right("E")
  }

  "Return fragmentary result if sv_res response timeout was reached" in {
    val testPath = "https://okto.su:5101"
    val httpProbe = TestProbe()
    val httpProxy = new TestHttpProxy(httpProbe.ref)
    val probe = TestProbe()

    val target = system.actorOf(TextResourceRepositoryFsm.props(testPath, httpProxy, tm = true))

    probe.send(target, TextResourceRepositoryFsm.Commands.Exec(
      List(
        CreateAction("a", "A"),
        ReadAction("b"),
        UpdateAction("c", "C"),
        DeleteAction("d"),
        ReadAction("e")
      ), Some("sss")))

    val rA = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    val pA = Promise[HttpResponse]()
    httpProbe.reply(pA.future)

    val rB = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    val pB = Promise[HttpResponse]()
    httpProbe.reply(pB.future)

    val rC = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    val pC = Promise[HttpResponse]()
    httpProbe.reply(pC.future)

    val rD = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    val pD = Promise[HttpResponse]()
    httpProbe.reply(pD.future)

    val rE = httpProbe.expectMsgType[TestHttpProxy.Actions.SingleRequest]
    val pE = Promise[HttpResponse]()
    httpProbe.reply(pE.future)

    pA success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":200,"value":"A"}"""))
    pB success HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, """{"code":401,"value":"Error text"}"""))

    val r = probe.expectMsgType[TextResourceRepository.Responses.PacketResult]
    r.results("a") shouldEqual Right("A")
    r.results("b") shouldEqual Left(401)
    r.results("c") shouldEqual Left(1002)
    r.results("d") shouldEqual Left(1002)
    r.results("e") shouldEqual Left(1002)
  }

}