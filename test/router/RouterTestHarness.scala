package router

import org.scalatestplus.play.PlaySpec
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.TestServer
import play.api.test.Helpers.await

import java.net.ServerSocket

/** Provides withCluster(nodeCount) { (routerUrl, nodeUrls) => ... }
  *
  * Staged startup (see plan section "Part 2 testing"):
  *   1. Reserve N free ports by opening and immediately closing a ServerSocket(0).
  *   2. Build + start node TestServers on those ports.
  *   3. Build the router Application with kv.nodes pointing at the live node URLs.
  *   4. Start the router TestServer.
  *   5. Run the test body.
  *   6. Stop router, then nodes.
  *
  * The WSClient is taken from the router application, so all HTTP calls go
  * through it and exercise the real WS stack.
  *
  * This trait is mixed into RouterIntegrationSpec; it is not a spec itself.
  */
trait RouterTestHarness { self: PlaySpec =>

  private var _wsClient: WSClient = _

  def wsClient: WSClient = _wsClient

  def withCluster(nodeCount: Int)(body: (String, Seq[String]) => Unit): Unit = {
    val nodePorts = (0 until nodeCount).map(_ => freePort())
    val nodeUrls  = nodePorts.map(p => s"http://localhost:$p")

    // Build node apps — all use the default (node) role
    val nodeApps: Seq[TestServer] = nodePorts.zipWithIndex.map { case (port, i) =>
      val nodeId = s"node-${i + 1}"
      val app = new GuiceApplicationBuilder()
        .configure(
          "kv.role"                       -> "node",
          "kv.nodeId"                     -> nodeId,
          "kv.nodes"                      -> nodeUrls.zipWithIndex.map { case (url, j) =>
            Map("id" -> s"node-${j + 1}", "url" -> url)
          },
          "play.http.parser.maxMemoryBuffer" -> "10m"
        )
        .build()
      TestServer(port, app)
    }
    nodeApps.foreach(_.start())

    // Build router app pointing at the live nodes
    val routerPort = freePort()
    val routerNodes = nodeUrls.zipWithIndex.map { case (url, i) =>
      Map("id" -> s"node-${i + 1}", "url" -> url)
    }
    val routerApp: Application = new GuiceApplicationBuilder()
      .configure(
        "kv.role"   -> "router",
        "kv.nodeId" -> "router",
        "kv.nodes"  -> routerNodes,
        "play.http.parser.maxMemoryBuffer" -> "10m"
      )
      .build()
    val routerServer = TestServer(routerPort, routerApp)
    routerServer.start()

    _wsClient = routerApp.injector.instanceOf[WSClient]

    try {
      body(s"http://localhost:$routerPort", nodeUrls)
    } finally {
      routerServer.stop()
      nodeApps.foreach(_.stop())
    }
  }

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort finally s.close()
  }
}
