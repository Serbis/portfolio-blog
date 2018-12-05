package ru.serbis.okto.sv_blg.unit.domain.entry

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import ru.serbis.okto.sv_blg.domain.StandardFailures
import ru.serbis.okto.sv_blg.domain.entry.EntryAggregate
import ru.serbis.okto.sv_blg.domain.entry.read.EntryView
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.domain.entry.write.{Entry, EntryFO}
import ru.serbis.okto.sv_blg.service.PermissionsChecker
import ru.serbis.svc.ddd._
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}

class EntryAggregateSpec extends TestKit(ActorSystem("TestSystem")) with ImplicitSender with WordSpecLike with Matchers with StreamLogger with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  implicit val mater = ActorMaterializer()

  val tFo = EntryFO(id = "", timestamp = 0L)
  val tRm = EntryRM(id = "", timestamp = 0L)

  class EntityProxy(tpRef: ActorRef) extends Actor {
    override def receive = {
      case m =>
        tpRef.forward(m)
    }
  }

  "When processing a message, FindById" should {
    "return list with the desired entity" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val teRef = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, teRef.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindById(rid, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/$rid")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      viewProbe.expectMsg(EntryView.Commands.FindById(rid, Some("sss")))
      viewProbe.reply(FullResult(List(tRm.copy(id = rid))))
      probe.expectMsg(FullResult(List(tRm.copy(id = rid))))
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindById(rid, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/$rid")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("cud", probe.ref, pc.f))

      probe.expectMsg(StandardFailures.AccessDenied)
    }

    "return failure if permissions does not presented" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindById(rid, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/$rid")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
    }
  }

  "When processing a message, FindAll" should {
    "return list with the desired entity" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindAll(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      viewProbe.expectMsg(EntryView.Commands.FindAll(Some("sss")))
      viewProbe.reply(FullResult(List(tRm.copy(id = rid))))
      probe.expectMsg(FullResult(List(tRm.copy(id = rid))))
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindAll(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("cud", probe.ref, pc.f))

      Failure(FailureType.Validation, ErrorMessage("access_denied", Some("User is not authorized to perform this operation")))
    }

    "return failure if permissions does not presented" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindAll(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
    }
  }

  "When processing a message, FindInRange" should {
    "return list with the desired entity" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindInRange(20, 10, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      viewProbe.expectMsg(EntryView.Commands.FindInRange(20, 10, Some("sss")))
      viewProbe.reply(FullResult(List(tRm.copy(id = rid))))
      probe.expectMsg(FullResult(List(tRm.copy(id = rid))))
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindInRange(20, 10, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("cud", probe.ref, pc.f))

      Failure(FailureType.Validation, ErrorMessage("access_denied", Some("User is not authorized to perform this operation")))
    }

    "return failure if permissions does not presented" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.FindInRange(20, 10, Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/*")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
    }
  }

  "When processing a message, GetCount" should {
    "return registered entity's count" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.GetCount(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/#/count")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      viewProbe.expectMsg(EntryView.Commands.GetCount)
      viewProbe.reply(FullResult("999"))
      viewProbe.reply(FullResult("999"))
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.GetCount(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/#/count")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("cud", probe.ref, pc.f))

      Failure(FailureType.Validation, ErrorMessage("access_denied", Some("User is not authorized to perform this operation")))
    }

    "return failure if permissions does not presented" in {
      val rid = UUID.randomUUID().toString
      val probe = TestProbe("P_MAIN")
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref))

      probe.send(target, EntryAggregate.Commands.GetCount(Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/#/count")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
    }
  }

  "When processing a message, Delete" should {
    "return EmptyResult" in {
      val rid = "entry-x11"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.DeleteById("x11", Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x11")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      sctEntityStabProbe.expectMsg(Entry.Commands.Delete(Some("sss")))
      sctEntityStabProbe.reply(EmptyResult)
      probe.expectMsg(EmptyResult)
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = "entry-x12"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.DeleteById("x12", Some("xxx")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x12")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("cru", probe.ref, pc.f))

      Failure(FailureType.Validation, ErrorMessage("access_denied", Some("User is not authorized to perform this operation")))
    }

    "return failure if permissions does not presented" in {
      val rid = "entry-x13"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.DeleteById("x13", Some("xxx")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x13")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      Failure(FailureType.Internal, ErrorMessage("security_error", Some("Unable to check user permissions, no response from security system")))
    }
  }

  "When processing a message, UpdateById" should {
    "return EmptyResult" in {
      val rid = "entry-x14"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.UpdateById("x14", "l", Some("t"), None, Some("b"), Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x14")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crud", probe.ref, pc.f))

      sctEntityStabProbe.expectMsg(Entry.Commands.Update("l", Some("t"), None, Some("b"), Some("sss")))
      sctEntityStabProbe.reply(EmptyResult)
      probe.expectMsg(EmptyResult)
    }

    "return failure if user does not have permissions for this operation" in {
      val rid = "entry-x15"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.UpdateById("x15", "l", Some("t"), None, Some("b"), Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x15")
      permsCheckerProbe.reply(PermissionsChecker.Responses.CheckPong("crd", probe.ref, pc.f))

      probe.expectMsg(StandardFailures.AccessDenied)
    }

    "return failure if permissions does not presented" in {
      val rid = "entry-x16"
      val probe = TestProbe()
      val viewProbe = TestProbe()
      val trRepo = TestProbe()
      val permsCheckerProbe = TestProbe()

      val sctEntityStabProbe = TestProbe(rid)
      system.actorOf(Props(new EntityProxy(sctEntityStabProbe.ref)), rid)

      val target = system.actorOf(EntryAggregate.props(viewProbe.ref, trRepo.ref, permsCheckerProbe.ref, testMode = true))

      probe.send(target, EntryAggregate.Commands.UpdateById("x16", "l", Some("t"), None, Some("b"), Some("sss")))

      //PermissionChecker work imitation
      val pc = permsCheckerProbe.expectMsgType[PermissionsChecker.Commands.CheckPing]
      pc.resource.shouldEqual(s"blog:/entry/x16")
      permsCheckerProbe.reply(PermissionsChecker.Responses.InternalError("err", probe.ref, pc.f))

      probe.expectMsg(StandardFailures.SecurityError)
    }
  }
}