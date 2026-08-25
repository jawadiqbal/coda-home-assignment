package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._

/** Controller-level tests using FakeRequest (no real server started).
  * GuiceOneAppPerTest gives a fresh application — and therefore a fresh
  * InMemoryKvStore singleton — for every test.
  */
class KvControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  // ---- GET ----------------------------------------------------------------

  "GET /kv/:key" should {
    "return 404 for a missing key" in {
      val result = route(app, FakeRequest(GET, "/kv/absent")).value
      status(result) mustBe NOT_FOUND
      (contentAsJson(result) \ "error").as[String] mustBe "key not found"
    }

    "return 200 with key, value and version after a PUT" in {
      status(
        route(
          app,
          FakeRequest(PUT, "/kv/foo").withJsonBody(Json.obj("n" -> 1))
        ).value
      ) mustBe OK
      val result = route(app, FakeRequest(GET, "/kv/foo")).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "key").as[String] mustBe "foo"
      (json \ "version").as[Long] mustBe 1L
      (json \ "value").as[JsObject] mustBe Json.obj("n" -> 1)
    }
  }

  // ---- PUT ----------------------------------------------------------------

  "PUT /kv/:key" should {
    "return 200 with version 1 on first write" in {
      val result =
        route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(42))).value
      status(result) mustBe OK
      (contentAsJson(result) \ "version").as[Long] mustBe 1L
    }

    "increment version on subsequent writes" in {
      route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(1)))
      val result =
        route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(2))).value
      status(result) mustBe OK
      (contentAsJson(result) \ "version").as[Long] mustBe 2L
    }

    "return 400 for a non-numeric ifVersion query param" in {
      val result = route(
        app,
        FakeRequest(PUT, "/kv/bar?ifVersion=abc").withJsonBody(JsNumber(1))
      ).value
      status(result) mustBe BAD_REQUEST
    }

    "return 409 when ifVersion mismatches" in {
      route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(1)))
      val result = route(
        app,
        FakeRequest(PUT, "/kv/bar?ifVersion=99").withJsonBody(JsNumber(2))
      ).value
      status(result) mustBe CONFLICT
      (contentAsJson(result) \ "currentVersion").as[Long] mustBe 1L
    }

    "return 409 when ifVersion is supplied but the key does not exist" in {
      val result = route(
        app,
        FakeRequest(PUT, "/kv/new?ifVersion=1").withJsonBody(JsNull)
      ).value
      status(result) mustBe CONFLICT
    }

    "accept ifVersion from If-Match header" in {
      route(app, FakeRequest(PUT, "/kv/h").withJsonBody(JsString("v1")))
      val req = FakeRequest(PUT, "/kv/h")
        .withHeaders("If-Match" -> "1")
        .withJsonBody(JsString("v2"))
      val result = route(app, req).value
      status(result) mustBe OK
      (contentAsJson(result) \ "version").as[Long] mustBe 2L
    }

    "give query param precedence over If-Match header" in {
      route(app, FakeRequest(PUT, "/kv/p").withJsonBody(JsString("v1")))
      // query says version 1 (correct), header says 99 (wrong)
      val req = FakeRequest(PUT, "/kv/p?ifVersion=1")
        .withHeaders("If-Match" -> "99")
        .withJsonBody(JsString("v2"))
      val result = route(app, req).value
      status(result) mustBe OK
    }
  }

  // ---- PATCH --------------------------------------------------------------

  "PATCH /kv/:key" should {
    "create the key when absent" in {
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/new").withJsonBody(Json.obj("x" -> 1))
      ).value
      status(result) mustBe OK
      (contentAsJson(result) \ "version").as[Long] mustBe 1L
    }

    "shallow-merge two objects" in {
      route(
        app,
        FakeRequest(PUT, "/kv/obj").withJsonBody(Json.obj("a" -> 1, "b" -> 2))
      )
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/obj").withJsonBody(
          Json.obj("b" -> 99, "c" -> 3)
        )
      ).value
      status(result) mustBe OK

      val get = route(app, FakeRequest(GET, "/kv/obj")).value
      (contentAsJson(get) \ "value")
        .as[JsObject] mustBe Json.obj("a" -> 1, "b" -> 99, "c" -> 3)
    }

    "replace when delta is not an object" in {
      route(app, FakeRequest(PUT, "/kv/num").withJsonBody(Json.obj("a" -> 1)))
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/num").withJsonBody(JsNumber(7))
      ).value
      status(result) mustBe OK

      val get = route(app, FakeRequest(GET, "/kv/num")).value
      (contentAsJson(get) \ "value").as[JsNumber] mustBe JsNumber(7)
    }

    "return 409 when ifVersion mismatches" in {
      route(app, FakeRequest(PUT, "/kv/pv").withJsonBody(Json.obj("x" -> 0)))
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/pv?ifVersion=99").withJsonBody(
          Json.obj("x" -> 1)
        )
      ).value
      status(result) mustBe CONFLICT
    }
  }
}
