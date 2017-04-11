package trecords

data class ModelUpdate(val transactionId: String, val changedRecords: List<Pair<TRecord, List<TEvent>>>)

interface TEvent {
    val record: TRecord
}

data class TNewRecordEvent(override val record: TRecord) : TEvent
data class TRemoveRecordEvent(override val record: TRecord) : TEvent
data class TRecordUpdateEvent<T>(override val record: TRecord, val fieldName: String, val oldValue: T, val newValue: T) : TEvent
