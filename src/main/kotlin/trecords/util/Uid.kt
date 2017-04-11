package trecords.util

data class UID(val id: String) {
    val kind = id.substringBefore('-')
    override fun toString() = id
}
