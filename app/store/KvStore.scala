package store

import models.{VersionedValue, WriteResult}
import play.api.libs.json.JsValue

/** Storage interface. */
trait KvStore {

  /** Returns the current value and version for key, or None if absent. */
  def get(key: String): Option[VersionedValue]

  /** Replaces the value for key unconditionally, or only if the current
    * version matches ifVersion when supplied.
    * Creates the key if absent (version will be 1).
    * Returns Conflict if ifVersion is supplied but does not match,
    * including when the key does not exist.
    */
  def put(key: String, value: JsValue, ifVersion: Option[Long]): WriteResult

  /** Creates the key with delta if absent, or merges delta into the existing
    * value (shallow merge if both are JsObjects, replace otherwise).
    * Respects the same ifVersion semantics as put.
    */
  def patch(key: String, delta: JsValue, ifVersion: Option[Long]): WriteResult

  /** Weakly-consistent snapshot of current keys. Safe under concurrent
    * modifications.
    */
  def keys(): Iterator[String]
}
