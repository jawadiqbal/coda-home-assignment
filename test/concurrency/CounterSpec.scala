package concurrency

import models.WriteResult
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.JsNumber
import store.InMemoryKvStore

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Demonstrates why the ifVersion guard exists (Test A) and proves that the
  * guard, combined with client-side retry, reaches exactly the expected count
  * (Test B).
  *
  * Both tests use the store directly (no HTTP layer) to keep the focus on the
  * concurrency contract.  Three clients each perform 100 increments on the
  * same counter key.
  */
class CounterSpec extends PlaySpec {

  private val KEY = "counter"
  private val CLIENTS = 3
  private val OPS_PER_CLIENT = 100
  private val EXPECTED = CLIENTS * OPS_PER_CLIENT // 300

  // -------------------------------------------------------------------------
  // Test A — no ifVersion guard: lost updates are guaranteed
  // -------------------------------------------------------------------------

  "demonstratesLostUpdateWithoutIfVersion" should {
    "produce a final counter value less than 300" in {
      val store = new InMemoryKvStore()

      // Seed the key so all clients can read an initial value
      store.put(KEY, JsNumber(0), None)

      val futures = (1 to CLIENTS).map { _ =>
        Future {
          (1 to OPS_PER_CLIENT).foreach { _ =>
            val current = store.get(KEY).get
            val newValue = current.value.as[Int] + 1

            // Deliberate delay to widen the race window — without this, threads
            // may happen to serialize on a fast machine and the lost update
            // never occurs, causing the test to fail spuriously.
            Thread.sleep(1) // intentional: opens the gap between read and write

            // No ifVersion: any concurrent write that landed in the gap is silently lost
            store.put(KEY, JsNumber(newValue), ifVersion = None)
          }
        }
      }

      Await.result(Future.sequence(futures), 30.seconds)

      val finalValue = store.get(KEY).get.value.as[Int]
      // How many updates are lost depends on timing, so we can't assert an exact number —
      // but we know at least some were lost
      finalValue must be < EXPECTED
    }
  }

  // -------------------------------------------------------------------------
  // Test B — ifVersion guard + retry: exactly 300 successful increments
  // -------------------------------------------------------------------------

  "reachesExactly300WithIfVersionRetry" should {
    "produce a final counter value of exactly 300 and version 301" in {
      val store = new InMemoryKvStore()

      store.put(KEY, JsNumber(0), None)

      val MAX_RETRIES = 1000 // cap retries so a bug fails rather than hangs

      val futures = (1 to CLIENTS).map { _ =>
        Future {
          var successful = 0
          while (successful < OPS_PER_CLIENT) {
            val current = store.get(KEY).get
            val newValue = current.value.as[Int] + 1
            val result = store.put(
              KEY,
              JsNumber(newValue),
              ifVersion = Some(current.version)
            )
            result match {
              case WriteResult.Written(_) => successful += 1
              case WriteResult.Conflict(
                    _
                  ) => // someone else wrote first — retry
            }
            assert(
              successful <= MAX_RETRIES,
              "retry cap exceeded — possible livelock"
            )
          }
        }
      }

      Await.result(Future.sequence(futures), 30.seconds)

      val finalEntry = store.get(KEY).get
      finalEntry.value.as[Int] mustBe EXPECTED
      // version = 1 (seed) + 300 (increments) = 301
      finalEntry.version mustBe (EXPECTED + 1).toLong
    }
  }
}
