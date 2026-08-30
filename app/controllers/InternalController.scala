package controllers

import akka.stream.scaladsl.Source
import akka.util.ByteString
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.{
  AbstractController,
  Action,
  AnyContent,
  ControllerComponents
}
import store.KvStore

/** Node-side internal endpoint, called only by the router and tests.
  *
  * GET /internal/keys
  *   Streams all keys owned by this node as newline-delimited JSON.
  *   Each line: {"key":"<k>","node":"<nodeId>"}
  *   Returns 404 when kv.role is router
  *
  * The key iterator is weakly consistent (never throws under concurrent
  * modification, but not a point-in-time snapshot).
  *
  * Play 2.8 / Akka Streams: Source.fromIterator wraps a by-name iterator so the
  * iterator is created fresh on each materialisation, which prevents the common
  * bug of passing an already-exhausted iterator.
  */
@Singleton
class InternalController @Inject() (
    store: KvStore,
    config: Configuration,
    cc: ControllerComponents
) extends AbstractController(cc) {

  private val nodeId: String = config.get[String]("kv.nodeId")
  private val isRouter: Boolean =
    config.getOptional[String]("kv.role").contains("router")

  def keys(): Action[AnyContent] = Action {
    if (isRouter) {
      NotFound(
        Json.obj(
          "error" -> "/internal/keys is only available on a storage node"
        )
      )
    } else {
      val src = Source
        .fromIterator(() => store.keys())
        .map { k =>
          val line = Json.stringify(Json.obj("key" -> k, "node" -> nodeId))
          ByteString(line + "\n")
        }

      Ok.chunked(src).as("application/x-ndjson")
    }
  }
}
