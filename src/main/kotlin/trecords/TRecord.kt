package trecords

import trecords.util.*
import kotlin.reflect.*

interface TRecord {
    val kind: String
    val id: UID
    var isRemoved: Boolean

    fun getModel(): Model

    companion object {
        val ID = "id"
        val KIND = "kind"
        val IS_REMOVED = "isRemoved"

        val defaultPropertyNames = listOf(ID, KIND, IS_REMOVED)
    }
}

interface RefferableRecord {
    fun <T : TRecord> referrers(field: KProperty1<T, TRecord>): List<T>
}

interface TUnknownRecord: TRecord
