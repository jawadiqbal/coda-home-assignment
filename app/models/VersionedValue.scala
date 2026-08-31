package models

import play.api.libs.json.JsValue

/** A stored value with a monotonically increasing version counter.
  * Version starts at 1 and increments by 1 on every accepted write.
  *
  * Immutability is to ensure user never sees a partially constructed value.
  */
final case class VersionedValue(value: JsValue, version: Long)
