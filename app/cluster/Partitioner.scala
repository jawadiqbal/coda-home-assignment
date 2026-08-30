package cluster

import javax.inject.{Inject, Singleton}
import scala.util.hashing.MurmurHash3

/** Maps a key to the node that owns it.
  * All router processes must use the same
  * implementation and the same ordered NodeRegistry to agree on ownership.
  */
trait Partitioner {
  def ownerOf(key: String): NodeRef
}

/** Modulo partitioner: owner = nodes[ MurmurHash3(key) % N ] */
@Singleton
class ModuloPartitioner @Inject() (registry: NodeRegistry) extends Partitioner {

  override def ownerOf(key: String): NodeRef = {
    val idx = Math.floorMod(MurmurHash3.stringHash(key), registry.nodes.size)
    registry.nodes(idx)
  }
}
