package ru.serbis.okto.sv_blg.unit

import akka.http.javadsl.model.StatusCodes
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import ru.serbis.okto.sv_blg.domain.{DomainJsonProtocol, StandardFailures}
import ru.serbis.okto.sv_blg.domain.entry.EntryAggregate
import ru.serbis.okto.sv_blg.domain.entry.read.EntryViewBuilder.EntryRM
import ru.serbis.okto.sv_blg.endpoint.Models.{EndpointResult, UpdatedEntry}
import ru.serbis.okto.sv_blg.endpoint.{HttpRoute, JsonProtocol}
import ru.serbis.svc.ddd._
import ru.serbis.svc.logger.Logger.LogLevels
import ru.serbis.svc.logger.{StdOutLogger, StreamLogger}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class HttpRouteSpec extends WordSpec with Matchers with ScalatestRouteTest with BeforeAndAfterAll with JsonProtocol with DomainJsonProtocol with StreamLogger {

  implicit val mater = ActorMaterializer()

  override protected def beforeAll(): Unit = {
    initializeGlobalLogger(system, LogLevels.Info)
    logger.addDestination(system.actorOf(StdOutLogger.props, "StdOutLogger"))
  }

  "At path entry" must {
    "For POST must request EntryAggregate to create new entry and after that" should {
      "Return entity id if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))
        entryAggregateProbe.reply(FullResult("123"))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual "123"
        } (result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        } (result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        } (result)
      }

      "Return code 500 if some inner failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))
        entryAggregateProbe.reply(StandardFailures.ReadSideRepoError("x"))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.ReadSideRepoError("x").message.shortText.get
        } (result)
      }

      "Return error if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "No response from aggregate"
        } (result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Post("/entry") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.Create(None))
        entryAggregateProbe.reply("X")

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        } (result)
      }
    }
  }

  "At path entry / SEGMENT " must {
    "For GET must request EntryAggregate to get entry rm with specified if and after that" should {
      "Return code 200 and entry rm if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))
        entryAggregateProbe.reply(FullResult(List(EntryRM(id = "123", timestamp = 100L))))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual EntryRM(id = "123", timestamp = 100L).toJson.compactPrint
        }(result)
      }

      "Return code 404 and empty if aggregate return empty list" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))
        entryAggregateProbe.reply(FullResult(List.empty))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 404
          r.value shouldEqual ""
        }(result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        }(result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        }(result)
      }

      "Return code 500 and error message if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
        }(result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindById("123", None))
        entryAggregateProbe.reply(0)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        }(result)
      }
    }

    "For PUT must request EntryAggregate to update entry with specified if and after that" should {
      "Return code 200 and empty value if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))
        entryAggregateProbe.reply(EmptyResult)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual ""
        }(result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        }(result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        }(result)
      }

      "Return code 500 if some inner failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))
        entryAggregateProbe.reply(StandardFailures.EntryUpdateFailure("x"))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.EntryUpdateFailure("x").message.shortText.get
        }(result)
      }

      "Return code 500 and error message if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
        }(result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val entity = HttpEntity(ContentTypes.`application/json`, UpdatedEntry("l", Some("t"), None, Some("b")).toJson.prettyPrint)
        val routerFuture = Future { Put("/entry/123", entity) ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.UpdateById("123", "l", Some("t"), None, Some("b"), None))
        entryAggregateProbe.reply(0)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        }(result)
      }
    }

    "For DELETE must request EntryAggregate to delete entry with specified if and after that" should {
      "Return code 200 and empty value if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))
        entryAggregateProbe.reply(EmptyResult)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual ""
        } (result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        } (result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        } (result)
      }

      "Return code 500 if some inner failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))
        entryAggregateProbe.reply(StandardFailures.EntryUpdateFailure("x"))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.EntryUpdateFailure("x").message.shortText.get
        } (result)
      }

      "Return code 500 and error message if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "No response from aggregate"
        } (result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Delete("/entry/123") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.DeleteById("123", None))
        entryAggregateProbe.reply(0)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        } (result)
      }
    }
  }

  "At path resource / entry / range / INT / INT " must {
    "For GET must request EntryAggregate to fetch entry's in specified range and after that" should {
      "Return code 200 and entry rm's list if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))
        entryAggregateProbe.reply(FullResult(List(EntryRM(id = "123", timestamp = 100L))))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual List(EntryRM(id = "123", timestamp = 100L)).toJson.compactPrint
        }(result)
      }

      "Return code 404 and empty if aggregate return empty list" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))
        entryAggregateProbe.reply(FullResult(List.empty))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 404
          r.value shouldEqual ""
        }(result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        }(result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        }(result)
      }

      "Return code 500 and error message if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
        }(result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/range/20/10") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.FindInRange(20, 10, None))
        entryAggregateProbe.reply(0)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        }(result)
      }
    }
  }

  "At path resource / entry / total " must {
    "For GET must request EntryAggregate to get total entry's count after that" should {
      "Return code 200 and entry's count if operation was success" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/total") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.GetCount(None))
        entryAggregateProbe.reply(FullResult("99"))

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 200
          r.value shouldEqual "99"
        }(result)
      }

      "Return code 500 if access denied failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/total") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.GetCount(None))
        entryAggregateProbe.reply(StandardFailures.AccessDenied)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 401
          r.value shouldEqual StandardFailures.AccessDenied.message.shortText.get
        }(result)
      }

      "Return code 500 if security error failure was received from aggregate" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/total") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.GetCount(None))
        entryAggregateProbe.reply(StandardFailures.SecurityError)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual StandardFailures.SecurityError.message.shortText.get
        }(result)
      }

      "Return code 500 and error message if aggregate does not respond with expected timeout" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref), tm = true)
        val routerFuture = Future { Get("/entry/total") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.GetCount(None))
        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
        }(result)
      }

      "Return code 500 and error message if aggregate respond with unexpected response" in {
        val entryAggregateProbe = TestProbe()
        val router = HttpRoute(HttpRoute.Aggregates(entryAggregateProbe.ref))
        val routerFuture = Future { Get("/entry/total") ~> router.routes ~> runRoute }

        entryAggregateProbe.expectMsg(EntryAggregate.Commands.GetCount(None))
        entryAggregateProbe.reply(0)

        val result = Await.result(routerFuture, 3 second)

        check {
          status shouldEqual StatusCodes.OK
          val r = responseAs[String].parseJson.convertTo[EndpointResult[String]]
          r.code shouldEqual 500
          r.value shouldEqual "Wrong aggregate response"
        }(result)
      }
    }
  }
}