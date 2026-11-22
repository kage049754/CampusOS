# CampusOS React Native Migration

## Scope

Replace the Kotlin/Jetpack Compose Android client with a React Native Android app while preserving CampusOS's personal, offline-first functionality and visual identity.

Explicitly remove from the product:
- Announcements
- Group Chat
- Login and logout
- Admin and member accounts
- Registration and approval systems
- Online campus/community functions

Do not add a cloud account, campus backend, social feed, chat, role management, or approval workflow. Core planner data remains local to the device.

## Preserve

- Home dashboard, profile card, customizable tiles and tile ordering
- Schedule, configurable days/time range, Lecture/Lab entries, rooms, professor, notes, conflict checks, current/upcoming class indicators, reminders and widgets
- Calendar-style Notes with subject links, favorites, alarms, editing and deletion
- Academics: subjects, subject notepads, favorites and subject-linked lecture files
- Offline file management and supported PDF/Office/text previews
- Budget, expenses, savings goals, insights and supported widgets
- Settings, profile details, appearance presets, light/dark/system theme, optional app lock
- Module-based local backup and restore
- Local notifications and Android home-screen widgets

## Migration strategy

1. Keep `main` unchanged while the React Native replacement is developed on `react-native-offline-migration`.
2. Establish the React Native app shell, theme system and local SQLite schema.
3. Port modules in small, buildable increments; keep data migration and backup compatibility in scope.
4. Remove the legacy online/community code and its permissions/dependencies as the React Native implementation replaces it.
5. Run Android builds and tests after each milestone. Do not replace the default branch until feature parity and APK verification are complete.

## Current checkpoint

- [x] Inspected the existing CampusOS repository and documented its modules.
- [x] Created an isolated migration branch.
- [x] Added the initial React Native/Expo Android project scaffold.
- [x] Add first React Native CRUD screens for Schedule, Tasks, Notes, Budget, Profile, and local document imports.
- [ ] Continue deeper parity: weekly timetable configuration, month calendar, subject-linked notes/files, checklists, savings goals, dashboard layout controls, and rich file viewing.
- [ ] Add legacy JSON backup import/export and migration.
- [ ] Implement native reminders and home-screen widgets.
- [ ] Remove legacy online/community code from the replacement.
- [ ] Build, test, inspect logs, and verify the APK before proposing a default-branch switch.

The original Kotlin app remains intact on `main`.
