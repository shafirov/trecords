package trecords

import trecords.util.*
import kotlin.reflect.*

interface ReferenceIndex {
    fun getReferrers (to: UID, propertyName: String): List<UID>
    fun onSetValue (id: UID, propertyName: String, oldValue: UID?, newValue: UID?)

    fun copy(): ReferenceIndex
}

class OneToManyRelationship<TOne: TRecord, TMany: TRecord> (val propertyInMany: KProperty1<TMany, *>) {
    operator fun getValue(tOne: TOne, property: KProperty<*>): List<TMany> {
        val model = tOne.getModel()
        return model.transaction {
            val referenceIndex: ReferenceIndex = model.getReferenceIndex()
            val rights = referenceIndex.getReferrers(tOne.id, propertyInMany.name)
                .map { model.lookup<TMany>(it) }
                .filter { !it.isRemoved }
            rights
        }
    }

    operator fun setValue(tOne: TOne, property: KProperty<*>, list: List<TMany>) {
        val model = tOne.getModel()
        model.transaction {
            val existingMany = getValue(tOne, property).toMutableSet()

            list.forEach {
                if (existingMany.contains(it)) {
                    existingMany.remove(it)
                } else {
                    val manyRecord = model.lookup<TMany>(it.id)
                    model.setField(manyRecord.id, propertyInMany.name, tOne.id)
                }
            }

            existingMany.forEach {
                val manyRecord = model.lookup<TMany>(it.id)
                model.setField(manyRecord.id, propertyInMany.name, null)
            }
        }
    }
}

inline fun
    <TLeft: TRecord, TRight: TRecord, reified TRelationship: TRecord>
    refVia(leftProperty: KProperty1<TRelationship, TLeft>, rightProperty: KProperty1<TRelationship, TRight>) =
    RelationshipViaRecord(leftProperty, rightProperty, TRelationship::class)

class RelationshipViaRecord<TLeft: TRecord, TRight: TRecord, TRelationship: TRecord> (val leftProperty: KProperty1<TRelationship, TLeft>, val rightProperty: KProperty1<TRelationship, TRight>, val relationshipKind: KClass<TRelationship>) {
    operator fun getValue(tLeft: TLeft, property: KProperty<*>): List<TRight> {
        val model = tLeft.getModel()
        return model.transaction {
            val referenceIndex: ReferenceIndex = model.getReferenceIndex()
            val rights = referenceIndex.getReferrers(tLeft.id, leftProperty.name)
                .map { model.lookup<TRelationship>(it) }
                .filter { !it.isRemoved }
                .map { model.getField(it.id, rightProperty.name) as UID }
                .map { model.lookup<TRight>(it) }
                .filter { !it.isRemoved }
            rights
        }
    }

    operator fun setValue(tLeft: TLeft, property: KProperty<*>, list: List<TRight>) {
        val model = tLeft.getModel()
        model.transaction {
            val referenceIndex: ReferenceIndex = model.getReferenceIndex()
            val existingRightUidsMap = referenceIndex.getReferrers(tLeft.id, leftProperty.name).map {
                model.getField(it, rightProperty.name) to it
            }.toMap()

            val relationsToRemove = existingRightUidsMap.values.toMutableSet()
            val relationsToAdd = mutableListOf<UID>()
            list.forEach {
                val uid = existingRightUidsMap[it.id]
                if (uid == null) {
                    relationsToAdd.add(it.id)
                } else {
                    relationsToRemove.remove(uid)
                }
            }

            relationsToRemove.forEach {
                model.removeRecord<TRelationship>(model.lookup<TRelationship>(it))
            }

            relationsToAdd.forEach {
                val rel = model.new<TRelationship>(relationshipKind)
                model.setField(rel.id, leftProperty.name, tLeft.id)
                model.setField(rel.id, rightProperty.name, it)
            }
        }
    }
}
