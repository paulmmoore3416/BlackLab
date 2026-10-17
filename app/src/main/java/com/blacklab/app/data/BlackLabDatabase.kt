package com.blacklab.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.blacklab.app.core.Profile

@Database(
    entities = [
        DocumentEntity::class, JournalEntity::class,
        TaskListEntity::class, TaskEntity::class, SubtaskEntity::class,
        EventCalendarEntity::class, EventEntity::class,
        TermEntity::class, CourseEntity::class, ClassMeetingEntity::class, GradeCategoryEntity::class,
        AssignmentEntity::class, CertificationEntity::class, CeuEntryEntity::class, StudySessionEntity::class,
        DeckEntity::class, FlashcardEntity::class,
        RecordingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BlackLabDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun journal(): JournalDao
    abstract fun tasks(): TaskDao
    abstract fun events(): EventDao
    abstract fun education(): EducationDao
    abstract fun recordings(): RecordingDao

    companion object {
        const val NAME = "blacklab.db"

        fun build(context: Context): BlackLabDatabase =
            Room.databaseBuilder(context, BlackLabDatabase::class.java, NAME)
                .addCallback(Seed)
                .build()
    }

    /** Default lists and calendars for each profile on first creation. */
    private object Seed : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val lists = mapOf(
                Profile.PERSONAL to listOf("Personal" to 0xFF2BD9A8.toInt(), "Errands" to 0xFFFFB74D.toInt(), "Home" to 0xFF64B5F6.toInt()),
                Profile.PROFESSIONAL to listOf(
                    "Work Orders" to 0xFF4F8CFF.toInt(), "Projects" to 0xFFAB7CFF.toInt(),
                    "Inspections" to 0xFFFF7A59.toInt(), "Vendors" to 0xFF26C6DA.toInt(), "Follow-ups" to 0xFFFFD54F.toInt(),
                ),
            )
            val emojis = mapOf(
                "Personal" to "🏠", "Errands" to "🛒", "Home" to "🔧", "Work Orders" to "🛠️", "Projects" to "📐",
                "Inspections" to "🔍", "Vendors" to "🤝", "Follow-ups" to "📌",
            )
            lists.forEach { (profile, items) ->
                items.forEachIndexed { i, (name, color) ->
                    db.execSQL(
                        "INSERT INTO task_lists (profile, name, color, emoji, sortOrder, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(profile.name, name, color, emojis[name] ?: "", i, System.currentTimeMillis()),
                    )
                }
            }
            val calendars = mapOf(
                Profile.PERSONAL to listOf(
                    "Personal" to 0xFF2BD9A8.toInt(), "Family" to 0xFFFF8A80.toInt(), "Health" to 0xFF81C784.toInt(),
                    "Classes" to 0xFFFFB74D.toInt(), "Travel" to 0xFF64B5F6.toInt(),
                ),
                Profile.PROFESSIONAL to listOf(
                    "Meetings" to 0xFF4F8CFF.toInt(), "Maintenance" to 0xFFFF7A59.toInt(), "Inspections" to 0xFFFFD54F.toInt(),
                    "Vendors & Contractors" to 0xFF26C6DA.toInt(), "Training" to 0xFFAB7CFF.toInt(), "Deadlines" to 0xFFEF5350.toInt(),
                ),
            )
            calendars.forEach { (profile, items) ->
                items.forEachIndexed { i, (name, color) ->
                    db.execSQL(
                        "INSERT INTO event_calendars (profile, name, color, visible, sortOrder) VALUES (?, ?, ?, 1, ?)",
                        arrayOf(profile.name, name, color, i),
                    )
                }
            }
        }
    }
}
