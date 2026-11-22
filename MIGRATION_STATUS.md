# CampusOS React Native Migration

## Scope

Replace the Kotlin/Jetpack Compose Android client with a React Native Android app while preserving CampusOS's personal, offline-first functionality and visual identity.

Explicitly excluded from the React Native product:
- Announcements
- Group Chat
- Login and logout
- Admin and member accounts
- Registration and approval systems
- Online campus/community functions

The React Native app has no campus account system, community feed, chat, roles, or approval workflow. Core planner data stays on-device. The original Kotlin implementation remains on `main` until the replacement is ready; do not switch the default branch prematurely.

## Implemented in the migration branch

- React Native + Expo Android app shell, TypeScript, offline SQLite database, and CampusOS color presets.
- Home dashboard with profile, today's classes, tasks, subject/note statistics, pinned notes, persistent tile visibility, tile ordering controls, and layout reset.
- Schedule subject linking, Lecture/Laboratory types, overlap rejection, separate room/professor/details fields, configurable timetable days and hour range, and adjacent-session grouping for matching subject/room/type.
- Tasks calendar with month navigation, selected-day tasks, task status and priority, pinning, checklist items, and edit/delete.
- Personal Notepad notes with title, body, date, favorites, and edit/delete; Academics subject workspaces contain subject-linked notes and favorites.
- Budget allowance plans (daily/weekly/monthly), income, expenses, savings contributions, category summaries, savings goals, and transaction edit/delete.
- Local lecture-file storage, subject lecture folders, file favorites, file search, in-app text/Markdown/CSV/JSON previews, and native preview/share handoff for PDFs and Office files where a compatible Android app is installed.
- Module-selective JSON backup/restore, including the legacy `CampusOS Backup` format and lecture-file payloads.
- Local class/task notifications scheduled from SQLite data.
- Android home-screen widgets: Next Class and Tasks Overview. Widget snapshots refresh when the app opens and after schedule/task edits.
- Optional 4–8 digit PIN app lock stored through Android SecureStore, optional fingerprint/face unlock when device biometrics are enrolled, and relocking when returning to the app after it is backgrounded.

## Latest verified build

- Branch: `react-native-offline-migration`
- Source commit: `c250b7835d6d1905c32bda01b69f4b71fcdc3634`
- GitHub Actions run #31: https://github.com/kage049754/CampusOS/actions/runs/44988056371
- Result: TypeScript validation, Expo Android prebuild, ARM64 Gradle APK build, package-name verification, signature verification, and artifact upload all passed.
- The downloaded APK SHA-256 was independently matched against the checksum file produced by CI.

## Remaining work and limitations

- Replace the weekly list presentation with the full timetable table/grid and port the original table customization options (scroll modes, font size, day width, and row height).
- Add richer Notepad calendar navigation and reminder fields where they are distinct from task deadlines.
- Add true drag-and-drop Home tile reordering; current controls move tiles up/down and persist the order.
- PDF/Office handling currently opens an installed compatible Android viewer; it is not yet an embedded PDF/Office renderer.
- Widgets use the last snapshot from app launch or a schedule/task edit. Background refresh while the app is closed is not implemented.
- Pattern lock is not implemented; PIN and optional biometrics are available.
- Add runtime/device tests for notification timing, backup/restore round trips, document preview compatibility, app-lock lifecycle, and actual widget placement. GitHub Actions currently verifies compilation/package/signature, not all interactions on a physical phone.
- Remove the legacy Kotlin/community implementation from the default branch only after React Native feature parity and device checks are complete.

## Migration strategy

1. Keep `main` unchanged while the React Native replacement is developed on `react-native-offline-migration`.
2. Port modules in small, buildable increments and keep the local backup format compatible.
3. Keep online/community features excluded from the new app.
4. Inspect actual GitHub Actions results and logs after each milestone; fix failures before considering a build verified.
5. Switch the default branch only after feature parity, device checks, and a final verified APK.
