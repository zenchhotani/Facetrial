# Face Trial: Handoff

Last updated: 2026-10-02

## Goal
A personal Android app (APK) that recognizes a face and remembers the person's name. The user says their name once ("my name is Zuner"), the app learns their face, and the next time it sees that face it says "Hi Zuner".

## Where things live
- Repo: https://github.com/zenchhotani/Facetrial (branch `main`)
- APKs are built in the cloud by GitHub Actions (workflow: `.github/workflows/build.yml`). No Android Studio or computer is needed.
- The built APK is in the **Actions** tab: open the latest run, then download the artifact **FaceTrial-debug-apk**, unzip it, and install `app-debug.apk`.

## What works (as of the last build)
1. **Camera + face detection**: CameraX preview with ML Kit face detection (bundled, works offline). Green box around faces. Front/back camera flip.
2. **Say your name**: mic button uses Android SpeechRecognizer. Strips lead-ins like "my name is" / "I'm" and capitalizes the name. Usually needs internet.
3. **Remember the face**: after the name is heard, the app captures 6 frames of the face, turns each into an embedding with a TFLite face model, and saves them with the name in private app storage (SharedPreferences, JSON).
4. **Recognize and greet**: every ~400 ms the largest face is compared with the saved ones (cosine similarity, threshold 0.60). After 2 matches in a row it speaks "Hi <name>" (20 s cooldown per person). The label above the face shows the name and score, e.g. `Zuner (0.78)`.
5. **Forget all** button wipes every saved face and name.

All data stays on the phone. Nothing is uploaded.

## Code map (`app/src/main/java/com/zen/facetrial/`)
- `MainActivity.kt`: permissions, camera, speech in/out, enrolment and matching flow, greeting logic.
- `FaceEmbedder.kt`: loads `assets/face_model.tflite`, reads input size and output length from the model, returns an L2-normalized embedding.
- `FaceStore.kt`: saves and loads (name, embedding) pairs; best-match search.
- `FaceOverlay.kt`: draws boxes and the name label.
- Layout: `res/layout/activity_main.xml`. Manifest asks for CAMERA and RECORD_AUDIO.

## Build notes
- Gradle 8.7, AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 24, Java 17. There is no Gradle wrapper; the workflow installs Gradle itself.
- The face model is **not stored in the repo**. The workflow downloads it at build time from public GitHub repos (FaceNet `facenet.tflite`, falling back to `mobile_face_net.tflite`) and saves it as `app/src/main/assets/face_model.tflite`. If every URL fails, the build fails with "Could not download a face model". Fix: commit a model file to `app/src/main/assets/face_model.tflite` and remove the download step.
- The runner already has the Android SDK; the workflow only accepts licenses.
- Pushes to this repo from Claude work because the Claude GitHub App is installed on the repo.

## Not yet verified (nobody has run this on a phone as of this writing)
- Box alignment on the front camera.
- Whether the 0.60 match threshold is right. Watch the on-screen score for the real user vs. other people and adjust `MATCH_THRESHOLD` in `MainActivity.kt`.
- Whether the speech recognizer hears the name "Zuner" correctly.
- Accuracy in dim light and at angles.

## Known limits
- Face matching is not a security feature: a photo of a person can fool it (no liveness check).
- Only the largest face in view is recognized.
- Debug build only (not signed for the Play Store).

## Ideas for next steps
- Type a name instead of speaking it.
- Rename or delete a single person instead of "Forget all".
- Tune the threshold from real scores; consider more enrolment samples at different angles.
- Liveness check (blink or head turn) if it will ever be used for anything sensitive.
- Signed release build and app icon.

## Privacy
This app stores face data (biometric). Only save people who agree to it. Laws such as Illinois BIPA and GDPR apply if the app is ever shared.

## How to resume with Claude
Open a new chat and say: "Continue the Face Trial Android app. Read `handoff.md` in https://github.com/zenchhotani/Facetrial first." Then describe what you saw on the phone (scores, errors) or the next change you want.
