# YSOS — Personal Safety & Emergency Response Android Application

**Developer/Creator:** MNM YOUNUS  
**Target Platform:** Android 5.0 (API Level 21) to Android 15+ (API Level 35+)  
**Distribution:** GitHub Releases (Debug APK)  

---

## 1. Architectural Highlights

- **100% Offline Capability:** Operates with zero network connectivity. No cloud sync, external analytics, or remote tracking.
- **Privacy & Zero-Knowledge Architecture:** All emergency contacts and captured evidence reside strictly in private internal app storage (`context.filesDir/evidence`).
- **Military-Grade Local Encryption:** All evidence recorded during panic activation (Audio, Front/Back Camera, Screen) is encrypted using **AES-256-GCM** backed by the Android Keystore.
- **Forensic Accessibility:** Protected behind device biometrics (fingerprint/face) or PIN authentication, ensuring only authorized owners or forensic authorities can decrypt recordings.
- **Material Design 3:** Intuitive, high-contrast, stress-resilient UI with automatic/manual Dark & Light mode toggle.

---

## 2. Modules & Capabilities

### Module 1: Global Emergency Directory
- **Country Selector:** Pre-loaded database of verified emergency contacts covering 40+ countries.
- **Categorized Services:** Police, Ambulance, Fire & Rescue, Disaster Relief.
- **Direct Actions:** One-tap direct dial (`Intent.ACTION_CALL`) and support email client integration (`Intent.ACTION_SENDTO`).

### Module 2: Panic Trigger & Secure Evidence Capture ("The Red Button")
- **One-Tap Emergency Activation:** Big, tactile red button on the home screen with pulsing visual feedback.
- **Simultaneous Multi-Stream Capture:**
  1. **Ambient Audio:** High-clarity microphone recording (`MediaRecorder`).
  2. **Camera Recording:** Concurrent Front and Back camera video capture via `Camera2 API` (with graceful single-camera fallback for older devices).
  3. **Screen Recording:** Display activity capture via `MediaProjection` API.
- **Android Foreground Service:** Persistent notification with high priority and CPU WakeLock to guarantee uninterrupted evidence recording.
- **Auto-Encryption:** Raw media is transformed into `.enc` encrypted files using AES-256-GCM immediately upon capture completion, and raw files are wiped.

### Module 3: Evidence Vault
- Authenticate via AndroidX `BiometricPrompt` (Fingerprint / Face / Device PIN).
- View captured evidence files, timestamp, and size.
- In-memory decrypt & preview playback via `FileProvider`.
- Secure deletion of evidence items.

---

## 3. Building & CI/CD

### Building Locally
```bash
./gradlew assembleDebug
```
The resulting debug APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions CI/CD
A fully automated workflow is configured at `.github/workflows/build.yml`.
- Pushing a version tag (e.g. `v1.0.0`) automatically builds the debug APK and attaches it to the GitHub Release.
- Artifacts are also uploaded for each workflow run.

---

## 4. Privacy & Compliance

- **No Network Permissions:** `android.permission.INTERNET` is **not** requested in `AndroidManifest.xml`.
- **Zero Backups:** Cloud backups and device-to-device transfers of database and evidence files are explicitly blocked via `data_extraction_rules.xml` and `backup_rules.xml`.
