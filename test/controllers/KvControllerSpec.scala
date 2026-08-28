package controllers

import org.scalatestplus.play._
import org.scalatestplus.play.guice._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._

/** Controller-level tests using FakeRequest (no real server started).
  * GuiceOneAppPerTest gives a fresh application — and therefore a fresh
  * InMemoryKvStore singleton — for every test.
  */
class KvControllerSpec extends PlaySpec with GuiceOneAppPerTest {

  // ---- GET /kv (no key) --------------------------------------------------

  "GET /kv" should {
    "return 501 on a node process (listAll is router-only)" in {
      val result = route(app, FakeRequest(GET, "/kv")).value
      status(result) mustBe NOT_IMPLEMENTED
      (contentAsJson(result) \ "error").as[String] must include(
        "router process"
      )
    }
  }

  "GET /internal/keys" should {
    "stream keys as NDJSON on a node process" in {
      status(
        route(
          app,
          FakeRequest(PUT, "/kv/ik").withJsonBody(Json.obj("n" -> 1))
        ).value
      ) mustBe OK
      implicit val mat = app.materializer
      val result = route(app, FakeRequest(GET, "/internal/keys")).value
      status(result) mustBe OK
      contentType(result).getOrElse("") must include("application/x-ndjson")
      contentAsString(result) must include("\"key\":\"ik\"")
    }

    "return 404 on a router process" in {
      val routerApp =
        new GuiceApplicationBuilder().configure("kv.role" -> "router").build()
      running(routerApp) {
        val result =
          route(routerApp, FakeRequest(GET, "/internal/keys")).value
        status(result) mustBe NOT_FOUND
        (contentAsJson(result) \ "error").as[String] must include(
          "storage node"
        )
      }
    }
  }

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
    "return 200 with key, value and version on first write" in {
      val result =
        route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(42))).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "key").as[String] mustBe "bar"
      (json \ "value").as[JsNumber] mustBe JsNumber(42)
      (json \ "version").as[Long] mustBe 1L
    }

    "return the updated value in the response on subsequent writes" in {
      route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(1)))
      val result =
        route(app, FakeRequest(PUT, "/kv/bar").withJsonBody(JsNumber(2))).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "value").as[JsNumber] mustBe JsNumber(2)
      (json \ "version").as[Long] mustBe 2L
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

    "accept ifVersion from kv-if-version header" in {
      route(app, FakeRequest(PUT, "/kv/h").withJsonBody(JsString("v1")))
      val req = FakeRequest(PUT, "/kv/h")
        .withHeaders("kv-if-version" -> "1")
        .withJsonBody(JsString("v2"))
      val result = route(app, req).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "value").as[String] mustBe "v2"
      (json \ "version").as[Long] mustBe 2L
    }

    "treat kv-if-version header name as case-insensitive" in {
      route(app, FakeRequest(PUT, "/kv/ci").withJsonBody(JsString("v1")))
      val req = FakeRequest(PUT, "/kv/ci")
        .withHeaders("KV-If-Version" -> "1")
        .withJsonBody(JsString("v2"))
      val result = route(app, req).value
      status(result) mustBe OK
      (contentAsJson(result) \ "version").as[Long] mustBe 2L
    }

    "give query param precedence over kv-if-version header" in {
      route(app, FakeRequest(PUT, "/kv/p").withJsonBody(JsString("v1")))
      // query says version 1 (correct), header says 99 (wrong)
      val req = FakeRequest(PUT, "/kv/p?ifVersion=1")
        .withHeaders("kv-if-version" -> "99")
        .withJsonBody(JsString("v2"))
      val result = route(app, req).value
      status(result) mustBe OK
    }
  }

  // ---- Key validation -----------------------------------------------------

  "GET /kv/:key" should {
    "return 400 for a key with a '.' segment" in {
      val result = route(app, FakeRequest(GET, "/kv/foo/./bar")).value
      status(result) mustBe BAD_REQUEST
      val json = contentAsJson(result)
      (json \ "error").as[String] must include("'.' and '..'")
      (json \ "key").as[String] mustBe "foo/./bar"
    }

    "return 400 for a key with a '..' segment" in {
      val result = route(app, FakeRequest(GET, "/kv/foo/../etc/passwd")).value
      status(result) mustBe BAD_REQUEST
      val json = contentAsJson(result)
      (json \ "error").as[String] must include("'.' and '..'")
    }

    "return 400 for a key that is exactly '.'" in {
      val result = route(app, FakeRequest(GET, "/kv/.")).value
      status(result) mustBe BAD_REQUEST
    }

    "return 400 for a key that is exactly '..'" in {
      val result = route(app, FakeRequest(GET, "/kv/..")).value
      status(result) mustBe BAD_REQUEST
    }

    "allow a key whose segment merely starts with dots (e.g. '...foo')" in {
      status(
        route(
          app,
          FakeRequest(PUT, "/kv/...foo").withJsonBody(JsNumber(1))
        ).value
      ) mustBe OK
      val result = route(app, FakeRequest(GET, "/kv/...foo")).value
      status(result) mustBe OK
    }
  }

  "PUT /kv/:key" should {
    "return 400 for a key with a '.' segment" in {
      val result =
        route(
          app,
          FakeRequest(PUT, "/kv/a/./b").withJsonBody(JsNumber(1))
        ).value
      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] must include("'.' and '..'")
    }

    "return 400 for a key with a '..' segment" in {
      val result =
        route(
          app,
          FakeRequest(PUT, "/kv/x/../y").withJsonBody(JsNumber(1))
        ).value
      status(result) mustBe BAD_REQUEST
    }
  }

  "PATCH /kv/:key" should {
    "return 400 for a key with a '.' segment" in {
      val result =
        route(
          app,
          FakeRequest(PATCH, "/kv/a/./b").withJsonBody(JsNumber(1))
        ).value
      status(result) mustBe BAD_REQUEST
      (contentAsJson(result) \ "error").as[String] must include("'.' and '..'")
    }

    "return 400 for a key with a '..' segment" in {
      val result =
        route(
          app,
          FakeRequest(PATCH, "/kv/../secret").withJsonBody(JsNumber(1))
        ).value
      status(result) mustBe BAD_REQUEST
    }
  }

  // ---- PATCH --------------------------------------------------------------

  "PATCH /kv/:key" should {
    "create the key when absent and return value in the response" in {
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/new").withJsonBody(Json.obj("x" -> 1))
      ).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "key").as[String] mustBe "new"
      (json \ "value").as[JsObject] mustBe Json.obj("x" -> 1)
      (json \ "version").as[Long] mustBe 1L
    }

    "shallow-merge two objects and return merged value in the response" in {
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
      val json = contentAsJson(result)
      (json \ "key").as[String] mustBe "obj"
      (json \ "value")
        .as[JsObject] mustBe Json.obj("a" -> 1, "b" -> 99, "c" -> 3)
      (json \ "version").as[Long] mustBe 2L
    }

    "replace when delta is not an object and return new value in the response" in {
      route(app, FakeRequest(PUT, "/kv/num").withJsonBody(Json.obj("a" -> 1)))
      val result = route(
        app,
        FakeRequest(PATCH, "/kv/num").withJsonBody(JsNumber(7))
      ).value
      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "key").as[String] mustBe "num"
      (json \ "value").as[JsNumber] mustBe JsNumber(7)
      (json \ "version").as[Long] mustBe 2L
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
