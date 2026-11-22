import * as SQLite from 'expo-sqlite';

export const database = SQLite.openDatabaseAsync('campusos.db');

export async function initializeDatabase() {
  const db = await database;
  await db.execAsync(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS profile (id INTEGER PRIMARY KEY CHECK (id = 1), full_name TEXT NOT NULL DEFAULT '', student_number TEXT NOT NULL DEFAULT '', year_section TEXT NOT NULL DEFAULT '', photo_uri TEXT NOT NULL DEFAULT '');
    CREATE TABLE IF NOT EXISTS subjects (id TEXT PRIMARY KEY NOT NULL, code TEXT NOT NULL DEFAULT '', title TEXT NOT NULL, color TEXT NOT NULL DEFAULT '#176B3A', created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS schedule_entries (id TEXT PRIMARY KEY NOT NULL, subject_id TEXT, title TEXT NOT NULL, day INTEGER NOT NULL, start_time TEXT NOT NULL, end_time TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'Lecture', room TEXT NOT NULL DEFAULT '', professor TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '', FOREIGN KEY(subject_id) REFERENCES subjects(id) ON DELETE SET NULL);
    CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, details TEXT NOT NULL DEFAULT '', due_date TEXT NOT NULL DEFAULT '', due_time TEXT NOT NULL DEFAULT '', done INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, subject_id TEXT, created_at INTEGER NOT NULL, FOREIGN KEY(subject_id) REFERENCES subjects(id) ON DELETE SET NULL);
    CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', subject_id TEXT, favorite INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, FOREIGN KEY(subject_id) REFERENCES subjects(id) ON DELETE SET NULL);
    CREATE TABLE IF NOT EXISTS lecture_files (id TEXT PRIMARY KEY NOT NULL, subject_id TEXT, name TEXT NOT NULL, mime_type TEXT NOT NULL DEFAULT '', local_uri TEXT NOT NULL, size INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL, FOREIGN KEY(subject_id) REFERENCES subjects(id) ON DELETE SET NULL);
    CREATE TABLE IF NOT EXISTS budget_entries (id TEXT PRIMARY KEY NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, category TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '', date TEXT NOT NULL, created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS savings_goals (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, target REAL NOT NULL, saved REAL NOT NULL DEFAULT 0, due_date TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS home_tiles (tile_key TEXT PRIMARY KEY NOT NULL, visible INTEGER NOT NULL DEFAULT 1, position INTEGER NOT NULL DEFAULT 0);
  `);
  await db.runAsync('INSERT OR IGNORE INTO profile (id) VALUES (1)');
}
