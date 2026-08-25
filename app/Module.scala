import com.google.inject.AbstractModule
import store.{InMemoryKvStore, KvStore}

/** Guice module for Part 1.
  *
  * Binding is eager so that:
  *   - construction failures surface at boot, not on the first request
  *   - the map is allocated once, at a predictable time
  *
  * The router process also loads this module (same build, role chosen by
  * config), but allocating an empty map it never uses is harmless.
  * If that bothers you, switch to a Module that checks kv.role before binding.
  */
class Module extends AbstractModule {
  override def configure(): Unit =
    bind(classOf[KvStore]).to(classOf[InMemoryKvStore]).asEagerSingleton()
}
