package controllers

import play.api.libs.json.JsValue
import play.api.mvc.{Action, AnyContent}

/** Common interface for the two role implementations.
  *
  * KvController implements this for the node role (local store).
  * RouterController implements this for the router role (proxy to nodes).
  *
  * Module binds the correct implementation to @Named("kvHandler").
  */
trait KvHandler {
  def get(key: String): Action[AnyContent]
  def put(key: String): Action[JsValue]
  def patch(key: String): Action[JsValue]
  def listAll(): Action[AnyContent]
}
