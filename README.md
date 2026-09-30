# KyZer_HADOC Android App

Android app for https://kyzerhadoc.netlify.app (Capacitor).

- Loads the live website inside the app, so the UI, animations, background song and every page stay exactly the same as the web. Web updates show up in the app automatically.
- Native download manager: downloads get a notification with progress and are saved to the phone's Downloads folder.
- Background song autoplays and pauses when the app is minimised.
- Offline screen with a retry button.

## Get the APK
Push to `main` (or run "Build APK" from the Actions tab). The APK is attached to the latest Release and to the workflow run as an artifact.

## Local build (optional)
```
npm install
npx cap add android
# copy android-patch/MainActivity.java to android/app/src/main/java/com/kyzerhadoc/app/
npx cap sync android
cd android && ./gradlew assembleDebug
```
