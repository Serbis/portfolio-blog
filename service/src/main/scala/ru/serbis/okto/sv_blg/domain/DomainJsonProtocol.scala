package ru.serbis.okto.sv_blg.domain

import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.svc.ddd.CommonJsonProtocol

/**
  * Domain json protocols
  */
trait DomainJsonProtocol extends CommonJsonProtocol {
  implicit val entryRmFormat = jsonFormat6(EntryRM.apply)
}