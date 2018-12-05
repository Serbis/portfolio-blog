package ru.serbis.okto.sv_blg.daemon

import ru.serbis.okto.sv_blg.Application

class ApplicationDaemon() extends AbstractApplicationDaemon {
  var app: Option[Application] = None
  def application = {
    if (app.isEmpty)
      app = Some(new Application)

    app.get
  }
}
