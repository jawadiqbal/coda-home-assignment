package models

/** Outcome of a put or patch operation. */
sealed trait WriteResult

object WriteResult {

  /** The write was accepted; vv is the new state. */
  final case class Written(vv: VersionedValue) extends WriteResult

  /** The supplied ifVersion did not match the current version.
    * currentVersion is None when the key did not exist at all.
    */
  final case class Conflict(currentVersion: Option[Long]) extends WriteResult
}
