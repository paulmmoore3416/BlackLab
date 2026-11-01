package com.blacklab.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE profile = :profile ORDER BY pinned DESC, updatedAt DESC")
    fun observe(profile: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE courseId = :courseId ORDER BY updatedAt DESC")
    fun observeForCourse(courseId: Long): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Insert suspend fun insert(e: DocumentEntity): Long
    @Update suspend fun update(e: DocumentEntity)
    @Delete suspend fun delete(e: DocumentEntity)
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal WHERE profile = :profile ORDER BY entryDay DESC, createdAt DESC")
    fun observe(profile: String): Flow<List<JournalEntity>>

    @Query("SELECT * FROM journal WHERE id = :id")
    suspend fun get(id: Long): JournalEntity?

    @Query("SELECT * FROM journal WHERE profile = :profile ORDER BY entryDay DESC")
    suspend fun allFor(profile: String): List<JournalEntity>

    @Query("SELECT COUNT(*) FROM journal WHERE profile = :profile AND entryDay = :day")
    suspend fun countOn(profile: String, day: Long): Int

    @Insert suspend fun insert(e: JournalEntity): Long
    @Update suspend fun update(e: JournalEntity)
    @Delete suspend fun delete(e: JournalEntity)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE profile = :profile ORDER BY completed ASC, sortOrder ASC, createdAt DESC")
    fun observe(profile: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE profile IN (:profiles) AND completed = 0 AND dueDay IS NOT NULL")
    fun observeDated(profiles: List<String>): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeOne(id: Long): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE completed = 0 AND reminderAt IS NOT NULL AND reminderAt > :from AND reminderAt <= :to")
    suspend fun remindersBetween(from: Long, to: Long): List<TaskEntity>

    @Query("SELECT MIN(reminderAt) FROM tasks WHERE completed = 0 AND reminderAt IS NOT NULL AND reminderAt > :after")
    suspend fun nextReminder(after: Long): Long?

    @Query("SELECT * FROM tasks WHERE completed = 0 AND profile = :profile AND dueDay IS NOT NULL AND dueDay <= :day")
    suspend fun dueByDay(profile: String, day: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Insert suspend fun insert(e: TaskEntity): Long
    @Update suspend fun update(e: TaskEntity)
    @Delete suspend fun delete(e: TaskEntity)

    @Query("DELETE FROM tasks WHERE profile = :profile AND completed = 1")
    suspend fun clearCompleted(profile: String)

    // lists
    @Query("SELECT * FROM task_lists WHERE profile = :profile ORDER BY sortOrder, name")
    fun observeLists(profile: String): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists WHERE profile = :profile ORDER BY sortOrder, name")
    suspend fun lists(profile: String): List<TaskListEntity>

    @Insert suspend fun insertList(e: TaskListEntity): Long
    @Update suspend fun updateList(e: TaskListEntity)
    @Delete suspend fun deleteList(e: TaskListEntity)

    @Query("UPDATE tasks SET listId = NULL WHERE listId = :listId")
    suspend fun detachList(listId: Long)

    // subtasks
    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY sortOrder, id")
    fun observeSubtasks(taskId: Long): Flow<List<SubtaskEntity>>

    @Query("SELECT s.* FROM subtasks s JOIN tasks t ON s.taskId = t.id WHERE t.profile = :profile")
    fun observeAllSubtasks(profile: String): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY sortOrder, id")
    suspend fun subtasks(taskId: Long): List<SubtaskEntity>

    @Insert suspend fun insertSubtask(e: SubtaskEntity): Long
    @Update suspend fun updateSubtask(e: SubtaskEntity)
    @Delete suspend fun deleteSubtask(e: SubtaskEntity)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE profile IN (:profiles)")
    fun observe(profiles: List<String>): Flow<List<EventEntity>>

    @Query("SELECT * FROM events")
    suspend fun all(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: Long): EventEntity?

    @Insert suspend fun insert(e: EventEntity): Long
    @Update suspend fun update(e: EventEntity)
    @Delete suspend fun delete(e: EventEntity)

    @Query("SELECT * FROM event_calendars WHERE profile IN (:profiles) ORDER BY sortOrder, name")
    fun observeCalendars(profiles: List<String>): Flow<List<EventCalendarEntity>>

    @Query("SELECT * FROM event_calendars WHERE profile = :profile ORDER BY sortOrder, name")
    suspend fun calendars(profile: String): List<EventCalendarEntity>

    @Query("SELECT * FROM event_calendars")
    suspend fun allCalendars(): List<EventCalendarEntity>

    @Insert suspend fun insertCalendar(e: EventCalendarEntity): Long
    @Update suspend fun updateCalendar(e: EventCalendarEntity)
    @Delete suspend fun deleteCalendar(e: EventCalendarEntity)

    @Query("UPDATE events SET calendarId = NULL WHERE calendarId = :id")
    suspend fun detachCalendar(id: Long)
}

@Dao
interface EducationDao {
    // terms
    @Query("SELECT * FROM terms WHERE profile = :profile ORDER BY startDay DESC")
    fun observeTerms(profile: String): Flow<List<TermEntity>>
    @Query("SELECT * FROM terms")
    suspend fun allTerms(): List<TermEntity>
    @Insert suspend fun insertTerm(e: TermEntity): Long
    @Update suspend fun updateTerm(e: TermEntity)
    @Delete suspend fun deleteTerm(e: TermEntity)
    @Query("UPDATE courses SET termId = NULL WHERE termId = :termId")
    suspend fun detachTerm(termId: Long)

    // courses
    @Query("SELECT * FROM courses WHERE profile IN (:profiles) ORDER BY status = 'COMPLETED', code, title")
    fun observeCourses(profiles: List<String>): Flow<List<CourseEntity>>
    @Query("SELECT * FROM courses WHERE id = :id")
    fun observeCourse(id: Long): Flow<CourseEntity?>
    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun course(id: Long): CourseEntity?
    @Query("SELECT * FROM courses")
    suspend fun allCourses(): List<CourseEntity>
    @Insert suspend fun insertCourse(e: CourseEntity): Long
    @Update suspend fun updateCourse(e: CourseEntity)
    @Delete suspend fun deleteCourse(e: CourseEntity)

    // meetings
    @Query("SELECT m.* FROM class_meetings m JOIN courses c ON m.courseId = c.id WHERE c.profile IN (:profiles)")
    fun observeMeetings(profiles: List<String>): Flow<List<ClassMeetingEntity>>
    @Query("SELECT * FROM class_meetings")
    suspend fun allMeetings(): List<ClassMeetingEntity>
    @Insert suspend fun insertMeeting(e: ClassMeetingEntity): Long
    @Update suspend fun updateMeeting(e: ClassMeetingEntity)
    @Delete suspend fun deleteMeeting(e: ClassMeetingEntity)

    // grade categories
    @Query("SELECT g.* FROM grade_categories g JOIN courses c ON g.courseId = c.id WHERE c.profile = :profile")
    fun observeCategories(profile: String): Flow<List<GradeCategoryEntity>>
    @Insert suspend fun insertCategory(e: GradeCategoryEntity): Long
    @Update suspend fun updateCategory(e: GradeCategoryEntity)
    @Delete suspend fun deleteCategory(e: GradeCategoryEntity)

    // assignments
    @Query("SELECT a.* FROM assignments a JOIN courses c ON a.courseId = c.id WHERE c.profile IN (:profiles) ORDER BY a.dueAt IS NULL, a.dueAt")
    fun observeAssignments(profiles: List<String>): Flow<List<AssignmentEntity>>
    @Query("SELECT * FROM assignments")
    suspend fun allAssignments(): List<AssignmentEntity>
    @Query("SELECT * FROM assignments WHERE id = :id")
    suspend fun assignment(id: Long): AssignmentEntity?
    @Insert suspend fun insertAssignment(e: AssignmentEntity): Long
    @Update suspend fun updateAssignment(e: AssignmentEntity)
    @Delete suspend fun deleteAssignment(e: AssignmentEntity)

    // certifications
    @Query("SELECT * FROM certifications WHERE profile = :profile ORDER BY status = 'EXPIRED', expiresDay IS NULL, expiresDay, name")
    fun observeCerts(profile: String): Flow<List<CertificationEntity>>
    @Query("SELECT * FROM certifications")
    suspend fun allCerts(): List<CertificationEntity>
    @Insert suspend fun insertCert(e: CertificationEntity): Long
    @Update suspend fun updateCert(e: CertificationEntity)
    @Delete suspend fun deleteCert(e: CertificationEntity)

    @Query("SELECT e.* FROM ceu_entries e JOIN certifications c ON e.certificationId = c.id WHERE c.profile = :profile ORDER BY e.day DESC")
    fun observeCeus(profile: String): Flow<List<CeuEntryEntity>>
    @Insert suspend fun insertCeu(e: CeuEntryEntity): Long
    @Delete suspend fun deleteCeu(e: CeuEntryEntity)

    // study
    @Query("SELECT * FROM study_sessions WHERE profile = :profile ORDER BY startAt DESC")
    fun observeSessions(profile: String): Flow<List<StudySessionEntity>>
    @Insert suspend fun insertSession(e: StudySessionEntity): Long
    @Delete suspend fun deleteSession(e: StudySessionEntity)

    // flashcards
    @Query("SELECT * FROM decks WHERE profile = :profile ORDER BY name")
    fun observeDecks(profile: String): Flow<List<DeckEntity>>
    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun deck(id: Long): DeckEntity?
    @Insert suspend fun insertDeck(e: DeckEntity): Long
    @Update suspend fun updateDeck(e: DeckEntity)
    @Delete suspend fun deleteDeck(e: DeckEntity)

    @Query("SELECT f.* FROM flashcards f JOIN decks d ON f.deckId = d.id WHERE d.profile = :profile")
    fun observeAllCards(profile: String): Flow<List<FlashcardEntity>>
    @Query("SELECT * FROM flashcards WHERE deckId = :deckId ORDER BY createdAt")
    fun observeCards(deckId: Long): Flow<List<FlashcardEntity>>
    @Query("SELECT * FROM flashcards WHERE deckId = :deckId AND dueDay <= :day ORDER BY dueDay, id")
    suspend fun dueCards(deckId: Long, day: Long): List<FlashcardEntity>
    @Insert suspend fun insertCard(e: FlashcardEntity): Long
    @Update suspend fun updateCard(e: FlashcardEntity)
    @Delete suspend fun deleteCard(e: FlashcardEntity)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE profile = :profile ORDER BY createdAt DESC")
    fun observe(profile: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE profile = :profile ORDER BY createdAt DESC")
    suspend fun allFor(profile: String): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE filePath = :path LIMIT 1")
    suspend fun byPath(path: String): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeOne(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(e: RecordingEntity): Long
    @Update suspend fun update(e: RecordingEntity)
    @Delete suspend fun delete(e: RecordingEntity)
}

data class NotebookCount(val notebookId: Long, val pages: Int, val sections: Int)

@Dao
interface BrainstormDao {
    // notebooks
    @Query("SELECT * FROM notebooks WHERE profile = :profile ORDER BY sortOrder ASC, createdAt ASC")
    fun observeNotebooks(profile: String): Flow<List<NotebookEntity>>
    @Query("SELECT * FROM notebooks WHERE profile = :profile ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun notebooks(profile: String): List<NotebookEntity>
    @Query("SELECT * FROM notebooks") suspend fun allNotebooks(): List<NotebookEntity>
    @Query("SELECT * FROM notebooks WHERE id = :id") suspend fun notebook(id: Long): NotebookEntity?
    @Query("SELECT * FROM notebooks WHERE id = :id") fun observeNotebook(id: Long): Flow<NotebookEntity?>
    @Insert suspend fun insertNotebook(e: NotebookEntity): Long
    @Update suspend fun updateNotebook(e: NotebookEntity)
    @Delete suspend fun deleteNotebook(e: NotebookEntity)
    @Query(
        "SELECT n.id AS notebookId, " +
            "(SELECT COUNT(*) FROM nb_pages p JOIN nb_sections s ON p.sectionId = s.id WHERE s.notebookId = n.id AND p.deletedAt IS NULL) AS pages, " +
            "(SELECT COUNT(*) FROM nb_sections s WHERE s.notebookId = n.id) AS sections FROM notebooks n WHERE n.profile = :profile"
    )
    fun observeCounts(profile: String): Flow<List<NotebookCount>>

    // sections
    @Query("SELECT * FROM nb_sections WHERE notebookId = :notebookId ORDER BY sortOrder ASC, id ASC")
    fun observeSections(notebookId: Long): Flow<List<SectionEntity>>
    @Query("SELECT * FROM nb_sections WHERE notebookId = :notebookId ORDER BY sortOrder ASC, id ASC")
    suspend fun sections(notebookId: Long): List<SectionEntity>
    @Query("SELECT * FROM nb_sections") suspend fun allSections(): List<SectionEntity>
    @Query("SELECT * FROM nb_sections WHERE id = :id") suspend fun section(id: Long): SectionEntity?
    @Insert suspend fun insertSection(e: SectionEntity): Long
    @Update suspend fun updateSection(e: SectionEntity)
    @Delete suspend fun deleteSection(e: SectionEntity)

    // pages
    @Query("SELECT * FROM nb_pages WHERE sectionId = :sectionId AND deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    fun observePages(sectionId: Long): Flow<List<PageEntity>>
    @Query("SELECT * FROM nb_pages WHERE sectionId = :sectionId AND deletedAt IS NULL ORDER BY sortOrder ASC, id ASC")
    suspend fun pages(sectionId: Long): List<PageEntity>
    @Query("SELECT * FROM nb_pages WHERE sectionId = :sectionId") suspend fun allPagesIn(sectionId: Long): List<PageEntity>
    @Query("SELECT * FROM nb_pages") suspend fun allPages(): List<PageEntity>
    @Query("SELECT * FROM nb_pages WHERE id = :id") suspend fun page(id: Long): PageEntity?
    @Query("SELECT * FROM nb_pages WHERE id = :id") fun observePage(id: Long): Flow<PageEntity?>
    @Query("SELECT * FROM nb_pages WHERE profile = :profile AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(profile: String, limit: Int): Flow<List<PageEntity>>
    @Query("SELECT * FROM nb_pages WHERE profile = :profile AND deletedAt IS NULL AND (title LIKE :q OR searchText LIKE :q) ORDER BY updatedAt DESC LIMIT 200")
    suspend fun search(profile: String, q: String): List<PageEntity>
    @Query("SELECT * FROM nb_pages WHERE profile = :profile AND deletedAt IS NULL AND tagIndex != '' ORDER BY updatedAt DESC")
    fun observeTagged(profile: String): Flow<List<PageEntity>>
    @Query("SELECT * FROM nb_pages WHERE profile = :profile AND deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeDeleted(profile: String): Flow<List<PageEntity>>
    @Query("SELECT * FROM nb_pages WHERE deletedAt IS NOT NULL AND deletedAt < :before") suspend fun deletedBefore(before: Long): List<PageEntity>
    @Query("SELECT * FROM nb_pages WHERE deletedAt IS NULL AND reminderAt IS NOT NULL AND reminderAt > :from AND reminderAt <= :to")
    suspend fun remindersBetween(from: Long, to: Long): List<PageEntity>
    @Insert suspend fun insertPage(e: PageEntity): Long
    @Update suspend fun updatePage(e: PageEntity)
    @Delete suspend fun deletePage(e: PageEntity)

    // comments
    @Query("SELECT * FROM bs_comments WHERE pageId = :pageId ORDER BY createdAt ASC") fun observePageComments(pageId: Long): Flow<List<CommentEntity>>
    @Query("SELECT * FROM bs_comments WHERE noteId = :noteId ORDER BY createdAt ASC") fun observeNoteComments(noteId: Long): Flow<List<CommentEntity>>
    @Query("SELECT * FROM bs_comments WHERE pageId = :pageId ORDER BY createdAt ASC") suspend fun pageComments(pageId: Long): List<CommentEntity>
    @Query("SELECT * FROM bs_comments WHERE noteId = :noteId ORDER BY createdAt ASC") suspend fun noteComments(noteId: Long): List<CommentEntity>
    @Query("SELECT * FROM bs_comments WHERE guest = 1 AND resolved = 0 ORDER BY createdAt DESC") fun observeGuestComments(): Flow<List<CommentEntity>>
    @Insert suspend fun insertComment(e: CommentEntity): Long
    @Update suspend fun updateComment(e: CommentEntity)
    @Delete suspend fun deleteComment(e: CommentEntity)
    @Query("DELETE FROM bs_comments WHERE pageId = :pageId") suspend fun deletePageComments(pageId: Long)
    @Query("DELETE FROM bs_comments WHERE noteId = :noteId") suspend fun deleteNoteComments(noteId: Long)

    // live share links
    @Query("SELECT * FROM bs_shares ORDER BY createdAt DESC") fun observeShares(): Flow<List<ShareLinkEntity>>
    @Query("SELECT * FROM bs_shares WHERE token = :token") suspend fun share(token: String): ShareLinkEntity?
    @Query("SELECT * FROM bs_shares WHERE kind = :kind AND refId = :refId AND revoked = 0 ORDER BY createdAt DESC") suspend fun sharesFor(kind: String, refId: Long): List<ShareLinkEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putShare(e: ShareLinkEntity)
    @Delete suspend fun deleteShare(e: ShareLinkEntity)
    @Query("DELETE FROM bs_shares WHERE kind = :kind AND refId = :refId") suspend fun deleteSharesFor(kind: String, refId: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE profile = :profile ORDER BY pinned DESC, sortOrder DESC, updatedAt DESC")
    fun observe(profile: String): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE profile = :profile") suspend fun allFor(profile: String): List<NoteEntity>
    @Query("SELECT * FROM notes") suspend fun all(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun get(id: Long): NoteEntity?
    @Query("SELECT * FROM notes WHERE id = :id") fun observeNote(id: Long): Flow<NoteEntity?>
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND archived = 0 AND reminderAt IS NOT NULL AND reminderAt > :from AND reminderAt <= :to")
    suspend fun remindersBetween(from: Long, to: Long): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE trashedAt IS NULL AND archived = 0 AND placeLat IS NOT NULL AND placeLon IS NOT NULL")
    suspend fun withPlaces(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE trashedAt IS NOT NULL AND trashedAt < :before") suspend fun trashedBefore(before: Long): List<NoteEntity>
    @Query("SELECT MAX(sortOrder) FROM notes WHERE profile = :profile") suspend fun maxOrder(profile: String): Long?
    @Insert suspend fun insert(e: NoteEntity): Long
    @Update suspend fun update(e: NoteEntity)
    @Delete suspend fun delete(e: NoteEntity)

    @Query("SELECT * FROM note_labels WHERE profile = :profile ORDER BY name COLLATE NOCASE")
    fun observeLabels(profile: String): Flow<List<NoteLabelEntity>>
    @Query("SELECT * FROM note_labels WHERE profile = :profile ORDER BY name COLLATE NOCASE")
    suspend fun labels(profile: String): List<NoteLabelEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLabel(e: NoteLabelEntity): Long
    @Update suspend fun updateLabel(e: NoteLabelEntity)
    @Delete suspend fun deleteLabel(e: NoteLabelEntity)
}
