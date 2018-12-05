package ru.serbis.okto.sv_blg.daemon

import org.apache.commons.daemon.{Daemon, DaemonContext}

abstract class AbstractApplicationDaemon extends Daemon {
  def application: ApplicationLifecycle

  def init(daemonContext: DaemonContext) = application.init(daemonContext.getArguments)

  def start() = application.start()

  def stop() = application.stop()

  def destroy() = application.stop()
}