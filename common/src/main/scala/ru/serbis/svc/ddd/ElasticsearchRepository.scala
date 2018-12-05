package ru.serbis.svc.ddd

import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import ru.serbis.svc.logger.Logger.LogEntryQualifier
import ru.serbis.svc.logger.StreamLogger
import spray.json.{JsObject, JsonFormat, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.reflect.ClassTag
import spray.json._

object ElasticsearchApi extends CommonJsonProtocol with InnerJsonProtocol {
  trait EsResponse
  case class ShardData(total:Int, failed:Int, successful:Int)
  case class IndexingResult(_shards:ShardData, _index:String, _type:String, _id:String, _version:Int, created:Option[Boolean]) extends EsResponse

  case class UpdateScript(inline:String, params:Map[String,Any])
  case class UpdateRequest(script:UpdateScript)

  case class SearchHit(_source:JsObject)
  case class QueryHits(hits:List[SearchHit])
  case class QueryResponse(hits:QueryHits) extends EsResponse

  case class DeleteResult(acknowledged:Boolean) extends EsResponse

  implicit val shardDataFormat = jsonFormat3(ShardData)
  implicit val indexResultFormat = jsonFormat6(IndexingResult)
  implicit val updateScriptFormat = jsonFormat2(UpdateScript)
  implicit val updateRequestFormat = jsonFormat1(UpdateRequest)
  implicit val searchHitFormat = jsonFormat1(SearchHit)
  implicit val queryHitsFormat = jsonFormat1(QueryHits)
  implicit val queryResponseFormat = jsonFormat1(QueryResponse)
  implicit val deleteResultFormat = jsonFormat1(DeleteResult)
}

object ElasticsearchRepository extends CommonJsonProtocol {
  import ElasticsearchApi.UpdateRequest

  def props[RM](indexRoot: String, entityType: String, jf: JsonFormat[RM]): Props =
    Props(new ElasticsearchRepository[RM](indexRoot: String, entityType: String, jf: JsonFormat[RM]))

  object Commands {
    trait Query

    case class QueryElasticsearch(query: String) extends Query
    case class CreateEntry(id: String, fpJson: String)
    case class UpdateEntry(id: String, request: UpdateRequest, version: Option[Long])
    case class DeleteEntry(id: String)
    case class ClearIndex()

    /** Return total docs count in the index. Respond with TotalCound or QueryError messages */
    case object GetTotalCount

    /** Request es with dsl query entity */
    case class QueryDsl(query: String) extends Query
  }

  object Responses {
    /** Total docs count in index */
    case class TotalCount(count: Int)

    /** Some query error - net error, json error and etc */
    case class QueryError(reason: String)
  }

}

class ElasticsearchRepository[RM](indexRoot: String, entityType: String, jf: JsonFormat[RM]) extends CommonActor with StreamLogger {
  import ElasticsearchRepository.Commands._
  import ElasticsearchRepository.Responses._
  import ElasticsearchApi._

  setLogSourceName(s"ElasticsearchRepository*$entityType")
  setLogKeys(Seq("ElasticsearchRepository"))

  implicit val logQualifier = LogEntryQualifier("static")
  implicit val ec = context.system.dispatcher
  implicit val mater = ActorMaterializer()
  implicit val jsonFormat = jf

  val esSettings = ElasticsearchSettings(context.system)

  logger.debug(s"ElasticsearchRepository actor for entity '$entityType' as started")



  def baseUrl = s"${esSettings.rootUrl}/$indexRoot-$entityType"

  override def receive = {
    case QueryElasticsearch(query) =>
      val req = HttpRequest(HttpMethods.GET, Uri(s"$baseUrl/$entityType/_search?q=$query")/*.withQuery(Uri.Query(("q",  query)))*/)
      callElasticsearch[QueryResponse](req).flatMap(v => Future {
        v.hits.hits.map(_._source.convertTo[RM])
      }).pipeTo(sender())
    case ClearIndex() =>
      val req = HttpRequest(HttpMethods.DELETE, s"${esSettings.rootUrl}/$indexRoot/")
      callElasticsearch[DeleteResult](req).pipeTo(sender())
    case CreateEntry(id, fpJson) =>
      val urlBase = s"$baseUrl/$entityType/$id"
      val requestUrl = urlBase
      val entity = HttpEntity(ContentTypes.`application/json`, fpJson )
      val req = HttpRequest(HttpMethods.POST, requestUrl, entity = entity)
      callElasticsearch[IndexingResult](req).pipeTo(sender())
    case UpdateEntry(id, request, version) =>
      val urlBase = s"$baseUrl/$entityType/$id"
      val requestUrl = version match{
        case None => urlBase
        case Some(v) => s"$urlBase/_update?version=$v"
      }
      val entity = HttpEntity(ContentTypes.`application/json`, request.toJson.prettyPrint )
      val req = HttpRequest(HttpMethods.POST, requestUrl, entity = entity)
      callElasticsearch[IndexingResult](req).pipeTo(sender())
    case DeleteEntry(id) =>
      val req = HttpRequest(HttpMethods.DELETE, Uri(s"$baseUrl/$entityType/$id"))
      callElasticsearch[IndexingResult](req).pipeTo(sender())

    case QueryDsl(query) =>
      implicit val logQualifier = LogEntryQualifier("QueryDsl")
      logger.debug(s"Fetches an entity's with search dsl query '$query'")
      val entity = HttpEntity(ContentTypes.`application/json`, query)
      val req = HttpRequest(HttpMethods.GET, Uri(s"$baseUrl/$entityType/_search"), entity = entity)
      callElasticsearch[QueryResponse](req).flatMap(v => Future {
        v.hits.hits.map(_._source.convertTo[RM])
      }).pipeTo(sender())
    case GetTotalCount =>
      implicit val logQualifier = LogEntryQualifier("GetTotalCount")
      logger.debug("Requested total entry count")
      val req = HttpRequest(HttpMethods.GET, Uri(s"$baseUrl/_stats/docs"))
      Http(context.system).singleRequest(req).flatMap {
        case resp if resp.status.isSuccess =>
          resp.entity.toStrict(5 second)
        case resp =>
          resp.discardEntityBytes()
          logger.error(s"Elasticsearch respond with unexpected status code '${resp.status}'")
          Future.failed(new RuntimeException(s"Unexpected status code of: ${resp.status} with entity ${resp.entity}"))
      } map { e =>
        try {
          val count = e.data.utf8String.parseJson
            .asJsObject.fields("indices")
            .asJsObject.fields(s"$indexRoot-$entityType")
            .asJsObject.fields("primaries")
            .asJsObject.fields("docs")
            .asJsObject.fields("count")
            .convertTo[Int]
          logger.debug(s"Total entry's count '$count' was returned")
          TotalCount(count)
        } catch {
          case ex: Throwable =>
            logger.error(s"Elasticsearch respond with dab json. Reason '${ex.getMessage}'")
            QueryError("bad elasticsearch response")
        }
      } recover {
        case ex: Throwable =>
          logger.error(s"Elasticsearch response error. Reason '${ex.getMessage}'")
          QueryError("response error")
      } pipeTo sender()
  }

  def callElasticsearch[RT : ClassTag](req: HttpRequest)(implicit unmarshaller: Unmarshaller[ResponseEntity, RT]): Future[RT] = {
    //println(req)
    Http(context.system).
      singleRequest(req).
      flatMap{
        case resp if resp.status.isSuccess =>
          Unmarshal(resp.entity).to[RT]
        case resp =>
          resp.discardEntityBytes()
          Future.failed(new RuntimeException(s"Unexpected status code of: ${resp.status} with entity ${resp.entity}"))
      }
  }
}

class ElasticsearchSettingsImpl(conf:Config) extends Extension{
  val esConfig = conf.getConfig("elasticsearch")
  val host = esConfig.getString("host")
  val port = esConfig.getInt("port")
  val rootUrl = s"http://$host:$port"
}
object ElasticsearchSettings extends ExtensionId[ElasticsearchSettingsImpl] with ExtensionIdProvider {
  override def lookup = ElasticsearchSettings
  override def createExtension(system: ExtendedActorSystem) =
    new ElasticsearchSettingsImpl(system.settings.config)
}
