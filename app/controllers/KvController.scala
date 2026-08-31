package controllers

import http.KvHeaders
import javax.inject.{Inject, Singleton}
import models.WriteResult
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import store.KvStore

import scala.util.Try

/** REST API for the single-node KV store (node role).
  *
  * Implements KvHandler so it can be bound to @Named("kvHandler") by Module
  * when kv.role = node, letting KvDispatcher delegate to it.
  */
@Singleton
class KvController @Inject() (store: KvStore, cc: ControllerComponents)
    extends AbstractController(cc)
    with KvHandler {

  private val logger = Logger(getClass)

  def listAll(): Action[AnyContent] = Action {
    NotImplemented(
      Json.obj("error" -> "listAll is only available on a router process")
    )
  }

  def get(key: String): Action[AnyContent] = Action {
    store.get(key) match {
      case Some(vv) =>
        logger.info(s"GET key=$key version=${vv.version}")
        Ok(Json.obj("key" -> key, "value" -> vv.value, "version" -> vv.version))
      case None =>
        NotFound(Json.obj("error" -> "key not found", "key" -> key))
    }
  }

  def put(key: String): Action[JsValue] = Action(parse.json) { request =>
    withIfVersion(request) { ifVersion =>
      store.put(key, request.body, ifVersion) match {
        case WriteResult.Written(vv) =>
          logger.info(s"PUT key=$key version=${vv.version}")
          Ok(
            Json.obj("key" -> key, "value" -> vv.value, "version" -> vv.version)
          )
        case WriteResult.Conflict(current) =>
          conflictResponse(current)
      }
    }
  }

  def patch(key: String): Action[JsValue] = Action(parse.json) { request =>
    withIfVersion(request) { ifVersion =>
      store.patch(key, request.body, ifVersion) match {
        case WriteResult.Written(vv) =>
          logger.info(s"PATCH key=$key version=${vv.version}")
          Ok(
            Json.obj("key" -> key, "value" -> vv.value, "version" -> vv.version)
          )
        case WriteResult.Conflict(current) =>
          conflictResponse(current)
      }
    }
  }

  // Parses ifVersion from query param (preferred) or kv-if-version header.
  // Calls f with the parsed value, or returns 400 if the string is non-numeric.
  private def withIfVersion(
      request: Request[_]
  )(f: Option[Long] => Result): Result = {
    val raw = request
      .getQueryString("ifVersion")
      .orElse(KvHeaders.ifVersion(request.headers))

    raw match {
      case None =>
        f(None)
      case Some(s) =>
        Try(s.toLong).toOption match {
          case Some(v) => f(Some(v))
          case None =>
            BadRequest(
              Json.obj(
                "error" -> s"invalid ifVersion '$s': must be a long integer"
              )
            )
        }
    }
  }

  private def conflictResponse(currentVersion: Option[Long]): Result =
    currentVersion match {
      case Some(cv) =>
        Conflict(
          Json.obj("error" -> "version conflict", "currentVersion" -> cv)
        )
      case None =>
        Conflict(Json.obj("error" -> "version conflict — key does not exist"))
    }
}
