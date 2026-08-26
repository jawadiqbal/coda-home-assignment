package router

import cluster.{NodeRef, NodeRegistry}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerTest
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import play.api.test.Helpers._

/** End-to-end integration tests for the router topology.
  *
  * Test strategy (from the plan):
  *   Each test spins up N node applications + 1 router application in-process,
  *   using ephemeral ports.  All apps run inside the same sbt test JVM so there
  *   is no Docker dependency.
  *
  * The staged startup dance:
  *   1. Start node TestServers (they each pick their own port).
  *   2. Build the router Application with config pointing at the live node URLs.
  *   3. Start the router TestServer.
  *
  * All servers are stopped in afterEach via GuiceOneServerPerTest on the router,
  * while nodes are stopped manually in their own afterEach logic captured below.
  *
  * The tests below use a simplified 2-node cluster to keep boot time short.
  *
  * NOTE: These tests require a running WS client, which is available via the
  * injected WSClient from the router application.  Node apps also expose a WS
  * client, but we drive everything through the router.
  */
class RouterIntegrationSpec extends PlaySpec with RouterTestHarness {

  "Router" should {

    "PUT a key on the correct node and GET it back through the router" in
      withCluster(nodeCount = 2) { (routerUrl, _) =>
        val ws = wsClient

        val put = await(ws.url(s"$routerUrl/kv/hello").put(Json.obj("greeting" -> "world")))
        put.status mustBe OK

        val get = await(ws.url(s"$routerUrl/kv/hello").get())
        get.status mustBe OK
        (get.json \ "value").as[JsObject] mustBe Json.obj("greeting" -> "world")
      }

    "return 409 when ifVersion does not match through the router" in
      withCluster(nodeCount = 2) { (routerUrl, _) =>
        val ws = wsClient

        await(ws.url(s"$routerUrl/kv/vkey").put(Json.obj("x" -> 1)))
        val conflict = await(
          ws.url(s"$routerUrl/kv/vkey")
            .addQueryStringParameters("ifVersion" -> "99")
            .put(Json.obj("x" -> 2))
        )
        conflict.status mustBe CONFLICT
      }

    "PATCH a key through the router using shallow merge" in
      withCluster(nodeCount = 2) { (routerUrl, _) =>
        val ws = wsClient

        await(ws.url(s"$routerUrl/kv/pkey").put(Json.obj("a" -> 1, "b" -> 2)))
        await(ws.url(s"$routerUrl/kv/pkey").patch(Json.obj("b" -> 99, "c" -> 3)))

        val get = await(ws.url(s"$routerUrl/kv/pkey").get())
        get.status mustBe OK
        val v = (get.json \ "value").as[JsObject]
        (v \ "a").as[Int] mustBe 1
        (v \ "b").as[Int] mustBe 99
        (v \ "c").as[Int] mustBe 3
      }

    "return the union of keys from all nodes via GET /kv" in
      withCluster(nodeCount = 2) { (routerUrl, nodeUrls) =>
        val ws = wsClient

        // Write to both nodes to ensure at least one key per node
        // We write enough keys that the modulo partitioner will spread them.
        val keys = (0 until 20).map(i => s"distributed-$i")
        keys.foreach { k =>
          await(ws.url(s"$routerUrl/kv/$k").put(Json.obj("i" -> k)))
        }

        val resp = await(ws.url(s"$routerUrl/kv").get())
        resp.status mustBe OK
        resp.contentType must include("ndjson")

        // Parse NDJSON lines and collect all returned keys
        val lines = resp.body.split("\n").filter(_.trim.nonEmpty)
        val returnedKeys = lines.map { line =>
          (Json.parse(line) \ "key").as[String]
        }.toSet

        keys.foreach { k => returnedKeys must contain(k) }
      }

    "reach exactly 300 after 3×100 concurrent increments through the router" in
      withCluster(nodeCount = 2) { (routerUrl, _) =>
        val ws  = wsClient
        val key = "counter-through-router"

        def increment(): Unit = {
          @annotation.tailrec
          def loop(): Unit = {
            val get = await(ws.url(s"$routerUrl/kv/$key").get())
            val (currentValue, ifV) = get.status match {
              case NOT_FOUND => (0, None)
              case OK =>
                val v = (get.json \ "value").as[Int]
                val ver = (get.json \ "version").as[Long]
                (v, Some(ver))
              case s => fail(s"unexpected GET status $s")
            }
            val body = Json.obj("value" -> (currentValue + 1))
            val req  = ifV.fold(ws.url(s"$routerUrl/kv/$key")) { v =>
              ws.url(s"$routerUrl/kv/$key").addQueryStringParameters("ifVersion" -> v.toString)
            }
            val put = await(req.put(body))
            if (put.status == CONFLICT) loop() else ()
          }
          loop()
        }

        val threads = (0 until 3).map(_ => new Thread(() => (0 until 100).foreach(_ => increment())))
        threads.foreach(_.start())
        threads.foreach(_.join(30000L))

        val get = await(ws.url(s"$routerUrl/kv/$key").get())
        get.status mustBe OK
        (get.json \ "value").as[Int] mustBe 300
      }
  }
}
