package controllers

import javax.inject.{Inject, Named, Singleton}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._

/** Routes /kv requests to either the local KvController (node role) or the
  * RouterController (router role).
  *
  * The concrete handler is bound in Module based on kv.role config.  Callers
  * never know which role they are talking to — this is the standard Play
  * delegating-controller pattern.
  *
  * Naming the binding with @Named("kvHandler") avoids ambiguity: both
  * KvController and RouterController extend AbstractController which also
  * injects ControllerComponents, so plain type-based binding would cause a
  * double-binding of ControllerComponents.
  *
  * Key validation:
  *   Keys that contain a "." or ".." path segment are rejected with 400.
  *   A segment is defined by splitting on "/".  This prevents path-traversal
  *   payloads regardless of how downstream storage or routing uses the key.
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

  // ---------------------------------------------------------------------------

  /** Returns the inner action unchanged when the key is valid; otherwise
    * returns a constant 400 action without touching the handler.
    *
    * Splitting on "/" covers both "." (single-dot segment) and ".." without
    * needing a regex or URL-decoding step — Akka HTTP already decoded the
    * path before Play received it.
    */
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
