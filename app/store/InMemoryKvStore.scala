package store

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import javax.inject.Singleton
import models.{VersionedValue, WriteResult}
import play.api.libs.json.{JsObject, JsValue}

import scala.collection.JavaConverters._

/** Single-node in-memory KV store.
  *
  * Concurrency model: ConcurrentHashMap.compute holds the bin lock for the
  * duration of the remapping function, serialising all operations on the same
  * key while allowing operations on different keys to proceed in parallel.
  * The entire read-check-write of the ifVersion guard therefore happens
  * atomically with no separate get-then-put.
  *
  * Constraints on the compute lambda (both satisfied here):
  *   - must complete quickly and must not block
  *   - must not re-enter the map (would deadlock on the same bin)
  *
  * The @Singleton annotation means Guice creates one instance per injector,
  * which in production is one instance per process — the correct shard-per-node
  * model for Part 2.  Do not make this a Scala object; that would give a single
  * JVM-wide map, breaking the in-process multi-node test harness.
  */
@Singleton
class InMemoryKvStore extends KvStore {

  private val map = new ConcurrentHashMap[String, VersionedValue]()

  override def get(key: String): Option[VersionedValue] =
    Option(map.get(key))

  override def put(
      key: String,
      value: JsValue,
      ifVersion: Option[Long]
  ): WriteResult =
    mutate(key, ifVersion)(_ => value)

  override def patch(
      key: String,
      delta: JsValue,
      ifVersion: Option[Long]
  ): WriteResult =
    mutate(key, ifVersion) {
      case Some(current) => merge(current.value, delta)
      case None          => delta
    }

  override def keys(): Iterator[String] =
    map.keySet().iterator().asScala

  // Shallow merge: if both sides are JsObjects, right-side fields win.
  // In all other combinations (one side is an array, number, string, etc.)
  // the delta replaces the existing value outright.
  // Play's JsObject.++ is shallow by design; deepMerge would be wrong here.
  private def merge(existing: JsValue, delta: JsValue): JsValue =
    (existing, delta) match {
      case (e: JsObject, d: JsObject) => e ++ d
      case _                          => delta
    }

  // Single entry point for all writes.  The lambda runs inside the bin lock,
  // so it must be short and non-blocking.  An AtomicReference carries the
  // result out of the lambda since compute's return value is the new map entry.
  private def mutate(
      key: String,
      ifVersion: Option[Long]
  )(next: Option[VersionedValue] => JsValue): WriteResult = {
    val outcome = new AtomicReference[WriteResult]()

    map.compute(
      key,
      (_: String, current: VersionedValue) => {
        val cur = Option(current)
        ifVersion match {
          case Some(v) if !cur.exists(_.version == v) =>
            // Version supplied but doesn't match (including key-absent case).
            outcome.set(WriteResult.Conflict(cur.map(_.version)))
            current // leave the mapping unchanged

          case _ =>
            val newVersion = cur.map(_.version + 1).getOrElse(1L)
            val vv = VersionedValue(next(cur), newVersion)
            outcome.set(WriteResult.Written(vv))
            vv
        }
      }
    )

    outcome.get()
  }
}
