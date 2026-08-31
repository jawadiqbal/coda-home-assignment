package cluster

/** Identifies a storage node.
  * id   — stable logical name used in /internal/keys responses and logs.
  * url  — base HTTP URL used by WS calls, e.g. "http://localhost:7001".
  */
final case class NodeRef(id: String, url: String)
