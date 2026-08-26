package cluster

import javax.inject.{Inject, Singleton}
import scala.util.hashing.MurmurHash3

/** Maps a key to the node that owns it.
  * All processes that route (routers, smart clients) must use the same
  * implementation and the same ordered NodeRegistry to agree on ownership.
  */
trait Partitioner {
  def ownerOf(key: String): NodeRef
}

/** Modulo partitioner: owner = nodes[ floorMod(MurmurHash3(key), N) ]
  *
  * Properties:
  *   - Deterministic: same key always maps to the same node while N is fixed.
  *   - Even distribution: MurmurHash3 has good avalanche behaviour.
  *   - Simple: no extra state; one function call.
  *
  * Limitation: changing N remaps ~(N-1)/N of all keys.  Consistent hashing
  * reduces that to ~1/N and is the Part 3 roadmap item.  The trait exists here
  * so swapping the implementation requires no change to callers.
  */
@Singleton
class ModuloPartitioner @Inject() (registry: NodeRegistry) extends Partitioner {

  override def ownerOf(key: String): NodeRef = {
    val idx = Math.floorMod(MurmurHash3.stringHash(key), registry.nodes.size)
    registry.nodes(idx)
  }
}
