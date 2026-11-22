# Android build notes

The project targets Expo SDK 58 and React Native 0.88. The safe-area dependency is pinned to Expo SDK 58's recommended `~5.11.1` after `~5.7.0` failed Android Kotlin compilation (`SafeAreaView.kt:59`, unresolved `uiImplementation`).
