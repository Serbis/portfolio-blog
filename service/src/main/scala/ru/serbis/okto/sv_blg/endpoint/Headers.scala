package ru.serbis.okto.sv_blg.endpoint

import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}

import scala.util.Try

object Headers {
  final class SessionHeader(token: String) extends ModeledCustomHeader[SessionHeader] {
    override def renderInRequests = true
    override def renderInResponses = true
    override val companion = SessionHeader
    override def value: String = token

    override def hashCode() = token.hashCode()
  }
  object SessionHeader extends ModeledCustomHeaderCompanion[SessionHeader] {
    override val name = "Session"
    override def parse(value: String) = Try(new SessionHeader(value))
  }
}
