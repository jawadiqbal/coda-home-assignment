package cluster

import javax.inject.{Inject, Singleton}
import play.api.Configuration

/** Reads the kv.nodes list from application.conf and makes it available to the
  * partitioner and router.
  *
  * Config shape:
  *   kv.nodes = [
  *     { id = "node-1", url = "http://localhost:7001" },
  *     { id = "node-2", url = "http://localhost:7002" },
  *     { id = "node-3", url = "http://localhost:7003" }
  *   ]
  *
  * Ordering is significant: ModuloPartitioner maps hash(key) % nodes.size to a
  * list index.  The same ordered list must be in every process that routes, so
  * all of them agree on ownership.
  *
  * Two constructors:
  *   - @Inject primary: built by Guice from Configuration.
  *   - Secondary (nodes: Seq[NodeRef]): used directly in unit tests,
  *     bypassing the Play Configuration object entirely.
  */
@Singleton
class NodeRegistry @Inject() (config: Configuration) {

  val nodes: Seq[NodeRef] = {
    val raw = config.get[Seq[Configuration]]("kv.nodes")
    raw.map { c =>
      NodeRef(
        id = c.get[String]("id"),
        url = c.get[String]("url").stripSuffix("/")
      )
    }
  }

  require(nodes.nonEmpty, "kv.nodes must contain at least one entry")
}

object NodeRegistry {

  /** Convenience factory for tests — avoids needing a real Configuration. */
  def forTest(refs: NodeRef*): NodeRegistry = {
    // Build a minimal in-memory Configuration that satisfies the @Inject constructor.
    // Importing com.typesafe.config here keeps the dependency on the companion only.
    import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
    import scala.collection.JavaConverters._
    import play.api.Configuration

    val nodeList = refs
      .map { r =>
        Map("id" -> r.id, "url" -> r.url).asJava
      }
      .toList
      .asJava

    val raw = ConfigFactory
      .empty()
      .withValue("kv.nodes", ConfigValueFactory.fromIterable(nodeList))
    new NodeRegistry(Configuration(raw))
  }
}
