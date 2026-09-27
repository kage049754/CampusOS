# CampusOS

CampusOS is an offline-first Android student app for managing your **class schedule, notes, subjects, study files, and personal campus information** in one place.

The app is designed to keep core student data available locally without requiring an account or an always-online server.

## Current Features

### 🏠 Home
- Clean student dashboard
- Profile card with:
  - Profile photo
  - Name
  - Student ID
  - Section
- Today's class overview
- Notes overview
- Pinned notes
- Class, subject, and note statistics
- Customizable Home tile visibility
- Drag-and-drop Home tile arrangement
- Home layout saved locally

### 📅 Schedule
- Full timetable view
- Configurable class days
- Configurable schedule time range
- Add, edit, and delete classes
- Lecture and Lab class types
- Separate Lecture/Lab room information
- Subject, room, professor, and notes
- Current-class highlighting
- Upcoming-class information
- Class duration display
- Started-at / ended-at information
- Consecutive matching classes can be merged for a cleaner timetable
- Schedule table customization:
  - Horizontal scrolling
  - Vertical scrolling
  - Text size
  - Day-column width
  - Row height
- Schedule reminders and widget updates

### 📝 Notes
- Calendar-style interface
- Swipe left/right to change months
- Tap a date to view notes for that date
- Add a note directly from a selected date
- Subject-linked notes
- Note subject and note content
- Alarm-style time selection with AM/PM
- Edit and delete notes
- Notes remain stored locally
- Existing task data is preserved internally during the Tasks → Notes terminology change

### 🎓 Academics
- Subjects
- Dedicated subject Notepad
- Subject notes
- Favorite notes
- Lecture Files area
- Store files under their subject
- File management for uploaded lecture materials
- Offline viewing support for supported document formats
- PDF page viewing
- PowerPoint/PPTX slide viewing
- Office-document text extraction for supported DOCX/PPTX/XLSX files

### 📂 Files
- Access the app's stored study files
- Open supported files with CampusOS's offline viewer
- PDF viewing
- PPT/PPTX slide viewing
- Text preview for supported Office and text-based formats

### ⚙️ Settings
- Separate settings for:
  - Homepage
  - Schedule
  - Notes
  - Academics
- Homepage layout controls
- Profile information
- Schedule management and table settings
- Appearance & Design
- Light, dark, or system theme
- Optional PIN/app lock
- Module-based backup and restore
- Local data persistence

### 🔔 Reminders & Widgets
- Local reminder scheduling for relevant campus items
- Upcoming-class reminder support
- Home/widget data refresh when stored data changes

## Offline-First

CampusOS stores its main student data locally on the device.

Core data does not require an online account or a continuously available server. The app also supports local backup and restore so selected CampusOS data can be moved or restored.

## Backup & Restore

Backup/restore can be selected by module, including:

- Homepage
- Schedule
- Notes
- Academics

Academic backups can include subject notes and stored subject files.

## Technology

- Kotlin
- Jetpack Compose
- Android
- Local Android storage / SharedPreferences-based persistence
- Android PDF rendering
- GitHub Actions for automated APK builds

## Build

GitHub Actions builds the debug APK using the repository's Android/Gradle workflow.

APK output:

`app/build/outputs/apk/debug/app-debug.apk`

The generated APK can be downloaded from a successful GitHub Actions run or attached to a GitHub Release for easier sharing.

## Current Build Status

The latest documented successful build is produced by GitHub Actions.

For the latest APK, open the repository's **Actions** tab and download the `CampusOS-debug` artifact from the latest successful Android build.

## Project Status

CampusOS is an actively developed student productivity app. Features are being improved while preserving existing local data and previously working functionality.

Physical Android-device behavior such as every navigation path, rotation case, notification delivery, and long-term persistence still needs to be verified on an actual Android device in addition to the automated build checks.
