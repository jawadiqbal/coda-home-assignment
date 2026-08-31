package http

import play.api.mvc.Headers

/** Optimistic-locking header. HTTP header names are case-insensitive (RFC 7230). */
object KvHeaders {
  val IfVersion: String = "kv-if-version"

  def ifVersion(headers: Headers): Option[String] =
    headers.headers.collectFirst {
      case (name, value) if name.equalsIgnoreCase(IfVersion) => value
    }
}
