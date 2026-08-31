package router

import org.scalatestplus.play.PlaySpec
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.WSClient
import play.api.test.TestServer
import play.api.test.Helpers.await

import java.io.PrintWriter
import java.net.{ServerSocket, Socket}
import java.util.concurrent.atomic.AtomicBoolean

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
  */
trait RouterTestHarness { self: PlaySpec =>

  private var _wsClient: WSClient = _

  def wsClient: WSClient = _wsClient

  def withCluster(nodeCount: Int)(body: (String, Seq[String]) => Unit): Unit = {
    val nodePorts = (0 until nodeCount).map(_ => freePort())
    val nodeUrls = nodePorts.map(p => s"http://localhost:$p")

    // Build node apps — all use the default (node) role
    val nodeApps: Seq[TestServer] = nodePorts.zipWithIndex.map {
      case (port, i) =>
        val nodeId = s"node-${i + 1}"
        val app = new GuiceApplicationBuilder()
          .configure(
            "kv.role" -> "node",
            "kv.nodeId" -> nodeId,
            "kv.nodes" -> nodeUrls.zipWithIndex.map { case (url, j) =>
              Map("id" -> s"node-${j + 1}", "url" -> url)
            }
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
        "kv.role" -> "router",
        "kv.nodeId" -> "router",
        "kv.nodes" -> routerNodes
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

  /** One live storage node plus one dead URL in the router's ring.
    * Used to prove GET /kv still returns keys from the live node.
    */
  def withLiveAndDeadNode(body: (String, String) => Unit): Unit = {
    val livePort = freePort()
    val deadPort = freePort()
    val liveUrl = s"http://localhost:$livePort"
    val deadUrl = s"http://localhost:$deadPort"
    val nodes = Seq(
      Map("id" -> "node-1", "url" -> liveUrl),
      Map("id" -> "node-2", "url" -> deadUrl)
    )

    val nodeApp = new GuiceApplicationBuilder()
      .configure(
        "kv.role" -> "node",
        "kv.nodeId" -> "node-1",
        "kv.nodes" -> nodes
      )
      .build()
    val nodeServer = TestServer(livePort, nodeApp)
    nodeServer.start()

    val routerPort = freePort()
    val routerApp: Application = new GuiceApplicationBuilder()
      .configure(
        "kv.role" -> "router",
        "kv.nodeId" -> "router",
        "kv.nodes" -> nodes
      )
      .build()
    val routerServer = TestServer(routerPort, routerApp)
    routerServer.start()
    _wsClient = routerApp.injector.instanceOf[WSClient]

    try {
      body(s"http://localhost:$routerPort", liveUrl)
    } finally {
      routerServer.stop()
      nodeServer.stop()
    }
  }

  /** Starts a router pointing at nodeCount real nodes, then also gives back a
    * URL for an unreachable node that is NOT in the cluster — used to test 503.
    * The unreachable URL's port is reserved but never bound, so any connection
    * attempt fails immediately with "connection refused".
    */
  def withClusterAndDeadNode(nodeCount: Int)(
      body: (String, String) => Unit
  ): Unit = {
    val deadPort = freePort() // port is freed immediately; nothing will bind it
    val deadUrl = s"http://localhost:$deadPort"

    // Build a 1-node router that only points at the dead URL, so every proxied
    // request hits the nodeDown path.
    val routerPort = freePort()
    val routerApp: Application = new GuiceApplicationBuilder()
      .configure(
        "kv.role" -> "router",
        "kv.nodeId" -> "router",
        "kv.nodes" -> Seq(Map("id" -> "dead-node", "url" -> deadUrl))
      )
      .build()
    val routerServer = TestServer(routerPort, routerApp)
    routerServer.start()
    _wsClient = routerApp.injector.instanceOf[WSClient]

    try {
      body(s"http://localhost:$routerPort", deadUrl)
    } finally {
      routerServer.stop()
    }
  }

  /** Starts a minimal raw TCP server that accepts one HTTP connection and
    * responds with a fixed plain-text body (not JSON).  Used to exercise the
    * relayResponse non-JSON fallback path.
    *
    * Returns the URL and a shutdown handle.
    */
  def withPlainTextNode(statusCode: Int, textBody: String)(
      body: String => Unit
  ): Unit = {
    val ss = new ServerSocket(0)
    val port = ss.getLocalPort
    val running = new AtomicBoolean(true)

    val acceptThread = new Thread(() => {
      while (running.get()) {
        try {
          val conn: Socket = ss.accept()
          val in = conn.getInputStream
          val headerBuf = new StringBuilder
          while (!headerBuf.toString.contains("\r\n\r\n")) {
            val b = in.read()
            if (b < 0) throw new java.io.EOFException()
            headerBuf.append(b.toChar)
          }
          val out = new PrintWriter(conn.getOutputStream, true)
          out.print(s"HTTP/1.1 $statusCode OK\r\n")
          out.print("Content-Type: text/plain\r\n")
          out.print(s"Content-Length: ${textBody.length}\r\n")
          out.print("Connection: close\r\n")
          out.print("\r\n")
          out.print(textBody)
          out.flush()
          conn.close()
        } catch {
          case _: Exception => // socket closed on shutdown
        }
      }
    })
    acceptThread.setDaemon(true)
    acceptThread.start()

    // Build a router pointing at the plain-text TCP server
    val routerPort = freePort()
    val routerApp: Application = new GuiceApplicationBuilder()
      .configure(
        "kv.role" -> "router",
        "kv.nodeId" -> "router",
        "kv.nodes" -> Seq(
          Map("id" -> "plain-node", "url" -> s"http://localhost:$port")
        )
      )
      .build()
    val routerServer = TestServer(routerPort, routerApp)
    routerServer.start()
    _wsClient = routerApp.injector.instanceOf[WSClient]

    try {
      body(s"http://localhost:$routerPort")
    } finally {
      routerServer.stop()
      running.set(false)
      ss.close()
    }
  }

  private def freePort(): Int = {
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()
  }
}
