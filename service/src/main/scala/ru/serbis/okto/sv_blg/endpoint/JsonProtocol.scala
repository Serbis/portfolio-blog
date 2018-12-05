package ru.serbis.okto.sv_blg.endpoint

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._
import Models._
import ru.serbis.okto.sv_blg.domain.DomainJsonProtocol
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.svc.JsonWritable

/** Endpoint spray json protocol */
trait JsonProtocol extends SprayJsonSupport with DefaultJsonProtocol with DomainJsonProtocol {
  implicit def resultFormat[T : JsonFormat] = jsonFormat2(EndpointResult.apply[T])

  implicit val anyFormat = AnyFormat
  implicit val updateEntryFormant = jsonFormat4(UpdatedEntry.apply)

  object AnyFormat extends JsonFormat[Any] {
    override def write(obj: Any) = obj match {
      case v: EntryRM => entryRmFormat.write(v)
      case v: String => StringJsonFormat.write(v)
      case v: List[EntryRM] => JsArray(v.map(_.toJson).toVector)
      case v: Map[String, String] => JsObject {
        v.map { field =>
          field._1.toJson match {
            case JsString(x) => x -> field._2.toJson
            case x => throw new SerializationException("Map key must be formatted as JsString, not '" + x + "'")
          }
        }
      }
      case unrecognized => throw DeserializationException(s"Serialization problem $unrecognized")
    }

    override def read(json: JsValue) = json match {case _ => throw DeserializationException("_")}
  }
}
