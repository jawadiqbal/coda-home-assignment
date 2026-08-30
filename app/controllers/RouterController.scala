package controllers

import akka.stream.scaladsl.{Framing, Source}
import akka.util.ByteString
import cluster.{NodeRegistry, Partitioner, RemoteNodeClient}
import http.KvHeaders
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Logger}
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSResponse
import play.api.mvc._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

/** Stateless router process.
  *
  * For every PUT/PATCH/GET /kv/key it:
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
  * NDJSON framing:
  *   flatMapMerge for parallel collection of data from nodes. WS bodyAsSource delivers
  *   raw HTTP chunks, so we re-frame on "\n" inside
  *   streamKeysFrom before merging. Without this, chunks from different nodes
  *   could be concatenated mid-line, producing corrupt JSON.
  */
@Singleton
class RouterController @Inject() (
    partitioner: Partitioner,
    registry: NodeRegistry,
    client: RemoteNodeClient,
    config: Configuration,
    cc: ControllerComponents
)(implicit ec: ExecutionContext)
    extends AbstractController(cc)
    with KvHandler {

  private val logger = Logger(getClass)
  private val ndjsonMaxFrameLength: Int =
    config.get[Int]("kv.ndjsonMaxFrameLength")

  def get(key: String): Action[AnyContent] = Action.async {
    val node = partitioner.ownerOf(key)
    client
      .get(node, key)
      .map(relayResponse(node.id, key, "GET"))
      .recover(nodeDown(node.id, key, "GET"))
  }

  def put(key: String): Action[JsValue] = Action.async(parse.json) { request =>
    val node = partitioner.ownerOf(key)
    val ifV = parseIfVersion(request.getQueryString("ifVersion"))
    val ifVersionHeader = KvHeaders.ifVersion(request.headers)
    ifV match {
      case Left(err) => Future.successful(BadRequest(Json.obj("error" -> err)))
      case Right(parsed) =>
        client
          .put(node, key, request.body, parsed, ifVersionHeader)
          .map(relayResponse(node.id, key, "PUT"))
          .recover(nodeDown(node.id, key, "PUT"))
    }
  }

  def patch(key: String): Action[JsValue] = Action.async(parse.json) {
    request =>
      val node = partitioner.ownerOf(key)
      val ifV = parseIfVersion(request.getQueryString("ifVersion"))
      val ifVersionHeader = KvHeaders.ifVersion(request.headers)
      ifV match {
        case Left(err) =>
          Future.successful(BadRequest(Json.obj("error" -> err)))
        case Right(parsed) =>
          client
            .patch(node, key, request.body, parsed, ifVersionHeader)
            .map(relayResponse(node.id, key, "PATCH"))
            .recover(nodeDown(node.id, key, "PATCH"))
      }
  }

  def listAll(): Action[AnyContent] = Action {
    val parallelism = registry.nodes.size.max(1)
    val merged = Source(registry.nodes.toList)
      .flatMapMerge(parallelism, node => streamKeysFrom(node))

    Ok.chunked(merged).as("application/x-ndjson")
  }

  /** Re-frames the node's chunked response on newlines so that flatMapMerge
    * collects complete records rather than raw byte chunks.
    *
    * Merging streams follow best-effort strategy, so it doesn't fail if one node/stream fails.
    * Connection errors and non-200 responses become an empty substream
    * so the remaining nodes still contribute keys.
    *
    * allowTruncation = true, so a node that closes without a trailing newline
    * does not abort the whole merged stream.
    */
  private def streamKeysFrom(node: cluster.NodeRef): Source[ByteString, _] =
    Source
      .futureSource(
        client
          .streamKeys(node)
          .map { resp =>
            if (resp.status == 200) resp.bodyAsSource
            else {
              logger.error(
                s"node ${node.id} returned HTTP ${resp.status} for /internal/keys; skipping"
              )
              Source.empty[ByteString]
            }
          }
          .recover { case NonFatal(ex) =>
            logger.error(
              s"node ${node.id} unreachable during key listing: ${ex.getMessage}"
            )
            Source.empty[ByteString]
          }
      )
      .via(
        Framing.delimiter(
          ByteString("\n"),
          maximumFrameLength = ndjsonMaxFrameLength,
          allowTruncation = true
        )
      )
      .map(_ ++ ByteString("\n"))
      .recoverWithRetries(
        1,
        { case NonFatal(ex) =>
          logger.warn(
            s"node ${node.id} stream failed during key listing: ${ex.getMessage}"
          )
          Source.empty[ByteString]
        }
      )

  private def relayResponse(nodeId: String, key: String, method: String)(
      r: WSResponse
  ): Result =
    Try(r.json) match {
      case scala.util.Success(json) =>
        if (r.status == 404)
          logger.warn(s"$method key=$key not found on node=$nodeId")
        else if (r.status >= 200 && r.status < 300)
          logger.info(
            s"$method key=$key routed to node=$nodeId status=${r.status}"
          )
        Status(r.status)(json)
      case scala.util.Failure(_) =>
        logger.error(
          s"node returned non-JSON body (status=${r.status}); body suppressed"
        )
        BadGateway(Json.obj("error" -> "node returned an unexpected response"))
    }

  private def nodeDown(
      nodeId: String,
      key: String,
      method: String
  ): PartialFunction[Throwable, Result] = { case NonFatal(ex) =>
    logger.error(s"$method key=$key node=$nodeId unreachable: ${ex.getMessage}")
    ServiceUnavailable(
      Json.obj("error" -> "node unavailable")
    )
  }

  private def parseIfVersion(
      raw: Option[String]
  ): Either[String, Option[Long]] =
    raw match {
      case None => Right(None)
      case Some(s) =>
        Try(s.toLong).toOption match {
          case Some(v) => Right(Some(v))
          case None => Left(s"invalid ifVersion '$s': must be a long integer")
        }
    }
}
