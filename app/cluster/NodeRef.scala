package cluster

/** Identifies a storage node.
  * id   — stable logical name used in /internal/keys responses and logs.
  * url  — base HTTP URL (no trailing slash) used by WS calls, e.g. "http://localhost:7001".
  */
final case class NodeRef(id: String, url: String)
