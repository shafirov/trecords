package trecords.samples

import trecords.*

interface Team : TRecord, RefferableRecord {
    var name: String
}

interface Member: TRecord {
    var name: String
    var team: Team
}

val Team.members get() = referrers(Member::team)
