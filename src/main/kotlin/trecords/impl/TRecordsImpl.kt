package trecords.impl

import com.github.krukow.clj_ds.PersistentSet
import rx.Observable
import rx.lang.kotlin.PublishSubject
import trecords.Model
import trecords.ModelUpdate
import trecords.ReferenceIndex
import trecords.RefferableRecord
import trecords.TNewRecordEvent
import trecords.TRecord
import trecords.TRecordUpdateEvent
import trecords.TRemoveRecordEvent
import trecords.TUnknownRecord
import trecords.util.Random
import trecords.util.UID
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

fun Class<*>.isRecordType(): Boolean = TRecord::class.java.isAssignableFrom(this)
private data class FieldDesc(val id: UID, val field: String)
private fun fieldId(id: UID, field: String): FieldDesc = FieldDesc(id, field)

private class TransactionData(
        val initialState: ModelData,
        val uid: UID) {
    val tnState: ModelData = initialState.copy()
    val dirtySet: MutableSet<FieldDesc> = mutableSetOf()
    var deaf = false

    fun setField(id: UID, field: String, value: Any?) {
        val changed = tnState.setField(id, field, value)
        if (changed) {
            markDirty(id, field)
        }
    }

    fun markDirty(id: UID, field: String) {
        if (!deaf) dirtySet.add(fieldId(id, field))
    }
}

private class TModelReferenceIndex (val model: TModelImpl, var index: com.github.krukow.clj_lang.IPersistentMap<FieldDesc, PersistentSet<UID>> = com.github.krukow.clj_lang.PersistentHashMap.emptyMap()): ReferenceIndex {
    override fun getReferrers(to: UID, propertyName: String): List<UID> {
        return index.valAt(fieldId(to, propertyName))?.toList() ?: emptyList()
    }

    override fun onSetValue(id: UID, propertyName: String, oldValue: UID?, newValue: UID?) {
        if (oldValue != null) {
            val key = fieldId(oldValue, propertyName)
            index = index.assoc(key, index.valAt(key).minus(id))
        }

        if (newValue != null) {
            val key = fieldId(newValue, propertyName)
            index = index.assoc(key, (index.valAt(key) ?: com.github.krukow.clj_lang.PersistentHashSet.emptySet()).plus(id))
        }
    }

    override fun copy(): ReferenceIndex {
        return TModelReferenceIndex(model, index)
    }
}

private class ModelData(val model: TModelImpl, val index: TModelReferenceIndex) {
    var data: com.github.krukow.clj_lang.IPersistentMap<FieldDesc, Any> = com.github.krukow.clj_lang.PersistentHashMap.emptyMap()
    var records: com.github.krukow.clj_lang.IPersistentMap<UID, TRecord> = com.github.krukow.clj_lang.PersistentHashMap.emptyMap()

    fun copy(): ModelData {
        val x = this
        return ModelData(model, index.copy() as TModelReferenceIndex).apply {
            data = x.data
            records = x.records
        }
    }

    fun lookup(id: UID): TRecord? = records.valAt(id)

    fun getField(id: UID, field: String): Any? = data.valAt(fieldId(id, field))
    fun getRefField(id: UID, field: String): TRecord? = getField(id, field)?.let { lookup(it as UID) }

    fun setRawField(id: UID, field: String, value: Any?): Boolean {
        val fieldId = fieldId(id, field)

        val oldValue = data.valAt(fieldId)

        if (oldValue == value) return false

        data = if (value == null) {
            data.without(fieldId)
        } else {
            data.assoc(fieldId, value)
        }

        return true
    }

    fun setField(id: UID, field: String, value: Any?): Boolean {
        val prev = getField(id, field)

        if (prev == value) return false

        setRawField(id, field, value)
        run {
            val oldUid = prev as? UID
            val newUid = value as? UID
            if (oldUid != null || newUid != null )
                index.onSetValue(id, model.findProperty(model.types[id.kind]!!, field)!!.name, oldUid, newUid)
        }

        return true
    }

    fun index(to: TRecord, byProperty: KProperty<*>): List<TRecord> {
        return index.getReferrers(to.id, byProperty.name).map { lookup(it)!! }
    }

    fun clearRemoved() {
        val idsToRemove = records.filter { it.value.isRemoved }.map { it.key }
        if (idsToRemove.isNotEmpty()) {
            idsToRemove.forEach {
                this.records = records.without(it)
            }
        }
    }
}

internal class TModelImpl(vararg records: KClass<out TRecord>) : Model {
    override fun getReferenceIndex(): ReferenceIndex {
        return currentReadTransaction().index
    }

    private val data: AtomicReference<ModelData> = AtomicReference(ModelData(this, TModelReferenceIndex(this)))

    override fun <T : TRecord> new(klass: KClass<T>) = new(klass, nextUID(klass))

    fun <T : TRecord> new(klass: KClass<T>, id: UID): T {

        @Suppress("UNCHECKED_CAST")
        val record = proxies[klass]!!.newInstance(Handler(id)) as T
        record.isRemoved = false
        register(record)
        return record
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TRecord> new(id: UID): T {
        val kind = id.kind
        return new(types[kind] ?: error("Type $kind is not registered"), id) as T
    }

    override fun <T : TRecord> removeRecord(record: T) {
        record.isRemoved = true
    }

    fun <T: TRecord> nextUID(kind: KClass<T>) = UID("${kind.simpleName}-${Random.nextUID()}")

    val types = records.map { it.simpleName to it }.toMap()
    private val transaction = ThreadLocal<TransactionData>()

    override val modelUpdates = PublishSubject<ModelUpdate>()

    override val changeEvents = modelUpdates.flatMap { Observable.from(it.changedRecords).flatMap { Observable.from(it.second) } }

    private val fields = records.flatMap { r -> r.declaredMemberProperties.map { Pair(r to it.name, it) } }.toMap()

    private val proxies = records.map {
        val cons = Proxy.getProxyClass(it.java.classLoader, it.java, RefferableRecord::class.java).
            getConstructor(InvocationHandler::class.java)
        cons.isAccessible = true
        it to cons
    }.toMap()

    private fun currentReadTransaction() = transaction.get()?.tnState ?: data.get()
    private fun currentWriteTransaction() = transaction.get() ?: error("No transaction in scope")

    override fun <T> silent(body: () -> T): T {
        val outer = currentWriteTransaction()
        val deaf = outer.deaf
        outer.deaf = true
        try {
            return body()
        }
        finally {
            outer.deaf = deaf
        }
    }

    override fun <T> transaction(transactionId: UID, body: () -> T): T {
        val outer = transaction.get()
        return if (outer != null) {
            body()
        } else {
            val curData = data.get()

            val tn = TransactionData(curData, transactionId)
            transaction.set(tn)

            val (result, events) = try {
                body() to flushTn(tn)
            }
            finally {
                transaction.set(null)
            }

            events?.let {
                modelUpdates.onNext(it)
            }

            result
        }
    }

    fun flushTransaction(): ModelUpdate? {
        return flushTn(currentWriteTransaction())
    }

    private fun flushTn(tn: TransactionData): ModelUpdate? {
        val initialState = tn.initialState
        val newState = tn.tnState

        val dirty = tn.dirtySet.groupBy { it.id }

        if (dirty.isEmpty()) return null

        tn.dirtySet.clear()
        if (data.compareAndSet(initialState, newState)) {
            val updates = dirty.filter {
                val record = newState.lookup(it.key)!!
                record !is TUnknownRecord
            }.map {
                val record = newState.lookup(it.key)!!
                val kind = types[record.kind] ?: error("Unknow kind ${record.kind} for uid ${it.key}")

                val events = it.value.map {
                    val fieldName = it.field
                    when {
                        fieldName == "" -> TNewRecordEvent(record)
                        fieldName == TRecord.Companion.IS_REMOVED -> {
                            if (record.isRemoved)
                                TRemoveRecordEvent(record)
                            else
                                null
                        }
                        else -> {
                            val isRecord = run {
                                    val field = findProperty(kind, fieldName)!!
                                    field.returnType.javaClass.isRecordType()
                            }

                            val oldValue = if (isRecord) initialState.getRefField(record.id, fieldName) else initialState.getField(record.id, fieldName)
                            val newValue = if (isRecord) newState.getRefField(record.id, fieldName) else newState.getField(record.id, fieldName)

                            TRecordUpdateEvent(record, fieldName, oldValue, newValue)
                        }
                    }
                }.filterNotNull()

                // squeeze a bit
                val newEvent = events.filterIsInstance<TNewRecordEvent>().singleOrNull()
                val removeEvent = events.filterIsInstance<TRemoveRecordEvent>().singleOrNull()
                when {
                    newEvent != null && removeEvent != null -> null
                    removeEvent != null -> record to listOf(removeEvent)
                    else -> record to events
                }
            }.filterNotNull()

            newState.clearRemoved()

            return ModelUpdate(tn.uid.id, updates)
        }
        else {
            error("Transaction failure")
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TRecord> tryLookup(id: UID): T? = currentReadTransaction().records.valAt(id) as? T

    @Suppress("UNCHECKED_CAST")
    fun <F : TRecord, T : TRecord> referers(to: T, on: KProperty<*>): List<F> {
        return currentReadTransaction().index(to, on) as List<F>
    }

    internal fun register(record: TRecord) {
        val tn = currentWriteTransaction()
        tn.tnState.records = tn.tnState.records.assoc(record.id, record)
        tn.markDirty(record.id, "")
    }

    fun findProperty(kind: KClass<out TRecord>, propertyName: String): KProperty1<out TRecord, Any?>? {
        return fields[kind to propertyName]
    }

    fun getProperty(record_id: UID, propertyName: String): Any? {
        val raw = getField(record_id, propertyName)
        return if (raw is UID) lookup(raw) else raw
    }

    fun setProperty(record_id: UID, propertyName: String, value: Any?) {
        val raw = if (value is TRecord) value.id else value
        setField(record_id, propertyName, raw)
    }

    override fun setField(record_id: UID, propertyName: String, value: Any?) {
        currentWriteTransaction().setField(record_id, propertyName, value)
    }

    override fun getField(record_id: UID, propertyName: String): Any? {
        return currentReadTransaction().getField(record_id, propertyName)
    }

    private inner class Handler(val id: UID) : InvocationHandler {
        val kind = id.kind

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val message = method.name.let {
                when (it) {
                    "setRemoved" -> "setIsRemoved"
                    "isRemoved" -> "getIsRemoved"
                    else -> it
                }
            }

            return when {
                message == "getId" -> id
                message == "getModel" -> this@TModelImpl
                message == "getKind" -> kind
                message == "toString" -> "$kind[@$id]"
                message == "equals" -> (args?.get(0) as? TRecord)?.id == id
                message == "hashCode" -> (args?.get(0) as? TRecord)?.id?.hashCode() ?: -1
                message == "referrers" -> {
                    referers<TRecord, TRecord>(proxy as TRecord, args?.get(0) as KProperty<*>)
                }

                message.startsWith("get") -> {
                    val propertyName = message.removePrefix("get").decapitalize()
                    getProperty(id, propertyName)
                }

                message.startsWith("set") -> {
                    val propertyName = message.removePrefix("set").decapitalize()
                    setProperty(id, propertyName, args?.get(0))
                }

                else -> {
                    error("Unknown function $message for proxy of $kind")
                }
            }
        }
    }
}
