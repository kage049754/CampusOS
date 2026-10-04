# Firebase Test Lab for CampusOS

CampusOS CI can automatically run a Firebase Test Lab Robo test against every successfully built debug APK.

## One-time GitHub configuration

Add these to the repository settings:

1. **Repository variable**
   - Name: `FIREBASE_PROJECT_ID`
   - Value: your Firebase/Google Cloud project ID.

2. **Repository secret**
   - Name: `FIREBASE_SERVICE_ACCOUNT_JSON`
   - Value: the complete JSON key for a Google Cloud service account that can run Firebase Test Lab.

The service account needs permission to run Test Lab. Firebase's CLI documentation notes that CLI-started Test Lab runs using the default Firebase results bucket require the executing principal to have the required project permissions.

For better security, Google recommends GitHub Workload Identity Federation instead of long-lived service-account keys. The current CampusOS workflow uses the JSON-secret method because it is simpler to configure; it can be migrated to Workload Identity Federation later.

## What CI does

1. Builds `app-debug.apk`.
2. Uploads the APK as the `CampusOS-debug` artifact.
3. Downloads that exact APK into the Test Lab job.
4. Authenticates to Google Cloud.
5. Runs a Robo test on a current Arm virtual Android device.
6. Fails the Test Lab job if the Robo test matrix fails.

If the Firebase variable/secret has not been configured yet, the Test Lab job is skipped with a visible warning and the normal APK build still succeeds.

Robo testing does not require a separate instrumentation test APK; Test Lab can crawl the app APK itself and look for crashes and UI problems.
