package cluster

import http.KvHeaders
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.ws.{WSClient, WSResponse}
import play.api.libs.json.JsValue

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

/** Thin HTTP client used by the router to forward requests to storage nodes.
  *
  * The router must preserve the status code and response body from the node
  * exactly — so callers receive a raw WSResponse and do the mapping themselves.
  *
  * Timeout comes from kv.remoteTimeout.  On timeout or connection failure the
  * Future fails; the router maps this to 503.
  */
@Singleton
class RemoteNodeClient @Inject() (ws: WSClient, config: Configuration)(implicit
    ec: ExecutionContext
) {

  private val timeout: FiniteDuration =
    config.get[FiniteDuration]("kv.remoteTimeout")

  def get(node: NodeRef, key: String): Future[WSResponse] =
    ws.url(s"${node.url}/kv/$key")
      .withRequestTimeout(timeout)
      .get()

  def put(
      node: NodeRef,
      key: String,
      body: JsValue,
      ifVersion: Option[Long],
      ifVersionHeader: Option[String]
  ): Future[WSResponse] = {
    var req = ws.url(s"${node.url}/kv/$key").withRequestTimeout(timeout)
    // Forward ifVersion as query param (preferred) or kv-if-version header
    ifVersion match {
      case Some(v) =>
        req = req.addQueryStringParameters("ifVersion" -> v.toString)
      case None =>
        ifVersionHeader.foreach(h =>
          req = req.addHttpHeaders(KvHeaders.IfVersion -> h)
        )
    }
    req.withHttpHeaders("Content-Type" -> "application/json").put(body)
  }

  def patch(
      node: NodeRef,
      key: String,
      body: JsValue,
      ifVersion: Option[Long],
      ifVersionHeader: Option[String]
  ): Future[WSResponse] = {
    var req = ws.url(s"${node.url}/kv/$key").withRequestTimeout(timeout)
    ifVersion match {
      case Some(v) =>
        req = req.addQueryStringParameters("ifVersion" -> v.toString)
      case None =>
        ifVersionHeader.foreach(h =>
          req = req.addHttpHeaders(KvHeaders.IfVersion -> h)
        )
    }
    req.withHttpHeaders("Content-Type" -> "application/json").patch(body)
  }

  /** Streams the NDJSON key list from a node's /internal/keys endpoint.
    * Returns a streaming response; caller uses bodyAsSource.
    */
  def streamKeys(node: NodeRef): Future[WSResponse] =
    ws.url(s"${node.url}/internal/keys")
      .withRequestTimeout(timeout)
      .stream()
}
