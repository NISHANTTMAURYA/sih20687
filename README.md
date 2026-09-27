# Offline AI Face Attendance Engine (SIH26087)

> **Zero-Network, On-Device Biometric Verification & Offline-First Attendance Gateway**  
> Built for the Smart India Hackathon (SIH26087) challenge for NCCT.

---

## 1. Executive Summary

This project implements an **offline-first face attendance engine** running entirely on an Android device without requiring an active internet connection.

### Core Capabilities
- **Real Human Faces Dataset**: 30 high-resolution photographic human portraits downloaded from public datasets and bundled into `app/src/main/assets/students/` with real pre-seeded 128-dim MobileFaceNet ArcFace embeddings across 5 distinct NCCT batches.
- **Clean Institutional Light Mode UI**: High-trust, legible light mode design system with crisp slate-900 typography, subtle borders, and clear semantic feedback colors (Emerald `#059669`, Cobalt Blue `#2563EB`).
- **Phone-Style Circular Multi-Angle Biometric Registration**: Modern mobile face registration experience featuring an animated circular progress ring (0% ➔ 33% ➔ 66% ➔ 100%) guiding the user through Frontal, Left Tilt, and Right Tilt captures to aggregate a robust, 3D-resilient 128-dim embedding.
- **Discrete 1-Process Scanning Flow**: Replaces frantic 30-fps loop inference with an intentional, operator-triggered verification session. Point camera at student, tap `[ 🔍 SCAN ATTENDANCE ]`, and watch the sequential pipeline execute with real-time animated step telemetry.
- **Side-by-Side Photo Verification on Match**: When a student is identified, the app visually presents their **Live Camera Snapshot** side-by-side with their **Stored Database Portrait**, along with match confidence score, student ID, roll number, session, and 128-dim embedding vector preview chips.
- **Multi-Stage Security & Anti-Spoofing**:
  1. **Face Detection & Landmarks**: Local fast face detection & head Euler angle calculation (ML Kit).
  2. **Anti-Spoofing & Liveness**: MiniFASNetV2 presentation attack defense evaluating high-frequency texture gradient and specular skin reflections to block 2D paper/printouts.
  3. **Screen Replay Defense**: SSD MobileNet object detector identifying secondary mobile screens or tablets replaying video.
  4. **ArcFace MobileFaceNet Embedding**: 128-dimensional unit-norm biometric vector extraction.
  5. **Cosine Similarity Matching**: Vector dot product against locally enrolled profiles in Room SQLite ($\ge 0.70$ threshold).
  6. **Geofence Check**: Verifies that the attendance device is within the training center campus radius ($\le 100\text{ m}$).
  7. **Atomic SQLite Marking**: Immutable record created locally with status `PENDING_SYNC`.
- **Resilient Offline Store-and-Forward Queue**: Attendance accumulates safely offline. When network is restored, one-tap idempotent batch sync uploads records to the central FastAPI gateway (`POST /attendance/sync`).

---

## 2. Five App Screens (Flow Order)

1. **`1. Enrol`**: Register new students using phone-style circular progress biometric capture (Frontal ➔ Left ➔ Right), compute aggregated 128-dim embedding, save face portrait to local storage, and store profile in Room DB.
2. **`2. Sessions`**: Select active NCCT training batch (e.g. *Digital Literacy DL-01*, *Cooperative Management CM-02*, *Agri-Cooperative Banking AB-04*).
3. **`3. Scan`**: Frame student face, tap `[ 🔍 SCAN ATTENDANCE ]`, view animated step telemetry, and inspect the side-by-side photo comparison (Live Camera vs Database Photo).
4. **`4. Queue`**: View offline attendance records pending sync. Configure server IP and trigger one-tap batch synchronization.
5. **`5. Database`**: Browse all 30 pre-seeded and newly enrolled students with real portrait photos, roll numbers, courses, batch tags, and 128-dim embedding vector previews.

---

## 3. How to Test on Phone

### Step 1: Download & Install APK on Android Phone
Ensure your phone is connected to the same Wi-Fi network as your PC:
- **Download Link**: Open Chrome or any browser on your phone and go to:
  ```
  http://192.168.29.209:8000/download/apk
  ```
- Install `OfflineFaceAttendance-debug.apk` and grant Camera & Location permissions when prompted.

### Step 2: Turn Internet OFF (Airplane Mode)
- Turn off Wi-Fi and Mobile Data on your phone.
- Open the app. The top status shows `100% OFFLINE MODE`.

### Step 3: Test Enrolled Database
- Tap the **`5. Database`** tab.
- Browse through the 30 real human student profiles with real photographs.
- Tap any student card to expand their 128-dim ArcFace embedding vector.

### Step 4: Test Discrete Attendance Scanning
- Tap the **`2. Sessions`** tab and select **Digital Literacy (DL-01)**, then tap **START ATTENDANCE**.
- Tap the **`3. Scan`** tab.
- Notice the camera feed is live and stable (no endless loop inference).
- Point camera or open the **Security Tests** toggle (top right `tune` icon) and tap **Test Scan Enrolled Student (Nishant)**.
- Watch the animated 6-step progress illuminate step-by-step.
- View the **Side-by-Side Face Comparison**: Live Camera Snapshot on the left, Stored Database Portrait on the right, 96% Match score, and 128-dim vector preview!

### Step 5: Test Phone-Style Biometric Enrollment
- Tap the **`1. Enrol`** tab.
- Enter Student Name: *Aman Gupta*, ID: *NCCT1035*, Roll: *135*.
- Tap **START BIOMETRIC ENROLLMENT**.
- Follow the circular guidance ring:
  - Step 1 (33%): Look straight ahead ➔ Tap **Capture Angle (1/3)**
  - Step 2 (66%): Tilt head slightly left ➔ Tap **Capture Angle (2/3)**
  - Step 3 (100%): Tilt head slightly right ➔ Tap **Capture Angle (3/3)**
- The app aggregates the 3 angles, saves the real photo, generates the 128-dim template, and stores it in Room DB.

### Step 6: Test Offline Queue & Sync
- Tap the **`4. Queue`** tab.
- See your marked attendance stored safely with status `PENDING`.
- Turn Wi-Fi back ON on your phone.
- Tap **SYNC NOW**. Records upload to the central server gateway.
- Visit `http://192.168.29.209:8000` on your PC or phone to see the live server attendance dashboard update instantly!

---

## 4. Central FastAPI Gateway Server

The companion server is running on port 8000:
- **Live Sync Dashboard**: `http://localhost:8000/` or `http://192.168.29.209:8000/`
- **Download APK Endpoint**: `http://192.168.29.209:8000/download/apk`
- **Batch Sync API**: `POST /attendance/sync` (idempotent, deduplicated via record UUIDs)
- **Interactive Swagger Docs**: `http://localhost:8000/docs`

To start or restart the server manually:
```powershell
python -m uvicorn server.main:app --host 0.0.0.0 --port 8000 --reload
```
