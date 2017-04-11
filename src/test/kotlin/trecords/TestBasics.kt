package trecords

import org.junit.*
import trecords.samples.*
import kotlin.test.*

class TestBasics {
    @Test fun basics() {
        val model = Model.new(Team::class, Member::class)

        val jetbrains = model.transaction {
            val jetbrains = model.new<Team> {
                name = "JetBrains"
            }

            model.new<Member> {
                name = "Bob"
                team = jetbrains
            }

            model.new<Member> {
                name = "Alice"
                team = jetbrains
            }

            jetbrains
        }

        assertEquals("Alice, Bob", jetbrains.members.map { it.name }.sorted().joinToString())

        var modelChangeFired = false
        model.changeEvents.forEach {
            @Suppress("UNCHECKED_CAST")
            val update = it as? TRecordUpdateEvent<String> ?: error("Expected record update event")

            assertEquals("Bob", update.oldValue)
            assertEquals("John", update.newValue)
            modelChangeFired = true
        }

        model.transaction {
            jetbrains.members.first { it.name == "Bob" }.name = "John"
        }

        assertEquals(true, modelChangeFired, "Model change didn't fired!")
    }
}
