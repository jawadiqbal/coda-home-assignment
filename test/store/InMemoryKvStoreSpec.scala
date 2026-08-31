package store

import models.{VersionedValue, WriteResult}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json._

class InMemoryKvStoreSpec extends PlaySpec {

  // Fresh store for each test via a helper — no shared state between examples
  def freshStore: InMemoryKvStore = new InMemoryKvStore()

  "get" should {
    "return None for a missing key" in {
      freshStore.get("absent") mustBe None
    }
  }

  "put" should {
    "create a key with version 1" in {
      val store = freshStore
      store.put("k", JsNumber(42), ifVersion = None) mustBe WriteResult.Written(
        VersionedValue(JsNumber(42), 1L)
      )
      store.get("k") mustBe Some(VersionedValue(JsNumber(42), 1L))
    }

    "replace an existing value and increment the version" in {
      val store = freshStore
      store.put("k", JsString("first"), None)
      store.put("k", JsString("second"), None) mustBe WriteResult.Written(
        VersionedValue(JsString("second"), 2L)
      )
    }

    "succeed when ifVersion matches" in {
      val store = freshStore
      store.put("k", JsString("v1"), None)
      store.put("k", JsString("v2"), ifVersion = Some(1L)) mustBe WriteResult
        .Written(VersionedValue(JsString("v2"), 2L))
    }

    "return Conflict when ifVersion does not match" in {
      val store = freshStore
      store.put("k", JsString("v1"), None)
      val result = store.put("k", JsString("v2"), ifVersion = Some(99L))
      result mustBe WriteResult.Conflict(Some(1L))
    }

    "return Conflict when ifVersion is supplied but the key is absent" in {
      val result =
        freshStore.put("missing", JsString("x"), ifVersion = Some(1L))
      result mustBe WriteResult.Conflict(None)
    }
  }

  "patch" should {
    "create the key with delta when the key is absent" in {
      val store = freshStore
      val delta = Json.obj("a" -> 1)
      store.patch("k", delta, None) mustBe WriteResult.Written(
        VersionedValue(delta, 1L)
      )
    }

    "shallow-merge two JsObjects (right-side wins on collision)" in {
      val store = freshStore
      store.put("k", Json.obj("a" -> 1, "b" -> 2), None)
      val result = store.patch("k", Json.obj("b" -> 99, "c" -> 3), None)
      result mustBe WriteResult.Written(
        VersionedValue(Json.obj("a" -> 1, "b" -> 99, "c" -> 3), 2L)
      )
    }

    "replace when the delta is not a JsObject" in {
      val store = freshStore
      store.put("k", Json.obj("a" -> 1), None)
      val result = store.patch("k", JsNumber(7), None)
      result mustBe WriteResult.Written(VersionedValue(JsNumber(7), 2L))
    }

    "replace when the existing value is not a JsObject" in {
      val store = freshStore
      store.put("k", JsNumber(1), None)
      val result = store.patch("k", Json.obj("a" -> 1), None)
      result mustBe WriteResult.Written(VersionedValue(Json.obj("a" -> 1), 2L))
    }

    "succeed when ifVersion matches" in {
      val store = freshStore
      store.put("k", Json.obj("x" -> 1), None)
      val result = store.patch("k", Json.obj("y" -> 2), ifVersion = Some(1L))
      result mustBe WriteResult.Written(
        VersionedValue(Json.obj("x" -> 1, "y" -> 2), 2L)
      )
    }

    "return Conflict when ifVersion does not match" in {
      val store = freshStore
      store.put("k", Json.obj("x" -> 1), None)
      store.patch(
        "k",
        Json.obj("y" -> 2),
        ifVersion = Some(99L)
      ) mustBe WriteResult.Conflict(Some(1L))
    }

    "still bump the version when patching with empty object" in {
      // A write was accepted even if no fields changed — version must advance
      val store = freshStore
      store.put("k", Json.obj("a" -> 1), None)
      val result = store.patch("k", Json.obj(), None)
      result mustBe WriteResult.Written(VersionedValue(Json.obj("a" -> 1), 2L))
    }
  }

  "keys" should {
    "be empty on a fresh store" in {
      freshStore.keys().toSet mustBe empty
    }

    "reflect all inserted keys" in {
      val store = freshStore
      store.put("x", JsNull, None)
      store.put("y", JsNull, None)
      store.keys().toSet mustBe Set("x", "y")
    }
  }
}
