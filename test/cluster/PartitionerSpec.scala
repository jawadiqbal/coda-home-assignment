package cluster

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Tests for ModuloPartitioner.
  *
  * Test A — Determinism: same key always maps to same node (critical for
  *   correctness; if this ever changes a GET after a PUT would hit the wrong node).
  *
  * Test B — Distribution: 10 000 synthetic keys land within a reasonable band
  *   across 3 nodes.  We use ±30% tolerance (expect ~3333 per node, accept
  *   2333–4333).  This would catch a degenerate hash function but is not a
  *   strict uniformity proof.
  *
  * Test C — Single-node cluster: all keys route to the one node.
  */
class PartitionerSpec extends AnyWordSpec with Matchers {

  private def threeNodeRegistry(): NodeRegistry =
    NodeRegistry.forTest(
      NodeRef("node-1", "http://localhost:7001"),
      NodeRef("node-2", "http://localhost:7002"),
      NodeRef("node-3", "http://localhost:7003")
    )

  "ModuloPartitioner" should {

    "deterministically route the same key to the same node" in {
      val partitioner = new ModuloPartitioner(threeNodeRegistry())
      val key = "some-deterministic-key"
      val first = partitioner.ownerOf(key)
      (1 to 100).foreach { _ =>
        partitioner.ownerOf(key) mustBe first
      }
    }

    "distribute 10 000 keys roughly evenly across 3 nodes" in {
      val partitioner = new ModuloPartitioner(threeNodeRegistry())
      val keys = (0 until 10000).map(i => s"key-$i")
      val counts = keys.groupBy(partitioner.ownerOf(_).id).mapValues(_.size)

      val n = 3
      val perNode = 10000 / n
      val tolerance = (perNode * 0.30).toInt
      counts.values.foreach { c =>
        c mustBe >=(perNode - tolerance)
        c mustBe <=(perNode + tolerance)
      }
    }

    "route all keys to the single node in a 1-node cluster" in {
      val registry =
        NodeRegistry.forTest(NodeRef("only-node", "http://localhost:7001"))
      val partitioner = new ModuloPartitioner(registry)
      (0 until 100).foreach { i =>
        partitioner.ownerOf(s"key-$i").id mustBe "only-node"
      }
    }
  }
}
