package com.blacklab.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------- Documents & Journal

@Entity(tableName = "documents", indices = [Index("profile")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val title: String,
    val folder: String = "",
    val tags: String = "",
    val filePath: String = "",
    val preview: String = "",
    val wordCount: Int = 0,
    val pinned: Boolean = false,
    val pageSize: String = "LETTER",
    val landscape: Boolean = false,
    val marginMm: Int = 25,
    val courseId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "journal", indices = [Index("profile"), Index("entryDay")])
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val entryDay: Long, // LocalDate.toEpochDay()
    val title: String = "",
    val mood: Int = 0, // 0 = not set, 1..5
    val tags: String = "",
    val location: String = "",
    val weather: String = "",
    val favorite: Boolean = false,
    val filePath: String = "",
    val preview: String = "",
    val wordCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------- Tasks

@Entity(tableName = "task_lists", indices = [Index("profile")])
data class TaskListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val name: String,
    val color: Int,
    val emoji: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks", indices = [Index("profile"), Index("listId")])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val listId: Long? = null,
    val title: String,
    val notes: String = "",
    val dueDay: Long? = null, // epoch day
    val dueMinute: Int? = null, // minute of day
    val reminderAt: Long? = null, // epoch millis
    val repeatRule: String? = null,
    val priority: Int = 0, // 0 none, 1 low, 2 medium, 3 high, 4 urgent
    val important: Boolean = false,
    val myDayDay: Long? = null, // epoch day it was added to My Day
    val tags: String = "",
    val estimateMinutes: Int? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val sortOrder: Int = 0,
    val courseId: Long? = null,
    val attachmentPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "subtasks",
    indices = [Index("taskId")],
    foreignKeys = [ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE)],
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val done: Boolean = false,
    val sortOrder: Int = 0,
)

// ---------------------------------------------------------------- Calendar

@Entity(tableName = "event_calendars", indices = [Index("profile")])
data class EventCalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val name: String,
    val color: Int,
    val visible: Boolean = true,
    val sortOrder: Int = 0,
)

@Entity(tableName = "events", indices = [Index("profile"), Index("start")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val calendarId: Long? = null,
    val title: String,
    val location: String = "",
    val notes: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    val color: Int? = null,
    val repeatRule: String? = null,
    val exDates: String = "",
    val reminders: String = "", // minutes before, comma separated
    val attendees: String = "",
    val url: String = "",
    val busy: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------- Education

@Entity(tableName = "terms", indices = [Index("profile")])
data class TermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val name: String,
    val institution: String = "",
    val startDay: Long? = null,
    val endDay: Long? = null,
)

@Entity(tableName = "courses", indices = [Index("profile"), Index("termId")])
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val termId: Long? = null,
    val code: String = "",
    val title: String,
    val kind: String = "COLLEGE", // COLLEGE, CERTIFICATION, TRAINING, WORKSHOP, ONLINE
    val institution: String = "",
    val instructor: String = "",
    val instructorEmail: String = "",
    val officeHours: String = "",
    val location: String = "",
    val onlineUrl: String = "",
    val credits: Double = 3.0,
    val color: Int,
    val status: String = "IN_PROGRESS", // PLANNED, IN_PROGRESS, COMPLETED, DROPPED
    val startDay: Long? = null,
    val endDay: Long? = null,
    val finalGrade: String = "", // letter grade when completed
    val modulesTotal: Int = 0,
    val modulesDone: Int = 0,
    val targetPercent: Double = 90.0,
    val passFail: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "class_meetings",
    indices = [Index("courseId")],
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
)
data class ClassMeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val dayOfWeek: Int, // 1 = Monday … 7 = Sunday
    val startMinute: Int,
    val endMinute: Int,
    val location: String = "",
    val kind: String = "Lecture",
)

@Entity(
    tableName = "grade_categories",
    indices = [Index("courseId")],
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
)
data class GradeCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val name: String,
    val weight: Double, // percent of final grade
)

@Entity(
    tableName = "assignments",
    indices = [Index("courseId")],
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)],
)
data class AssignmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val categoryId: Long? = null,
    val title: String,
    val kind: String = "Homework",
    val dueAt: Long? = null,
    val status: String = "TODO", // TODO, IN_PROGRESS, SUBMITTED, GRADED
    val pointsEarned: Double? = null,
    val pointsPossible: Double? = null,
    val priority: Int = 0,
    val notes: String = "",
    val reminderMinutes: Int? = 1440,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "certifications", indices = [Index("profile")])
data class CertificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val name: String,
    val issuer: String = "",
    val credentialId: String = "",
    val status: String = "STUDYING", // PLANNED, STUDYING, EXAM_SCHEDULED, EARNED, EXPIRED
    val examAt: Long? = null,
    val earnedDay: Long? = null,
    val expiresDay: Long? = null,
    val ceuRequired: Double = 0.0,
    val renewalMonths: Int = 0,
    val url: String = "",
    val filePath: String = "",
    val cost: Double = 0.0,
    val notes: String = "",
    val courseId: Long? = null,
)

@Entity(
    tableName = "ceu_entries",
    indices = [Index("certificationId")],
    foreignKeys = [ForeignKey(entity = CertificationEntity::class, parentColumns = ["id"], childColumns = ["certificationId"], onDelete = ForeignKey.CASCADE)],
)
data class CeuEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val certificationId: Long,
    val day: Long,
    val activity: String,
    val provider: String = "",
    val hours: Double,
)

@Entity(tableName = "study_sessions", indices = [Index("profile")])
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val courseId: Long? = null,
    val startAt: Long,
    val minutes: Int,
    val kind: String = "FOCUS", // FOCUS, MANUAL
    val notes: String = "",
)

@Entity(tableName = "decks", indices = [Index("profile")])
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val name: String,
    val courseId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "flashcards",
    indices = [Index("deckId")],
    foreignKeys = [ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE)],
)
data class FlashcardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val front: String,
    val back: String,
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val lapses: Int = 0,
    val dueDay: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

// ---------------------------------------------------------------- Media

@Entity(tableName = "recordings", indices = [Index(value = ["filePath"], unique = true), Index("profile")])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profile: String,
    val filePath: String,
    val title: String,
    val durationMs: Long = 0,
    val markers: String = "", // "ms|label" entries separated by \n
    val transcript: String = "",
    val notes: String = "",
    val tags: String = "",
    val format: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
