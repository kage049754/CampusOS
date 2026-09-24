# CampusOS

CampusOS is an offline-first Android student super-app.

## Included in the first build

- Dashboard with today's overview
- Class schedule
- Subjects
- Assignments and to-do checklist
- Offline reviewers/notes
- Grades and GPA calculator
- Attendance tracker
- Allowance and expense tracker
- School file area
- Global search
- Light/dark/system theme
- Local backup and restore
- Optional PIN/app lock
- Data persists after closing and reopening the app
- No account, server, or internet connection is required

## Build

The repository uses a standard Kotlin + Jetpack Compose Android project. GitHub Actions downloads a pinned Gradle distribution and builds a debug APK without Buildozer or Python-for-Android.

APK output: `app/build/outputs/apk/debug/app-debug.apk`
