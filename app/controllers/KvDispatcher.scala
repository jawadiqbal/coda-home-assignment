package controllers

import javax.inject.{Inject, Named, Singleton}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._

/** Front-facing route interface that dispatches requests to concrete handlers
  *
  * Key validation:
  *   Keys that contain a "." or ".." path segment are rejected with 400.
  *   A segment is defined by splitting on "/". This prevents path-traversal
  *   payloads regardless of how downstream storage or routing uses the key,
  *    thus prevents path compression attacks.
  */
@Singleton
class KvDispatcher @Inject() (
    @Named("kvHandler") handler: KvHandler,
    cc: ControllerComponents
) extends AbstractController(cc) {

  def get(key: String): Action[AnyContent] =
    validated(key)(handler.get(key))

  def put(key: String): Action[JsValue] =
    validated(key)(handler.put(key))

  def patch(key: String): Action[JsValue] =
    validated(key)(handler.patch(key))

  def listAll(): Action[AnyContent] = handler.listAll()

  private def validated[A](key: String)(action: => Action[A]): Action[A] = {
    val segments = key.split("/", -1)
    val hasDotSegment = segments.exists(s => s == "." || s == "..")
    if (hasDotSegment)
      Action(action.parser) { _ =>
        BadRequest(
          Json.obj(
            "error" -> "invalid key: '.' and '..' path segments are not allowed",
            "key" -> key
          )
        )
      }
    else
      action
  }
}
