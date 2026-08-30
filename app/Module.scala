import cluster.{ModuloPartitioner, NodeRegistry, Partitioner}
import com.google.inject.AbstractModule
import com.google.inject.name.Names
import controllers.{KvController, KvHandler, RouterController}
import play.api.{Configuration, Environment}
import store.{InMemoryKvStore, KvStore}

/** Guice module.  Binds implementations based on kv.role config.
  * WS (WSClient) and ControllerComponents are provided by Play's built-in modules
  */
class Module(environment: Environment, configuration: Configuration)
    extends AbstractModule {

  override def configure(): Unit = {
    // Always bind the store (router allocates an empty map it ignores, which is
    // cheap and keeps the DI graph consistent for tests that reuse this module).
    bind(classOf[KvStore]).to(classOf[InMemoryKvStore]).asEagerSingleton()

    val role = configuration.getOptional[String]("kv.role").getOrElse("node")

    if (role == "router") {
      bind(classOf[NodeRegistry]).asEagerSingleton()
      bind(classOf[Partitioner]).to(classOf[ModuloPartitioner])
      bind(classOf[KvHandler])
        .annotatedWith(Names.named("kvHandler"))
        .to(classOf[RouterController])
    } else {
      bind(classOf[KvHandler])
        .annotatedWith(Names.named("kvHandler"))
        .to(classOf[KvController])
    }
  }
}
