package ru.serbis.svc.ddd

import java.util.Date

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{DefaultJsonProtocol, JsFalse, JsNumber, JsObject, JsString, JsTrue, JsValue, JsonFormat, RootJsonFormat, deserializationError}

trait InnerJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {

  implicit object AnyJsonFormat extends JsonFormat[Any] {
    def write(x: Any) = x match {
      case n: Int => JsNumber(n)
      case d: Double => JsNumber(d)
      case s: String => JsString(s)
      case b: Boolean if b => JsTrue
      case b: Boolean if !b => JsFalse
    }
    def read(value: JsValue) = value match {
      case JsNumber(n) if n.isValidInt => n.intValue()
      case JsNumber(n) if n.isExactDouble => n.doubleValue()
      case JsString(s) => s
      case JsTrue => true
      case JsFalse => false
    }
  }
}