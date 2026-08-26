package controllers

import javax.inject.{Inject, Named, Singleton}
import play.api.libs.json.JsValue
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
  */
@Singleton
class KvDispatcher @Inject() (@Named("kvHandler") handler: KvHandler)
    extends InjectedController {

  def get(key: String): Action[AnyContent]  = handler.get(key)
  def put(key: String): Action[JsValue]     = handler.put(key)
  def patch(key: String): Action[JsValue]   = handler.patch(key)
  def listAll(): Action[AnyContent]         = handler.listAll()
}
