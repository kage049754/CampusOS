import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import { database } from './database';
import { readStoredFileBase64, writeStoredFileBase64 } from './storage';

export type BackupModule = 'homepage' | 'schedule' | 'tasks' | 'academics' | 'notepad' | 'budget';
export const backupModules: { key: BackupModule; label: string; description: string }[] = [
  { key: 'homepage', label: 'Home & profile', description: 'Profile, color theme, display mode, and dashboard layout' },
  { key: 'schedule', label: 'Schedule', description: 'Classes, Lecture/Lab type, timetable, and schedule settings' },
  { key: 'tasks', label: 'Tasks & reminders', description: 'Deadlines, checklists, and task status' },
  { key: 'academics', label: 'Academics', description: 'Subjects, subject Notepad entries, favorites, and lecture files' },
  { key: 'notepad', label: 'Personal Notepad', description: 'General notes and dated entries' },
  { key: 'budget', label: 'Budget & savings', description: 'Transactions and savings goals' }
];

type AnyRow = Record<string, unknown>;
type Subject = { id: string; legacy_id?: string | null; code: string; title: string; color: string; favorite?: number; created_at: number };
type FileRow = { id: string; subject_id: string | null; name: string; mime_type: string; local_uri: string; size: number; added_at: number; folder_name?: string; favorite?: number };

const now = () => Date.now();
const uid = (prefix: string) => prefix + '-' + now().toString(36) + '-' + Math.random().toString(36).slice(2, 7);
const parseMaybe = (value: unknown): any => {
  if (typeof value === 'string') {
    try { return JSON.parse(value); } catch { return value; }
  }
  return value;
};
const safeObject = (value: unknown): AnyRow => value && typeof value === 'object' && !Array.isArray(value) ? value as AnyRow : {};
const safeArray = (value: unknown): AnyRow[] => {
  const parsed = parseMaybe(value);
  return Array.isArray(parsed) ? parsed.map(safeObject) : [];
};
const asText = (value: unknown, fallback = ''): string => typeof value === 'string' ? value : value == null ? fallback : String(value);
const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const getLegacySubjectId = (subject: Subject, index: number): string =>
  subject.legacy_id || String(Math.max(1, Math.trunc(Number(subject.created_at) || now()) + index));

async function currentTables() {
  const db = await database;
  const [profile, settings, tiles, schedule, tasks, subjects, notes, files, folders, budget, savings] = await Promise.all([
    db.getAllAsync<AnyRow>('SELECT * FROM profile'),
    db.getAllAsync<AnyRow>('SELECT * FROM app_settings'),
    db.getAllAsync<AnyRow>('SELECT * FROM home_tiles ORDER BY position'),
    db.getAllAsync<AnyRow>('SELECT * FROM schedule_entries ORDER BY day,start_time'),
    db.getAllAsync<AnyRow>('SELECT * FROM tasks ORDER BY due_date,due_time'),
    db.getAllAsync<Subject>('SELECT * FROM subjects ORDER BY created_at,title'),
    db.getAllAsync<AnyRow>('SELECT * FROM notes ORDER BY updated_at DESC'),
    db.getAllAsync<FileRow>('SELECT * FROM lecture_files ORDER BY added_at DESC'),
    db.getAllAsync<AnyRow>('SELECT * FROM lecture_folders ORDER BY created_at,name'),
    db.getAllAsync<AnyRow>('SELECT * FROM budget_entries ORDER BY date DESC,created_at DESC'),
    db.getAllAsync<AnyRow>('SELECT * FROM savings_goals ORDER BY created_at DESC')
  ]);
  return { profile, settings, tiles, schedule, tasks, subjects, notes, files, folders, budget, savings };
}

export async function createCampusBackup(selected: Set<BackupModule>): Promise<string> {
  const db = await database;
  const data: AnyRow = {};
  const mobileData: AnyRow = { format: 'CampusOS Mobile Data', version: 1, modules: Array.from(selected).sort(), tables: data, filePayloads: [] as AnyRow[] };
  const root: AnyRow = {
    format: 'CampusOS Backup',
    version: 2,
    createdAt: now(),
    modules: Array.from(selected).sort(),
    source: 'CampusOS React Native',
    mobileData
  };
  const current = await currentTables();
  const filePayloads: AnyRow[] = [];
  const subjectFiles: AnyRow[] = [];
  root.subjectFiles = subjectFiles;

  if (selected.has('homepage')) {
    data.profile = current.profile;
    data.app_settings = current.settings;
    data.home_tiles = current.tiles;
    const p = current.profile[0] || {};
    const settings = Object.fromEntries(current.settings.map(row => [asText(row.key), asText(row.value)]));
    root.theme = 'light';
    root.appearancePreset = settings.palette || 'forest';
    root.profileName = asText(p.full_name);
    root.profileStudentId = asText(p.student_number);
    root.profileSection = asText(p.year_section);
    root.homeLayoutOrder = settings.home_layout_order || '';
    root.homeHiddenTiles = settings.home_hidden_tiles || '';
  }

  if (selected.has('schedule')) {
    data.schedule_entries = current.schedule;
    const legacySubjects = current.subjects;
    const legacyIds = new Map<string, string>();
    legacySubjects.forEach((subject, index) => legacyIds.set(subject.id, getLegacySubjectId(subject, index)));
    const legacySchedule = current.schedule.map((row: AnyRow) => ({
      id: Number(row.created_at) || now(),
      title: row.title,
      subtitle: '',
      extra: row.notes || '',
      value: 0,
      done: false,
      day: weekdays[Number(row.day)] || weekdays[0],
      startTime: row.start_time,
      endTime: row.end_time,
      room: row.room,
      professor: row.professor,
      color: 0,
      classType: row.kind,
      subjectId: row.subject_id ? Number(legacyIds.get(String(row.subject_id)) || 0) : 0,
      dueDate: '',
      dueTime: ''
    }));
    root.schedule = JSON.stringify(legacySchedule);
    root.scheduleDays = 'Monday,Tuesday,Wednesday,Thursday,Friday,Saturday';
    root.scheduleDaysConfigured = true;
    root.scheduleStartHour = 7;
    root.scheduleEndHour = 21;
  }

  if (selected.has('tasks')) {
    data.tasks = current.tasks;
    root.tasks = JSON.stringify(current.tasks.map((row: AnyRow) => ({
      id: Number(row.created_at) || now(),
      title: row.title,
      subtitle: row.details,
      extra: '',
      value: 0,
      done: Boolean(row.done),
      day: '',
      startTime: '',
      endTime: '',
      room: '',
      professor: '',
      color: 0,
      classType: 'Lecture',
      subjectId: 0,
      dueDate: row.due_date,
      dueTime: row.due_time
    })));
  }

  if (selected.has('academics')) {
    data.subjects = current.subjects;
    data.notes = current.notes.filter(row => row.subject_id != null);
    data.lecture_files = current.files;
    data.lecture_folders = current.folders;
    const legacyIdBySubject = new Map<string, string>();
    for (let i = 0; i < current.subjects.length; i++) {
      const subject = current.subjects[i];
      const legacyId = getLegacySubjectId(subject, i);
      legacyIdBySubject.set(subject.id, legacyId);
      if (!subject.legacy_id) await db.runAsync('UPDATE subjects SET legacy_id=? WHERE id=?', legacyId, subject.id);
    }
    root.subjects = JSON.stringify(current.subjects.map((subject, index) => ({
      id: Number(getLegacySubjectId(subject, index)),
      title: subject.title,
      subtitle: subject.code || '',
      extra: '',
      value: 0,
      done: Boolean(subject.favorite),
      day: '',
      startTime: '',
      endTime: '',
      room: '',
      professor: '',
      color: 0,
      classType: 'Lecture',
      subjectId: 0,
      dueDate: '',
      dueTime: ''
    })));
    const groupedNotes: AnyRow = {};
    const favorites: AnyRow = {};
    current.subjects.forEach((subject, index) => {
      const legacyId = getLegacySubjectId(subject, index);
      const rows = current.notes.filter(note => String(note.subject_id) === subject.id);
      groupedNotes[legacyId] = JSON.stringify(rows.map(note => ({
        id: Number(note.created_at) || now(),
        title: note.title,
        body: note.body,
        updatedAt: Number(note.updated_at) || now(),
        favorite: Boolean(note.favorite),
        order: Number(note.updated_at) || now()
      })));
      if (subject.favorite) favorites[legacyId] = true;
    });
    root.subjectNotes = groupedNotes;
    root.subjectFavorites = favorites;
    for (const file of current.files) {
      const legacyId = file.subject_id ? legacyIdBySubject.get(file.subject_id) : null;
      const base64 = await readStoredFileBase64(file.local_uri);
      const relativePath = (legacyId || 'General') + '/' + (file.folder_name || 'Lecture 1') + '/' + file.name;
      filePayloads.push({ id: file.id, path: relativePath, subject_id: file.subject_id, name: file.name, mime_type: file.mime_type, size: file.size, added_at: file.added_at, folder_name: file.folder_name || 'Lecture 1', favorite: Boolean(file.favorite), data: base64 });
      subjectFiles.push({ path: relativePath, data: base64 });
    }
  }

  if (selected.has('notepad')) data.notes = current.notes.filter(row => row.subject_id == null);

  if (selected.has('budget')) {
    data.budget_entries = current.budget;
    data.savings_goals = current.savings;
  }

  mobileData.filePayloads = filePayloads;
  return JSON.stringify(root, null, 2);
}

async function upsertRows(db: any, table: string, rows: AnyRow[]) {
  const allowed: Record<string, string[]> = {
    app_settings: ['key','value'],
    profile: ['id','full_name','student_number','year_section','photo_uri'],
    schedule_entries: ['id','subject_id','title','day','start_time','end_time','kind','room','professor','notes'],
    tasks: ['id','title','details','due_date','due_time','done','pinned','subject_id','created_at'],
    subjects: ['id','legacy_id','code','title','color','created_at'],
    notes: ['id','title','body','subject_id','favorite','note_date','created_at','updated_at'],
    lecture_files: ['id','subject_id','name','mime_type','local_uri','size','added_at','folder_name','favorite'],
    lecture_folders: ['id','subject_id','name','created_at'],
    budget_entries: ['id','type','amount','category','note','date','created_at'],
    savings_goals: ['id','title','target','saved','due_date','created_at'],
    home_tiles: ['tile_key','visible','position']
  };
  const columns = allowed[table];
  if (!columns) throw new Error('Unsupported backup data table: ' + table);
  for (const row of rows) {
    const entries = columns.filter(column => row[column] !== undefined);
    if (entries.length === 0) continue;
    const values = entries.map(column => row[column]);
    const placeholders = entries.map(() => '?').join(',');
    await db.runAsync('INSERT OR REPLACE INTO ' + table + ' (' + entries.join(',') + ') VALUES (' + placeholders + ')', ...values);
  }
}

async function restoreMobileData(root: AnyRow, selected: Set<BackupModule>) {
  const mobile = safeObject(root.mobileData);
  const tables = safeObject(mobile.tables);
  const payloads = Array.isArray(mobile.filePayloads) ? mobile.filePayloads.map(safeObject) : [];
  const db = await database;

  await db.withTransactionAsync(async () => {
    if (selected.has('homepage')) {
      const profiles = safeArray(tables.profile);
      if (profiles.length) {
        await db.runAsync('DELETE FROM profile');
        await upsertRows(db, 'profile', profiles);
      } else {
        await db.runAsync('UPDATE profile SET full_name=?,student_number=?,year_section=?,photo_uri=? WHERE id=1',
          asText(root.profileName), asText(root.profileStudentId), asText(root.profileSection), '');
      }
      await db.runAsync('DELETE FROM app_settings');
      await upsertRows(db, 'app_settings', safeArray(tables.app_settings));
      await db.runAsync('DELETE FROM home_tiles');
      await upsertRows(db, 'home_tiles', safeArray(tables.home_tiles));
    }
    if (selected.has('schedule')) {
      await db.runAsync('DELETE FROM schedule_entries');
      await upsertRows(db, 'schedule_entries', safeArray(tables.schedule_entries));
    }
    if (selected.has('tasks')) {
      await db.runAsync('DELETE FROM tasks');
      await upsertRows(db, 'tasks', safeArray(tables.tasks));
    }
    if (selected.has('academics')) {
      await db.runAsync('DELETE FROM notes WHERE subject_id IS NOT NULL');
      await db.runAsync('DELETE FROM lecture_files');
      await db.runAsync('DELETE FROM lecture_folders');
      await upsertRows(db, 'subjects', safeArray(tables.subjects));
      await upsertRows(db, 'lecture_folders', safeArray(tables.lecture_folders));
      await upsertRows(db, 'notes', safeArray(tables.notes));
      for (const file of payloads) {
        const localUri = await writeStoredFileBase64(asText(file.path), asText(file.data));
        await upsertRows(db, 'lecture_files', [{
          id: asText(file.id, uid('file')),
          subject_id: asText(file.subject_id),
          name: asText(file.name),
          mime_type: asText(file.mime_type),
          local_uri: localUri,
          size: Number(file.size) || 0,
          added_at: Number(file.added_at) || now(),
          folder_name: asText(file.folder_name, 'Lecture 1'),
          favorite: file.favorite ? 1 : 0
        }]);
      }
    }
    if (selected.has('notepad')) {
      await db.runAsync('DELETE FROM notes WHERE subject_id IS NULL');
      await upsertRows(db, 'notes', safeArray(tables.notes));
    }
    if (selected.has('budget')) {
      await db.runAsync('DELETE FROM budget_entries');
      await db.runAsync('DELETE FROM savings_goals');
      await upsertRows(db, 'budget_entries', safeArray(tables.budget_entries));
      await upsertRows(db, 'savings_goals', safeArray(tables.savings_goals));
    }
  });
}

async function restoreLegacyBackup(root: AnyRow, selected: Set<BackupModule>) {
  const db = await database;
  const legacySubjects = safeArray(root.subjects);
  const subjectIdMap = new Map<string, string>();

  if (selected.has('academics')) {
    await db.runAsync('DELETE FROM lecture_files');
    await db.runAsync('DELETE FROM lecture_folders');
    await db.runAsync('DELETE FROM notes WHERE subject_id IS NOT NULL');
    for (const [index, subject] of legacySubjects.entries()) {
      const legacyId = asText(subject.id, String(index + 1));
      const id = 'legacy-sub-' + legacyId;
      subjectIdMap.set(legacyId, id);
      await db.runAsync('INSERT OR REPLACE INTO subjects (id,legacy_id,code,title,color,favorite,created_at) VALUES (?,?,?,?,?,?,?)',
        id, legacyId, asText(subject.subtitle), asText(subject.title, 'Untitled subject'), '#176B3A', safeObject(root.subjectFavorites)[legacyId] ? 1 : 0, now());
      for (const folder of ['Lecture 1','Lecture 2','Lecture 3']) await db.runAsync('INSERT OR IGNORE INTO lecture_folders (id,subject_id,name,created_at) VALUES (?,?,?,?)', id+'-'+folder.toLowerCase().replace(/[^a-z0-9]+/g,'-'), id, folder, now());
    }
    const notesObject = safeObject(root.subjectNotes);
    for (const legacyId of Object.keys(notesObject)) {
      const subjectId = 'legacy-sub-' + legacyId;
      const notes = safeArray(notesObject[legacyId]);
      for (const note of notes) {
        await db.runAsync('INSERT OR REPLACE INTO notes (id,title,body,subject_id,favorite,note_date,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)',
          'legacy-note-' + asText(note.id, uid('note')), asText(note.title, 'Untitled note'), asText(note.body), subjectId,
          note.favorite ? 1 : 0, '', Number(note.updatedAt) || now(), Number(note.updatedAt) || now());
      }
    }
  }

  if (selected.has('schedule') && root.schedule !== undefined) {
    const oldRows = safeArray(root.schedule);
    await db.runAsync('DELETE FROM schedule_entries');
    for (const row of oldRows) {
      const legacySubjectId = asText(row.subjectId, '');
      const subjectId = subjectIdMap.get(legacySubjectId) || null;
      const dayString = asText(row.day);
      const day = Number.isFinite(Number(dayString)) ? Number(dayString) : Math.max(0, weekdays.indexOf(dayString));
      await db.runAsync('INSERT OR REPLACE INTO schedule_entries (id,subject_id,title,day,start_time,end_time,kind,room,professor,notes) VALUES (?,?,?,?,?,?,?,?,?,?)',
        uid('class'), subjectId, asText(row.title, 'Class'), day, asText(row.startTime, '08:00'), asText(row.endTime, '09:00'),
        asText(row.classType, 'Lecture'), asText(row.room), asText(row.professor), asText(row.extra));
    }
  }

  if (selected.has('tasks') && root.tasks !== undefined) {
    await db.runAsync('DELETE FROM tasks');
    for (const task of safeArray(root.tasks)) {
      await db.runAsync('INSERT OR REPLACE INTO tasks (id,title,details,due_date,due_time,done,pinned,subject_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)',
        'legacy-task-' + asText(task.id, uid('task')), asText(task.title, 'Task'), asText(task.subtitle), asText(task.dueDate), asText(task.dueTime),
        task.done ? 1 : 0, 0, null, Number(task.id) || now());
    }
  }

  if (selected.has('homepage')) {
    await db.runAsync('UPDATE profile SET full_name=?,student_number=?,year_section=? WHERE id=1',
      asText(root.profileName), asText(root.profileStudentId), asText(root.profileSection));
    const settings: [string, string][] = [
      ['palette', asText(root.appearancePreset, 'forest')],
      ['display_mode', 'light'],
      ['home_layout_order', asText(root.homeLayoutOrder)],
      ['home_hidden_tiles', asText(root.homeHiddenTiles)]
    ];
    for (const [key, value] of settings) await db.runAsync('INSERT OR REPLACE INTO app_settings (key,value) VALUES (?,?)', key, value);
  }

  if (selected.has('academics') && Array.isArray(root.subjectFiles)) {
    for (const file of root.subjectFiles) {
      const path = asText(safeObject(file).path);
      const data = asText(safeObject(file).data);
      const localUri = await writeStoredFileBase64(path, data);
      const parts = path.replace(/\\\\/g,'/').split('/').filter(Boolean);
      const legacyId = parts[0] || '';
      const subjectId = subjectIdMap.get(legacyId) || null;
      const folder = subjectId ? (parts.length > 2 ? parts[1] : 'Lecture 1') : 'Lecture 1';
      if (subjectId) await db.runAsync('INSERT OR IGNORE INTO lecture_folders (id,subject_id,name,created_at) VALUES (?,?,?,?)', uid('folder'), subjectId, folder, now());
      await db.runAsync('INSERT OR REPLACE INTO lecture_files (id,subject_id,name,mime_type,local_uri,size,added_at,folder_name,favorite) VALUES (?,?,?,?,?,?,?,?,?)',
        uid('file'), subjectId, parts[parts.length-1] || 'Lecture file', '', localUri, 0, now(), folder, 0);
    }
  }
}

export async function restoreCampusBackup(json: string, selected: Set<BackupModule>): Promise<void> {
  const root = safeObject(JSON.parse(json));
  if (asText(root.format) !== 'CampusOS Backup') throw new Error('This is not a valid CampusOS backup.');
  if (root.mobileData && asText(safeObject(root.mobileData).format) === 'CampusOS Mobile Data') {
    await restoreMobileData(root, selected);
  } else {
    await restoreLegacyBackup(root, selected);
  }
}

export async function shareCampusBackup(selected: Set<BackupModule>): Promise<void> {
  const json = await createCampusBackup(selected);
  const base = FileSystem.cacheDirectory;
  if (!base) throw new Error('Temporary file storage is unavailable.');
  const uri = base + 'CampusOS-backup-' + now() + '.json';
  await FileSystem.writeAsStringAsync(uri, json, { encoding: FileSystem.EncodingType.UTF8 });
  if (!(await Sharing.isAvailableAsync())) throw new Error('File sharing is not available on this device.');
  await Sharing.shareAsync(uri, { mimeType: 'application/json', dialogTitle: 'Export CampusOS backup' });
}
