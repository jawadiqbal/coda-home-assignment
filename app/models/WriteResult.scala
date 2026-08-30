package models

/** Outcome of a put or patch operation. */
sealed trait WriteResult

object WriteResult {

  final case class Written(vv: VersionedValue) extends WriteResult

  final case class Conflict(currentVersion: Option[Long]) extends WriteResult
}
