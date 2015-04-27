package akka.contrib.http

import org.scalatest.{ Inside, Matchers, WordSpec }
import akka.http.scaladsl.testkit.TestFrameworkInterface.Scalatest
import akka.http.scaladsl.testkit.RouteTest
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{ HttpEntity, HttpResponse, MediaRange }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ MalformedQueryParamRejection, Route }

class DirectivesSpec extends WordSpec with Matchers with Scalatest with RouteTest with Inside {

  import Directives._

  def echoComplete[T]: T ⇒ Route = { x ⇒ complete(x.toString) }

  val Ok = HttpResponse()
  val completeOk = complete(Ok)

  "accept directive" should {

    "pass to inner route if a matching accept header is present" in {
      Get("/ping") ~> Accept(MediaRange(`text/plain`)) ~> accept(`text/plain`) { completeOk } ~> check {
        response.status shouldBe OK
      }
    }

    "reject if no requested accept header is present" in {
      Get("/ping") ~> accept(`text/plain`) { completeOk } ~> check {
        rejections shouldEqual Nil
      }
    }
  }

  "parameterList directive" should {

    "provide a list of parameters to the inner route" in {
      Get("/ping?p=1&p=2&p=3") ~> parameterList('p.as[Int]) { echoComplete } ~> check {
        responseAs[String] shouldBe "List(3, 2, 1)"
      }
    }

    "provide an empty list to the inner route if no parameters specified" in {
      Get("/ping") ~> parameterList('p.as[Int]) { echoComplete } ~> check {
        responseAs[String] shouldBe "List()"
      }
    }

    "reject if parameters are of the wrong type" in {
      Get("/ping?p=hello") ~> parameterList('p.as[Int]) { echoComplete } ~> check {
        inside(rejection) { case MalformedQueryParamRejection("p", _, Some(e: NumberFormatException)) ⇒ }
      }
    }

  }

}
