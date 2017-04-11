package trecords

import rx.*
import trecords.impl.*
import trecords.util.*
import kotlin.reflect.*

interface Model {
    companion object {
        fun new(vararg records: KClass<out TRecord>): Model = TModelImpl(*records)
    }

    fun <T : TRecord> new(klass: KClass<T>): T

    fun <T : TRecord> new(id: UID): T
    fun <T : TRecord> removeRecord(record: T)

    fun <T> transaction(transactionId: UID, body: () -> T): T
    fun <T> transaction(body: () -> T) = transaction(Random.nextUID(), body)

    fun <T : TRecord> lookup(id: UID): T = tryLookup<T>(id) ?: error("Can't find $id")
    fun <T : TRecord> tryLookup(id: UID): T?

    val modelUpdates: Observable<ModelUpdate>
    val changeEvents: Observable<TEvent>

    fun <T> silent(body: () -> T): T
    fun setField(record_id: UID, propertyName: String, value: Any?)
    fun getField(record_id: UID, propertyName: String): Any?

    fun getReferenceIndex(): ReferenceIndex
}

inline fun <reified T: TRecord> Model.new() = new (T::class)
inline fun <reified T: TRecord> Model.new(init: T.() -> Unit) = new(T::class).apply(init)

