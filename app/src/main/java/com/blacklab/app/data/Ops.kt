package com.blacklab.app.data

import com.blacklab.app.AppContainer
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import java.time.LocalDate

/** Shared write operations used by screens, notifications and widgets. */
object TaskOps {

    suspend fun save(app: AppContainer, task: TaskEntity): Long {
        val dao = app.db.tasks()
        val id = if (task.id == 0L) dao.insert(task) else { dao.update(task.copy(updatedAt = Time.now())); task.id }
        app.requestReminderSync()
        return id
    }

    suspend fun setCompleted(app: AppContainer, task: TaskEntity, done: Boolean) {
        val dao = app.db.tasks()
        if (!done) {
            dao.update(task.copy(completed = false, completedAt = null, updatedAt = Time.now()))
            app.requestReminderSync()
            return
        }
        dao.update(task.copy(completed = true, completedAt = Time.now(), updatedAt = Time.now()))
        val rule = Recurrence.parse(task.repeatRule)
        if (rule != null) {
            val anchor = task.dueDay?.let(LocalDate::ofEpochDay) ?: Time.today()
            val next = rule.nextAfter(anchor, maxOf(anchor, if (task.dueDay == null) Time.today() else anchor))
            if (next != null) {
                val shift = next.toEpochDay() - anchor.toEpochDay()
                val newReminder = task.reminderAt?.let { it + shift * 86_400_000L }
                val newId = dao.insert(
                    task.copy(
                        id = 0, completed = false, completedAt = null, dueDay = next.toEpochDay(),
                        reminderAt = newReminder, myDayDay = null, createdAt = Time.now(), updatedAt = Time.now(),
                    )
                )
                dao.subtasks(task.id).forEach { dao.insertSubtask(it.copy(id = 0, taskId = newId, done = false)) }
            }
        }
        app.requestReminderSync()
    }

    suspend fun delete(app: AppContainer, task: TaskEntity) {
        app.db.tasks().delete(task)
        app.requestReminderSync()
    }
}

/** SM-2 spaced repetition. grade: 0 = again, 1 = hard, 2 = good, 3 = easy */
object Srs {
    fun review(card: FlashcardEntity, grade: Int, today: Long = Time.today().toEpochDay()): FlashcardEntity {
        val q = when (grade) { 0 -> 1; 1 -> 3; 2 -> 4; else -> 5 }
        var ease = card.ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))
        ease = ease.coerceAtLeast(1.3)
        return if (q < 3) {
            card.copy(repetitions = 0, intervalDays = 0, lapses = card.lapses + 1, ease = ease, dueDay = today)
        } else {
            val reps = card.repetitions + 1
            val interval = when (reps) {
                1 -> if (grade == 3) 3 else 1
                2 -> if (grade == 1) 4 else 6
                else -> Math.round(card.intervalDays * ease * (if (grade == 1) 0.8 else if (grade == 3) 1.3 else 1.0)).toInt().coerceAtLeast(1)
            }
            card.copy(repetitions = reps, intervalDays = interval, ease = ease, dueDay = today + interval)
        }
    }
}

/** Letter grade scale + GPA math. */
object Grades {
    data class Step(val letter: String, val minPercent: Double, val points: Double)

    val scale = listOf(
        Step("A", 93.0, 4.0), Step("A-", 90.0, 3.7), Step("B+", 87.0, 3.3), Step("B", 83.0, 3.0), Step("B-", 80.0, 2.7),
        Step("C+", 77.0, 2.3), Step("C", 73.0, 2.0), Step("C-", 70.0, 1.7), Step("D+", 67.0, 1.3), Step("D", 63.0, 1.0),
        Step("D-", 60.0, 0.7), Step("F", 0.0, 0.0),
    )
    val letters = scale.map { it.letter }

    fun letter(percent: Double): String = scale.first { percent >= it.minPercent }.letter
    fun points(letter: String): Double? = scale.firstOrNull { it.letter.equals(letter.trim(), true) }?.points

    /**
     * Current weighted percentage for a course. Categories without graded work are
     * excluded and the remaining weights are re-normalized.
     */
    fun coursePercent(assignments: List<AssignmentEntity>, categories: List<GradeCategoryEntity>): Double? {
        val graded = assignments.filter { it.pointsEarned != null && (it.pointsPossible ?: 0.0) > 0 }
        if (graded.isEmpty()) return null
        if (categories.isEmpty() || categories.sumOf { it.weight } <= 0.0) {
            return graded.sumOf { it.pointsEarned!! } / graded.sumOf { it.pointsPossible!! } * 100
        }
        var weighted = 0.0
        var weightUsed = 0.0
        for (cat in categories) {
            val inCat = graded.filter { it.categoryId == cat.id }
            if (inCat.isEmpty()) continue
            weighted += inCat.sumOf { it.pointsEarned!! } / inCat.sumOf { it.pointsPossible!! } * cat.weight
            weightUsed += cat.weight
        }
        val uncategorized = graded.filter { a -> categories.none { it.id == a.categoryId } }
        if (uncategorized.isNotEmpty() && weightUsed == 0.0) {
            return uncategorized.sumOf { it.pointsEarned!! } / uncategorized.sumOf { it.pointsPossible!! } * 100
        }
        return if (weightUsed > 0) weighted / weightUsed * 100 else null
    }

    /** Percentage needed on all remaining (ungraded) weight to finish with [target]. */
    fun neededOnRemaining(assignments: List<AssignmentEntity>, categories: List<GradeCategoryEntity>, target: Double): Double? {
        if (categories.isEmpty()) return null
        val total = categories.sumOf { it.weight }
        if (total <= 0) return null
        var earnedWeight = 0.0
        var gradedWeight = 0.0
        for (cat in categories) {
            val inCat = assignments.filter { it.categoryId == cat.id }
            val graded = inCat.filter { it.pointsEarned != null && (it.pointsPossible ?: 0.0) > 0 }
            if (graded.isEmpty()) continue
            val possibleAll = inCat.sumOf { it.pointsPossible ?: 0.0 }
            val possibleGraded = graded.sumOf { it.pointsPossible!! }
            val fractionGraded = if (possibleAll > 0) (possibleGraded / possibleAll).coerceIn(0.0, 1.0) else 1.0
            val catPct = graded.sumOf { it.pointsEarned!! } / possibleGraded
            earnedWeight += catPct * cat.weight * fractionGraded
            gradedWeight += cat.weight * fractionGraded
        }
        val remaining = total - gradedWeight
        if (remaining <= 0.0001) return null
        return ((target / 100 * total) - earnedWeight) / remaining * 100
    }

    data class Gpa(val gpa: Double?, val credits: Double, val qualityPoints: Double)

    fun gpa(courses: List<CourseEntity>): Gpa {
        val counted = courses.filter { it.kind == "COLLEGE" && !it.passFail && it.status == "COMPLETED" && points(it.finalGrade) != null }
        val credits = counted.sumOf { it.credits }
        val qp = counted.sumOf { it.credits * points(it.finalGrade)!! }
        return Gpa(if (credits > 0) qp / credits else null, credits, qp)
    }
}
