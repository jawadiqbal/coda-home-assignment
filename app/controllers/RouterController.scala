package controllers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import cluster.{NodeRegistry, Partitioner, RemoteNodeClient}
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/** Stateless router process.
  *
  * For every PUT/PATCH/GET /kv/*key it:
  *   1. Computes the owning node via ModuloPartitioner.
  *   2. Forwards the request over HTTP using RemoteNodeClient.
  *   3. Relays the node's status code and body unchanged.
  *
  * GET /kv (no key) fans out to all nodes via /internal/keys, merges NDJSON
  * streams using flatMapMerge, and re-streams to the client.
  *
  * Error handling:
  *   - A Future failure (connection refused, timeout) maps to 503.
  *   - The node's own error codes (404, 409, etc.) pass through as-is.
  *
  * Correctness note — NDJSON framing:
  *   flatMapMerge interleaves at element granularity.  WS bodyAsSource delivers
  *   raw HTTP chunks, not whole lines, so we re-frame on "\n" inside
  *   streamKeysFrom before merging.  Without this, chunks from different nodes
  *   could be concatenated mid-line, producing corrupt JSON.
  */
@Singleton
class RouterController @Inject() (
    partitioner: Partitioner,
    registry: NodeRegistry,
    client: RemoteNodeClient,
    cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends AbstractController(cc) with KvHandler {

  // ------------------------------------------------------------------ routing

  def get(key: String): Action[AnyContent] = Action.async {
    val node = partitioner.ownerOf(key)
    client.get(node, key).map(relayResponse).recover(nodeDown)
  }

  def put(key: String): Action[JsValue] = Action.async(parse.json) { request =>
    val node    = partitioner.ownerOf(key)
    val ifV     = parseIfVersion(request.getQueryString("ifVersion"))
    val ifMatch = request.headers.get("If-Match")
    ifV match {
      case Left(err) => Future.successful(BadRequest(Json.obj("error" -> err)))
      case Right(parsed) =>
        client.put(node, key, request.body, parsed, ifMatch)
          .map(relayResponse)
          .recover(nodeDown)
    }
  }

  def patch(key: String): Action[JsValue] = Action.async(parse.json) { request =>
    val node    = partitioner.ownerOf(key)
    val ifV     = parseIfVersion(request.getQueryString("ifVersion"))
    val ifMatch = request.headers.get("If-Match")
    ifV match {
      case Left(err) => Future.successful(BadRequest(Json.obj("error" -> err)))
      case Right(parsed) =>
        client.patch(node, key, request.body, parsed, ifMatch)
          .map(relayResponse)
          .recover(nodeDown)
    }
  }

  // ------------------------------------------------------------------ fan-out

  /** GET /kv — no key segment.
    *
    * Fans out to all nodes' /internal/keys endpoints concurrently and
    * re-streams the merged NDJSON to the client.  The degree of parallelism
    * equals the cluster size so all nodes are queried simultaneously.
    */
  def listAll(): Action[AnyContent] = Action {
    val parallelism = registry.nodes.size.max(1)
    val merged = Source(registry.nodes.toList)
      .flatMapMerge(parallelism, node => streamKeysFrom(node))

    Ok.chunked(merged).as("application/x-ndjson")
  }

  // ------------------------------------------------------------------ helpers

  /** Re-frames the node's chunked response on newlines so that flatMapMerge
    * interleaves complete records rather than raw byte chunks.
    *
    * maximumFrameLength = 8 KB is generous for {"key":"...","node":"..."} lines.
    * allowTruncation = true so a node that closes without a trailing newline
    * does not abort the whole merged stream.
    */
  private def streamKeysFrom(node: cluster.NodeRef): Source[ByteString, _] =
    Source.futureSource(
      client.streamKeys(node).map(_.bodyAsSource)
    )
    .via(
      Framing.delimiter(
        ByteString("\n"),
        maximumFrameLength = 8192,
        allowTruncation    = true
      )
    )
    .map(_ ++ ByteString("\n"))

  /** Relays the node's HTTP status code and JSON body to the original client.
    * Non-JSON node responses (should not happen in practice) pass as plain text.
    */
  private def relayResponse(r: WSResponse): Result = {
    val body: JsValue = Try(r.json).getOrElse(Json.obj("raw" -> r.body))
    Status(r.status)(body)
  }

  /** Maps a failed Future (connection refused, timeout) to 503. */
  private val nodeDown: PartialFunction[Throwable, Result] = {
    case NonFatal(ex) =>
      ServiceUnavailable(
        Json.obj("error" -> "node unavailable", "detail" -> ex.getMessage)
      )
  }

  /** Parses the ifVersion string.
    * Returns Right(None) when absent, Right(Some(v)) when valid, Left(msg) on bad input.
    */
  private def parseIfVersion(raw: Option[String]): Either[String, Option[Long]] =
    raw match {
      case None    => Right(None)
      case Some(s) =>
        Try(s.toLong).toOption match {
          case Some(v) => Right(Some(v))
          case None    => Left(s"invalid ifVersion '$s': must be a long integer")
        }
    }
}
