package ru.serbis.okto.sv_blg.exps

import java.io.File
import java.nio.file.Files
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.DequeBasedMessageQueueSemantics
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import ru.serbis.okto.sv_blg.domain.DomainJsonProtocol
import ru.serbis.okto.sv_blg.proxy.files.RealFilesProxy
import ru.serbis.okto.sv_blg.proxy.system.RealActorSystemProxy
import ru.serbis.svc.ddd.{ElasticsearchRepository, FullResult}
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}

import scala.concurrent.duration._
import akka.pattern.ask
import ru.serbis.okto.sv_blg.domain.entry.EntryAggregate
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.domain.entry.read.{EntryView, EntryViewBuilder}
import ru.serbis.okto.sv_blg.domain.entry.write.{Entry, EntryFO}
import ru.serbis.okto.sv_blg.proxy.http.RealHttpProxy
import ru.serbis.okto.sv_blg.service.{PermissionsChecker, TextResourceRepository}
import ru.serbis.okto.sv_blg.service.TextResourceRepository.Commands.{CreateAction, DeleteAction, ReadAction, UpdateAction}

import scala.concurrent.Await

object Exps extends DomainJsonProtocol with StreamLogger with App {
  val appConf = new String(Files.readAllBytes(new File("dist/sv_blg/application.conf").toPath))
  val serviceConf = new String(Files.readAllBytes(new File("dist/sv_blg/service.conf").toPath))

  val config = ConfigFactory.parseString(appConf + "\n" + serviceConf)
  val system = ActorSystem("exps", config)

  initializeGlobalLogger(system, LogLevels.Debug)
  logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))




  //05bf6b38-54b4-4248-8cbd-f1ac68783e5e   сущность
  //97512541-6ac7-4466-93bc-9a9e577a4c9d
  val session = "637223e0-3f02-438a-ab58-2687ba13bc95"

  val tpRepo = system.actorOf(TextResourceRepository.props("https://okto.su:5101", new RealActorSystemProxy(system), new RealHttpProxy()(system)))
  val entryRepo = system.actorOf(ElasticsearchRepository.props("sv_blg", "entry", entryRmFormat))
  system.actorOf(EntryViewBuilder.props(entryRepo), EntryViewBuilder.Name)
  val entryView = system.actorOf(EntryView.props(entryRepo, tpRepo, new RealActorSystemProxy(system)), EntryView.Name)
  val permsChecker = system.actorOf(PermissionsChecker.props("https://okto.su:5100", new RealActorSystemProxy(system), new RealHttpProxy()(system)))
  val entryAggregate = system.actorOf(EntryAggregate.props(entryView, tpRepo, permsChecker))

  val r = Await.result(entryView.ask(EntryView.Commands.FindAll(Some(session)))(1000 second), 1000 second)//.asInstanceOf[FullResult[List[EntryRM]]]
  /*r.value.foreach {v =>
    println("")
    print(s"${v.id} ... ")
    val r = Await.result(entryAggregate.ask(EntryAggregate.Commands.DeleteById(v.id, Some("84c1d172-4c1a-41e1-b2aa-08271bb46884")))(1000 second), 1000 second)
    print(r)
  }*/
  println(r)

  /*(1 to 29) foreach {i =>
    Await.result(entryAggregate.ask(EntryAggregate.Commands.Create(Some("e4567f8f-74ad-4687-b7fd-3c0912b7dc7d")))(1000 second), 1000 second) match {
      case FullResult(v: String) =>
        Await.result(entryAggregate.ask(EntryAggregate.Commands.UpdateById(v, "ru", Some(s"Title-$i"), Some(s"First text-$i"), Some(s"Second text-$i"), Some("e4567f8f-74ad-4687-b7fd-3c0912b7dc7d")))(1000 second), 1000 second)
    }
  }*/



  //val entry = system.actorOf(Entry.props("05bf6b38-54b4-4248-8cbd-f1ac68783e5e", tpResp))

  //val r1 = Await.result(entry.ask(Entry.Commands.Create(fo))(10 second), 10 second)
  //val r2 = Await.result(entry.ask(Entry.Commands.Update("ru", Some("Entry title 9"), Some("Entry A text 9"), Some("Entry B text 9"), Some("3f0cf422-6aa7-4389-926f-76b12da355c1")))(100 second), 100 second)
  //println(r2)
  //val r3 = Await.result(entry.ask(Entry.Commands.Update("ru", Some("Entry title 10"), Some("Entry A text 10"), Some("Entry B text 10"), Some("3f0cf422-6aa7-4389-926f-76b12da355c1")))(100 second), 100 second)
  //println(r3)

  //val entry = system.actorOf(Entry.props("179fb04b-2a8a-43e5-85ec-05156af63802", ActorRef.noSender))
  //val r = Await.result(entry.ask(Entry.Commands.Create(fo))(5 second), 5 second)

  /*val storage = system.actorOf(Storage.props("dist/storage", new RealFilesProxy))
  val permsChecker = system.actorOf(PermissionsChecker.props("https://localhost:5100", new RealActorSystemProxy(system), new RealHttpProxy()(system)))

  val textResourceRepo = system.actorOf(ElasticsearchRepository.props("sv_blg", "text_resource", textResourceRmFormat))
  system.actorOf(TextResourceViewBuilder.props(textResourceRepo), TextResourceViewBuilder.Name)
  val textResourceView = system.actorOf(TextResourceView.props(textResourceRepo, storage, new RealActorSystemProxy(system)), TextResourceView.Name)
  val textResourceAggregate = system.actorOf(TextResourceAggregate.props(textResourceView, storage, permsChecker), TextResourceAggregate.Name)*/

  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.Create("zxc", None))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.DeleteById("d93759e6-1fda-46e1-ae56-cf8304dc3d14", None))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindById("d93759e6-1fda-46e1-ae56-cf8304dc3d14", None))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindAll(None))(10 second), 10 second)

  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.Create("zxc", Some("d7dbd085-1a68-4425-a351-2938fb23e4a9")))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.DeleteById("041f8aec-7158-4055-8b80-672bf90d0ef1", Some("d7dbd085-1a68-4425-a351-2938fb23e4a9")))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindById("d93759e6-1fda-46e1-ae56-cf8304dc3d14", Some("d7dbd085-1a68-4425-a351-2938fb23e4a9")))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindAll(Some("d7dbd085-1a68-4425-a351-2938fb23e4a9")))(10 second), 10 second)

  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindById("d93759e6-1fda-46e1-ae56-cf8304dc3d14", None))(10 second), 10 second)
  //val r = Await.result(textResourceAggregate.ask(TextResourceAggregate.Commands.FindAll)(10 second), 10 second)
  //textResourceAggregate ! TextResourceAggregate.Commands.SetBody("9a76d4e9-11aa-4669-9c1c-f59ae96533bc", "actobar")
  //textResourceAggregate ! TextResourceAggregate.Commands.DeleteById("9a76d4e9-11aa-4669-9c1c-f59ae96533bc")
  //println(r)
}