package ru.serbis.svc.ddd

import java.util.Date

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

/**
  * Root json protocol class for others to extend from
  */
trait CommonJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {
  implicit object DateFormat extends JsonFormat[Date] {
    def write(date : Date) : JsValue = JsNumber(date.getTime)
    def read(json: JsValue) : Date = json match {
      case JsNumber(epoch) => new Date(epoch.toLong)
      case unknown => deserializationError(s"Expected JsString, got $unknown")
    }
  }
  implicit object MapJsonFormat extends RootJsonFormat[Map[Long, String]] {
    override def read(json: JsValue) = json match {
      case JsObject(m) if m.head._2.isInstanceOf[JsString] =>
        Map(m.head._1.toLong -> m(m.head._1).asInstanceOf[JsString].value)
    }

    override def write(obj: Map[Long, String]) = obj match {
      case m =>
        JsObject(m.map(v => v._1.toString -> JsString(v._2)))
    }
  }
  /*implicit object AnyJsonFormat extends JsonFormat[Any] {
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
  }*/
}