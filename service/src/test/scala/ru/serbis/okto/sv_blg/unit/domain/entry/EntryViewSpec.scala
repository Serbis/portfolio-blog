package ru.serbis.okto.sv_blg.unit.domain.entry

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.domain.StandardFailures._
import ru.serbis.okto.sv_blg.domain.entry.read.{EntryView, TextsCollectorFsm}
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.proxy.system.TestActorSystemProxy
import ru.serbis.svc.ddd.ElasticsearchRepository.Commands.{GetTotalCount, QueryDsl, QueryElasticsearch}
import ru.serbis.svc.ddd.{ElasticsearchRepository, Failure, FullResult}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}


class EntryViewSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  implicit val mater = ActorMaterializer()
  val rm =  EntryRM("", Map.empty, Map.empty, Map.empty, 0L)

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "EntryView actor when processing a message, FindById" should {
    "return the view of the projection with the correct id" in {
      val rid = UUID.randomUUID().toString
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindById(rid, Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:$rid&size=1"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("xxx")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectResult(List(rm1, rm2, rm3)))
      probe.expectMsg(FullResult(List(rm1, rm2, rm3)))
    }

    "return error if texts constructor respond with failure" in {
      val rid = UUID.randomUUID().toString
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindById(rid, Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:$rid&size=1"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("xxx")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectError("err"))
      probe.expectMsg(EntityResourceFetchFailure(s"text collector respond with error - err"))
    }

    "return error if elastic repo does not respond with expected timeout" in {
      val rid = UUID.randomUUID().toString

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindById(rid, Some("xxx")))

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ReadSideRepoTimeout("_").message.code
    }

    "return error if text resource repo does not respond with expected timeout" in {
      val rid = UUID.randomUUID().toString
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindById(rid, Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:$rid&size=1"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual TextsCollectorTimeout("_").message.code
    }
  }

  "EntryView actor when processing a message, FindAll" should {
    "return the view of the projection with the correct id" in {
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindAll(Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:*&size=10000"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("xxx")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectResult(List(rm1, rm2, rm3)))
      probe.expectMsg(FullResult(List(rm1, rm2, rm3)))
    }

    "return error if texts constructor respond with failure" in {
      val rid = UUID.randomUUID().toString
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindAll(Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:*&size=10000"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("xxx")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectError("err"))
      probe.expectMsg(EntityResourceFetchFailure(s"text collector respond with error - err"))
    }

    "return error if elastic repo does not respond with expected timeout" in {
      val rid = UUID.randomUUID().toString

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindAll(Some("xxx")))

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ReadSideRepoTimeout("_").message.code
    }

    "return error if text resource repo does not respond with expected timeout" in {
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindAll(Some("xxx")))
      repProbe.expectMsg(QueryElasticsearch(s"id:*&size=10000"))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual TextsCollectorTimeout("_").message.code
    }
  }

  "EntryView actor when processing a message, FindInRange" should {
    "return the view of the projection with the correct id" in {
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindInRange(20, 10, Some("sss")))
      repProbe.expectMsg(QueryDsl(s"""{"from": 20, "size": 10, "sort": [{"timestamp": "desc"}]}"""))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("sss")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectResult(List(rm1, rm2, rm3)))
      probe.expectMsg(FullResult(List(rm1, rm2, rm3)))
    }

    "return error if texts constructor respond with failure" in {
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindInRange(20, 10, Some("sss")))
      repProbe.expectMsg(QueryDsl(s"""{"from": 20, "size": 10, "sort": [{"timestamp": "desc"}]}"""))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)
      bodyCollectorFsmProbe.expectMsg(TextsCollectorFsm.Commands.Exec(List(rm1, rm2, rm3), Some("sss")))
      bodyCollectorFsmProbe.reply(TextsCollectorFsm.Responses.CollectError("err"))
      probe.expectMsg(EntityResourceFetchFailure(s"text collector respond with error - err"))
    }

    "return error if elastic repo does not respond with expected timeout" in {
      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindAll(Some("sss")))

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ReadSideRepoTimeout("_").message.code
    }

    "return error if text resource repo does not respond with expected timeout" in {
      val rm1 = rm.copy(id = "1")
      val rm2 = rm.copy(id = "2")
      val rm3 = rm.copy(id = "3")

      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.FindInRange(20, 10, Some("sss")))
      repProbe.expectMsg(QueryDsl(s"""{"from": 20, "size": 10, "sort": [{"timestamp": "desc"}]}"""))
      repProbe.reply(List(rm1, rm2, rm3))
      val props = systemProbe.expectMsgType[TestActorSystemProxy.Actions.ActorOf]
      props.props.actorClass() + "$" shouldEqual TextsCollectorFsm.getClass.toString
      systemProbe.reply(bodyCollectorFsmProbe.ref)

      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual TextsCollectorTimeout("_").message.code
    }

    "return error if input params does not pass validation check" in {
      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val bodyCollectorFsmProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy))
      probe.send(viewRef, EntryView.Commands.FindInRange(0, 0, Some("sss")))
      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ParamsValidationError("_").message.code
      probe.send(viewRef, EntryView.Commands.FindInRange(-1, 10, Some("sss")))
      val f2 = probe.expectMsgType[Failure]
      f2.message.code shouldEqual ParamsValidationError("_").message.code
    }
  }

  "EntryView actor when processing a message, GetCount" should {
    "return total count if repository interaction was success" in {
      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.GetCount)
      repProbe.expectMsg(ElasticsearchRepository.Commands.GetTotalCount)
      repProbe.reply(ElasticsearchRepository.Responses.TotalCount(99))
      probe.expectMsg(FullResult("99"))
    }

    "return error if repository return QueryError" in {
      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.GetCount)
      repProbe.expectMsg(ElasticsearchRepository.Commands.GetTotalCount)
      repProbe.reply(ElasticsearchRepository.Responses.QueryError("err"))
      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ReadSideRepoError("_").message.code
    }

    "return error if repository does not respond with timeout" in {
      val probe = TestProbe()
      val trRepo = TestProbe()
      val systemProbe = TestProbe()
      val repProbe = TestProbe()
      val systemProxy = new TestActorSystemProxy(systemProbe.ref)

      val viewRef = system.actorOf(EntryView.props(repProbe.ref, trRepo.ref, systemProxy, tm = true))
      probe.send(viewRef, EntryView.Commands.GetCount)
      val f = probe.expectMsgType[Failure]
      f.message.code shouldEqual ReadSideRepoTimeout("_").message.code
    }
  }
}