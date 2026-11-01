package com.blacklab.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    /** 4.2.0: Brainstorm — notebooks, sections, pages, notes, labels, comments, Live Share links. */
    val M1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SQL_1_2.forEach { db.execSQL(it) }
        }
    }

    // Generated from app/schemas/.../2.json so the tables match what Room expects exactly.
    internal val SQL_1_2 = listOf(
        "CREATE TABLE IF NOT EXISTS `notebooks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile` TEXT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `emoji` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_notebooks_profile` ON `notebooks` (`profile`)",
        "CREATE TABLE IF NOT EXISTS `nb_sections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `notebookId` INTEGER NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL, `groupName` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `lockSalt` TEXT NOT NULL, `lockCheck` TEXT NOT NULL, `lockHint` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`notebookId`) REFERENCES `notebooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_nb_sections_notebookId` ON `nb_sections` (`notebookId`)",
        "CREATE TABLE IF NOT EXISTS `nb_pages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile` TEXT NOT NULL, `sectionId` INTEGER NOT NULL, `title` TEXT NOT NULL, `level` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `preview` TEXT NOT NULL, `searchText` TEXT NOT NULL, `tagIndex` TEXT NOT NULL, `wordCount` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `reminderAt` INTEGER, `deletedAt` INTEGER, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_nb_pages_sectionId` ON `nb_pages` (`sectionId`)",
        "CREATE INDEX IF NOT EXISTS `index_nb_pages_profile` ON `nb_pages` (`profile`)",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile` TEXT NOT NULL, `title` TEXT NOT NULL, `body` TEXT NOT NULL, `checklist` INTEGER NOT NULL, `items` TEXT NOT NULL, `color` INTEGER NOT NULL, `bg` TEXT NOT NULL, `pinned` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `trashedAt` INTEGER, `labels` TEXT NOT NULL, `reminderAt` INTEGER, `repeatRule` TEXT, `placeName` TEXT NOT NULL, `placeLat` REAL, `placeLon` REAL, `placeRadius` INTEGER NOT NULL, `placeOnLeave` INTEGER NOT NULL, `attachments` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_notes_profile` ON `notes` (`profile`)",
        "CREATE TABLE IF NOT EXISTS `note_labels` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile` TEXT NOT NULL, `name` TEXT NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_note_labels_profile_name` ON `note_labels` (`profile`, `name`)",
        "CREATE TABLE IF NOT EXISTS `bs_comments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pageId` INTEGER, `noteId` INTEGER, `parentId` INTEGER, `author` TEXT NOT NULL, `text` TEXT NOT NULL, `guest` INTEGER NOT NULL, `resolved` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_bs_comments_pageId` ON `bs_comments` (`pageId`)",
        "CREATE INDEX IF NOT EXISTS `index_bs_comments_noteId` ON `bs_comments` (`noteId`)",
        "CREATE TABLE IF NOT EXISTS `bs_shares` (`token` TEXT NOT NULL, `kind` TEXT NOT NULL, `refId` INTEGER NOT NULL, `title` TEXT NOT NULL, `permission` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `revoked` INTEGER NOT NULL, `opens` INTEGER NOT NULL, PRIMARY KEY(`token`))",
    )
}
