package ru.serbis.okto.sv_blg

import org.apache.commons.daemon.DaemonContext
import ru.serbis.okto.sv_blg.daemon.ApplicationDaemon


class DaemonContextStub(args: Array[String]) extends DaemonContext {
  override def getArguments = args
  override def getController = null
}

object Main extends App {
  val application = createApplication()

  def createApplication() = new ApplicationDaemon

  private[this] var cleanupAlreadyRun: Boolean = false

  def cleanup(){
    val previouslyRun = cleanupAlreadyRun
    cleanupAlreadyRun = true
    if (!previouslyRun) application.stop()
  }

  Runtime.getRuntime.addShutdownHook(new Thread(() => {
    cleanup()
  }))

  application.init(new DaemonContextStub(args))
  application.start()
}