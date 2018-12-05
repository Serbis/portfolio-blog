package ru.serbis.okto.sv_blg

import java.io.File
import java.nio.file.Files

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import ru.serbis.okto.sv_blg.daemon.ApplicationLifecycle

import scala.util.Try

class Application extends ApplicationLifecycle {
  var started: Boolean = false
  var system: Option[ActorSystem] = None
  var workDir: Option[String] = None

  override def init(args: Array[String]) = {
    workDir = Some( (Try { args(0) } recover { case _: Exception => "/etc/sv_blg/" }).get )
    //config = Some(ConfigFactory.parseFile(new File(s"${workDir.get}/node.conf")))
    // TD ошибка отсутствия файла
  }

  override def start() {
    if (!started) {
      started = true
      val appConf = new String(Files.readAllBytes(new File(s"${workDir.get}/application.conf").toPath))
      val serviceConf = new String(Files.readAllBytes(new File(s"${workDir.get}/service.conf").toPath))
      val config = ConfigFactory.parseString(appConf + "\n" + serviceConf)

      system = Some(ActorSystem("sv_blg", config))
      val runner = system.get.actorOf(Runner.props)
      runner ! Runner.Commands.Exec(workDir.get)
    }
  }

  override def stop() {
    if (started) {
      started = false
      system.get.terminate()
    }
  }
}