package models

import play.api.libs.json.JsValue

/** A stored value with a monotonically increasing version counter.
  * Version starts at 1 and increments by 1 on every accepted write.
  * No history is retained — only the current snapshot.
  *
  * Immutability is intentional: ConcurrentHashMap's volatile semantics
  * guarantee that a reader sees either the previous or the next VersionedValue,
  * never a partially constructed one.  If this class held a mutable field that
  * guarantee would break.
  */
final case class VersionedValue(value: JsValue, version: Long)
