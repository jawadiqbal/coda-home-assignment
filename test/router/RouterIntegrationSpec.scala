package router

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.{JsNumber, JsObject, Json}
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

        val put = await(
          ws.url(s"$routerUrl/kv/hello").put(Json.obj("greeting" -> "world"))
        )
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
        await(
          ws.url(s"$routerUrl/kv/pkey").patch(Json.obj("b" -> 99, "c" -> 3))
        )

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

    "still return keys from live nodes when one node is unreachable" in
      withLiveAndDeadNode { (routerUrl, _) =>
        val ws = wsClient

        val written = (0 until 20).flatMap { i =>
          val k = s"partial-$i"
          val put = await(ws.url(s"$routerUrl/kv/$k").put(Json.obj("i" -> k)))
          if (put.status == OK) Some(k) else None
        }
        written must not be empty

        val resp = await(ws.url(s"$routerUrl/kv").get())
        resp.status mustBe OK
        val lines = resp.body.split("\n").filter(_.trim.nonEmpty)
        val returnedKeys = lines.map { line =>
          (Json.parse(line) \ "key").as[String]
        }.toSet

        written.foreach { k => returnedKeys must contain(k) }
      }

    "skip a node that returns HTTP 500 from /internal/keys" in
      withPlainTextNode(statusCode = 500, textBody = "<html>error</html>") {
        routerUrl =>
          val ws = wsClient
          val resp = await(ws.url(s"$routerUrl/kv").get())
          resp.status mustBe OK
          resp.body.split("\n").filter(_.trim.nonEmpty) mustBe empty
      }

    "return 400 for a non-numeric ifVersion on PUT through the router" in
      withCluster(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        val result = await(
          ws.url(s"$routerUrl/kv/badver")
            .addQueryStringParameters("ifVersion" -> "not-a-number")
            .put(Json.obj("x" -> 1))
        )
        result.status mustBe BAD_REQUEST
        (result.json \ "error").as[String] must include("invalid ifVersion")
      }

    "return 400 for a non-numeric ifVersion on PATCH through the router" in
      withCluster(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        val result = await(
          ws.url(s"$routerUrl/kv/badver")
            .addQueryStringParameters("ifVersion" -> "abc")
            .patch(Json.obj("x" -> 1))
        )
        result.status mustBe BAD_REQUEST
        (result.json \ "error").as[String] must include("invalid ifVersion")
      }

    "forward If-Match header as ifVersion on PUT through the router" in
      withCluster(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        await(ws.url(s"$routerUrl/kv/hkey").put(Json.obj("v" -> 1)))
        val result = await(
          ws.url(s"$routerUrl/kv/hkey")
            .addHttpHeaders("If-Match" -> "1")
            .put(Json.obj("v" -> 2))
        )
        result.status mustBe OK
        (result.json \ "version").as[Long] mustBe 2L
      }

    "forward If-Match header as ifVersion on PATCH through the router" in
      withCluster(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        await(ws.url(s"$routerUrl/kv/matchkey").put(Json.obj("a" -> 1)))
        val result = await(
          ws.url(s"$routerUrl/kv/matchkey")
            .addHttpHeaders("If-Match" -> "1")
            .patch(Json.obj("b" -> 2))
        )
        result.status mustBe OK
        (result.json \ "version").as[Long] mustBe 2L

        val get = await(ws.url(s"$routerUrl/kv/matchkey").get())
        val v = (get.json \ "value").as[JsObject]
        (v \ "a").as[Int] mustBe 1
        (v \ "b").as[Int] mustBe 2
      }

    "return 503 when the node is unreachable" in
      withClusterAndDeadNode(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        val get = await(ws.url(s"$routerUrl/kv/any").get())
        get.status mustBe SERVICE_UNAVAILABLE
        (get.json \ "error").as[String] mustBe "node unavailable"
      }

    "return 503 on PUT when the node is unreachable" in
      withClusterAndDeadNode(nodeCount = 1) { (routerUrl, _) =>
        val ws = wsClient
        val put = await(ws.url(s"$routerUrl/kv/any").put(Json.obj("x" -> 1)))
        put.status mustBe SERVICE_UNAVAILABLE
        (put.json \ "error").as[String] mustBe "node unavailable"
      }

    "wrap a non-JSON node response under a 'raw' key" in
      withPlainTextNode(statusCode = 200, textBody = "not json at all") {
        routerUrl =>
          val ws = wsClient
          val get = await(ws.url(s"$routerUrl/kv/any").get())
          (get.json \ "raw").asOpt[String] mustBe defined
      }

    "reach exactly 300 after 3×100 concurrent increments through the router" in
      withCluster(nodeCount = 2) { (routerUrl, _) =>
        val ws = wsClient
        val key = "counter-through-router"

        val seed = await(ws.url(s"$routerUrl/kv/$key").put(JsNumber(0)))
        seed.status mustBe OK

        def incrementOnce(): Unit = {
          @annotation.tailrec
          def loop(): Unit = {
            val get = await(ws.url(s"$routerUrl/kv/$key").get())
            get.status match {
              case OK =>
                val currentValue = (get.json \ "value").as[Int]
                val version = (get.json \ "version").as[Long]
                val put = await(
                  ws.url(s"$routerUrl/kv/$key")
                    .addQueryStringParameters("ifVersion" -> version.toString)
                    .put(JsNumber(currentValue + 1))
                )
                put.status match {
                  case CONFLICT => loop()
                  case OK       => ()
                  case s        => fail(s"unexpected PUT status $s: ${put.body}")
                }
              case s => fail(s"unexpected GET status $s: ${get.body}")
            }
          }
          loop()
        }

        val threads = (0 until 3).map(_ =>
          new Thread(() => (0 until 100).foreach(_ => incrementOnce()))
        )
        threads.foreach(_.start())
        threads.foreach(_.join(60000L))

        val get = await(ws.url(s"$routerUrl/kv/$key").get())
        get.status mustBe OK
        (get.json \ "value").as[Int] mustBe 300
      }
  }
}
