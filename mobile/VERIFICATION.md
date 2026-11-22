# Android APK verification

CI checks the React Native TypeScript project, regenerates the Android project, builds an ARM64 debug APK, validates the application ID with Android build-tools, verifies the APK signature, and publishes the APK with a SHA-256 checksum.
