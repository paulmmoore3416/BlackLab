package com.blacklab.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blacklab.app.data.BlackLabDatabase
import com.blacklab.app.data.Migrations
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrading from a 4.1.x database (schema 1) keeps existing data and yields the exact schema 2. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BlackLabDatabase::class.java)

    @After fun cleanup() { DeviceTest.context.deleteDatabase(name) }

    @Test fun upgradeFrom1To2KeepsDataAndMatchesSchema() {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO task_lists (profile, name, color, emoji, sortOrder, createdAt) VALUES ('PERSONAL', 'Work Orders', 1, '', 0, 0)")
            execSQL("INSERT INTO tasks (profile, listId, title, notes, priority, important, tags, completed, sortOrder, createdAt, updatedAt) " +
                "VALUES ('PERSONAL', 1, 'Replace AHU-3 belt', '', 3, 1, '', 0, 0, 0, 0)")
            close()
        }
        // Validates every table, column and index against schemas/2.json
        val db = helper.runMigrationsAndValidate(name, 2, true, Migrations.M1_2)
        db.query("SELECT title FROM tasks").use { c -> assertTrue(c.moveToFirst()); assertEquals("Replace AHU-3 belt", c.getString(0)) }
        db.query("SELECT COUNT(*) FROM notebooks").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
        db.close()

        // And Room itself opens the upgraded file and reads through the DAOs
        val room = Room.databaseBuilder(DeviceTest.context, BlackLabDatabase::class.java, name).addMigrations(Migrations.M1_2).build()
        try {
            runBlocking {
                assertEquals("Replace AHU-3 belt", room.tasks().all().single().title)
                assertTrue(room.notes().all().isEmpty())
            }
        } finally { room.close() }
    }
}
