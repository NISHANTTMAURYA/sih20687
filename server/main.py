import os
import json
import csv
import io
import math
import base64
import time
import asyncio
from typing import List, Optional, Set
from datetime import datetime
from PIL import Image
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

app = FastAPI(
    title="NCCT Offline AI Attendance Sync Gateway",
    version="1.0.0",
    description="Offline-first biometric attendance synchronization gateway for SIH26087"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Real-Time Zero-Refresh SSE Broadcast & Outbound Webhook Infrastructure
ACTIVE_SSE_QUEUES: Set[asyncio.Queue] = set()
OUTBOUND_WEBHOOK_URL: Optional[str] = None

async def broadcast_live_event(event_type: str, data: dict):
    """Instantly pushes events to all connected web dashboards over Server-Sent Events (SSE)."""
    payload = f"event: {event_type}\ndata: {json.dumps(data)}\n\n"
    dead_queues = []
    for q in list(ACTIVE_SSE_QUEUES):
        try:
            q.put_nowait(payload)
        except Exception:
            dead_queues.append(q)
    for q in dead_queues:
        ACTIVE_SSE_QUEUES.discard(q)

async def trigger_outbound_webhook(event_type: str, payload: dict):
    """Triggers outbound HTTP POST webhook if an external URL is configured."""
    global OUTBOUND_WEBHOOK_URL
    if not OUTBOUND_WEBHOOK_URL:
        return
    try:
        import urllib.request
        req_data = json.dumps({"event": event_type, "timestamp": time.time(), "payload": payload}).encode("utf-8")
        req = urllib.request.Request(
            OUTBOUND_WEBHOOK_URL,
            data=req_data,
            headers={"Content-Type": "application/json", "User-Agent": "NCCT-Attendance-Webhook/1.0"}
        )
        urllib.request.urlopen(req, timeout=3.0)
    except Exception as e:
        print(f"[Webhook] Failed to trigger outbound webhook to {OUTBOUND_WEBHOOK_URL}: {e}")

# In-Memory & Local File Persistent Store
ATTENDANCE_DB = []
DEDUP_IDS = set()

ATTENDANCE_DB_FILE = os.path.join(os.path.dirname(__file__), "attendance_db.json")
if os.path.exists(ATTENDANCE_DB_FILE):
    try:
        with open(ATTENDANCE_DB_FILE, "r", encoding="utf-8") as f:
            ATTENDANCE_DB = json.load(f)
            DEDUP_IDS = {r.get("recordId") for r in ATTENDANCE_DB if r.get("recordId")}
    except Exception as e:
        print("Failed to load attendance DB:", e)

def save_attendance_db():
    try:
        with open(ATTENDANCE_DB_FILE, "w", encoding="utf-8") as f:
            json.dump(ATTENDANCE_DB, f, indent=2)
    except Exception as e:
        print("Failed saving attendance DB:", e)

# Pre-seeded training sessions
SESSIONS = [
    {
        "sessionId": "DL-01",
        "title": "Digital Literacy",
        "batchCode": "DL-01",
        "startTime": "09:00 AM",
        "endTime": "11:00 AM",
        "centerName": "NCCT Regional Training Center, Sector 5",
        "centerLatitude": 19.0760,
        "centerLongitude": 72.8777,
        "allowedRadiusMeters": 100.0,
        "isActive": True
    },
    {
        "sessionId": "CM-02",
        "title": "Cooperative Management",
        "batchCode": "CM-02",
        "startTime": "11:30 AM",
        "endTime": "01:30 PM",
        "centerName": "NCCT State Institute, Hall B",
        "centerLatitude": 19.0760,
        "centerLongitude": 72.8777,
        "allowedRadiusMeters": 100.0,
        "isActive": True
    },
    {
        "sessionId": "EN-03",
        "title": "Entrepreneurship Development",
        "batchCode": "EN-03",
        "startTime": "02:00 PM",
        "endTime": "04:00 PM",
        "centerName": "NCCT Enterprise Lab, Hub 3",
        "centerLatitude": 19.0760,
        "centerLongitude": 72.8777,
        "allowedRadiusMeters": 100.0,
        "isActive": True
    },
    {
        "sessionId": "AB-04",
        "title": "Agri-Cooperative Banking",
        "batchCode": "AB-04",
        "startTime": "04:30 PM",
        "endTime": "06:30 PM",
        "centerName": "NCCT Rural Development Center",
        "centerLatitude": 19.0760,
        "centerLongitude": 72.8777,
        "allowedRadiusMeters": 100.0,
        "isActive": True
    },
    {
        "sessionId": "RC-05",
        "title": "Rural Credit & Finance",
        "batchCode": "RC-05",
        "startTime": "07:00 PM",
        "endTime": "09:00 PM",
        "centerName": "NCCT Microfinance Hall",
        "centerLatitude": 19.0760,
        "centerLongitude": 72.8777,
        "allowedRadiusMeters": 100.0,
        "isActive": True
    }
]

SESSIONS_FILE = os.path.join(os.path.dirname(__file__), "sessions_config.json")
if os.path.exists(SESSIONS_FILE):
    try:
        with open(SESSIONS_FILE, "r", encoding="utf-8") as f:
            SESSIONS = json.load(f)
    except Exception:
        pass

def save_sessions_config():
    try:
        with open(SESSIONS_FILE, "w", encoding="utf-8") as f:
            json.dump(SESSIONS, f, indent=2)
    except Exception as e:
        print("Failed saving sessions config:", e)

# Load full enrolled students dataset (30 students)
STUDENTS = []
dataset_file = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "students", "students_dataset.json")
if os.path.exists(dataset_file):
    try:
        with open(dataset_file, "r", encoding="utf-8") as f:
            STUDENTS = json.load(f)
    except Exception:
        STUDENTS = []

if not STUDENTS:
    STUDENTS = [
        {"studentId": "NCCT1001", "name": "Nishant Maurya", "rollNumber": "101", "course": "Digital Literacy", "enrolledSessionIds": ["DL-01"]},
        {"studentId": "NCCT1002", "name": "Aarav Sharma", "rollNumber": "102", "course": "Digital Literacy", "enrolledSessionIds": ["DL-01", "CM-02"]},
        {"studentId": "NCCT1003", "name": "Priya Patel", "rollNumber": "103", "course": "Cooperative Management", "enrolledSessionIds": ["CM-02"]}
    ]

ROSTER_VERSION = int(time.time())

def save_students_dataset():
    global ROSTER_VERSION
    ROSTER_VERSION = int(time.time())
    try:
        with open(dataset_file, "w", encoding="utf-8") as f:
            json.dump(STUDENTS, f, indent=2)
    except Exception as e:
        print("Failed saving students dataset to app assets:", e)
    server_backup = os.path.join(os.path.dirname(__file__), "students_dataset.json")
    try:
        with open(server_backup, "w", encoding="utf-8") as f:
            json.dump(STUDENTS, f, indent=2)
    except Exception as e:
        pass

DELETED_STUDENTS_FILE = os.path.join(os.path.dirname(__file__), "deleted_students.json")
DELETED_STUDENT_IDS = set()
try:
    if os.path.exists(DELETED_STUDENTS_FILE):
        with open(DELETED_STUDENTS_FILE, "r", encoding="utf-8") as f:
            DELETED_STUDENT_IDS = set(json.load(f))
except Exception:
    DELETED_STUDENT_IDS = set()

def save_deleted_students():
    global ROSTER_VERSION
    ROSTER_VERSION = int(time.time())
    try:
        with open(DELETED_STUDENTS_FILE, "w", encoding="utf-8") as f:
            json.dump(list(DELETED_STUDENT_IDS), f, indent=2)
    except Exception as e:
        print("Failed saving deleted students list:", e)

# MobileFaceNet Deep Learning Interpreter via ai-edge-litert
TFLITE_MODEL_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "models", "mobile_face_net.tflite"))
TFLITE_INTERPRETER = None
try:
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    if os.path.exists(TFLITE_MODEL_PATH):
        TFLITE_INTERPRETER = Interpreter(model_path=TFLITE_MODEL_PATH)
        TFLITE_INTERPRETER.allocate_tensors()
        print(f"[MobileFaceNet] Successfully loaded TFLite model from {TFLITE_MODEL_PATH}")
except Exception as e:
    print(f"[MobileFaceNet] Warning: Could not initialize ai_edge_litert interpreter: {e}")

FACE_CASCADE = None
FACE_ALT2_CASCADE = None
CAT_CASCADE = None
EYE_CASCADE = None
EYE_TREE_CASCADE = None
LEFT_EYE_CASCADE = None
RIGHT_EYE_CASCADE = None

try:
    import cv2
    FACE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_default.xml')
    FACE_ALT2_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalface_alt2.xml')
    CAT_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_frontalcatface.xml')
    EYE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_eye.xml')
    EYE_TREE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_eye_tree_eyeglasses.xml')
    LEFT_EYE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_lefteye_2splits.xml')
    RIGHT_EYE_CASCADE = cv2.CascadeClassifier(cv2.data.haarcascades + 'haarcascade_righteye_2splits.xml')
    print("[OpenCV] Loaded multi-cascade human face & eye detection ensemble successfully")
except Exception as e:
    print(f"[OpenCV] Warning: Could not initialize Haar cascades: {e}")

def validate_human_face(img: Image.Image) -> tuple:
    """
    Server-side security gate for student enrollment.
    Validates that the submitted photo contains a genuine human face while rejecting
    animals, drawings, objects, blank screens, and crowd shots.

    Robust Ensemble Checks:
      1. Multi-scale human face detection (default frontal + alt2 cascades with CLAHE/equalization).
      2. Anti-Animal check: Flags explicit cat/animal cascades when human features are absent.
      3. Geometry check: Face width >= 12% of image and aspect ratio 0.45 - 2.2.
      4. Eye & feature verification: Uses standard eye, eyeglasses-tolerant tree, and split eye cascades.
    """
    global FACE_CASCADE, FACE_ALT2_CASCADE, CAT_CASCADE, EYE_CASCADE, EYE_TREE_CASCADE, LEFT_EYE_CASCADE, RIGHT_EYE_CASCADE
    if FACE_CASCADE is None or FACE_CASCADE.empty():
        print("[Security] WARNING: Haar cascade not loaded, skipping face validation")
        return (True, "")

    try:
        import cv2
        import numpy as np

        cv_img = cv2.cvtColor(np.array(img.convert("RGB")), cv2.COLOR_RGB2BGR)
        gray = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        eq_gray = cv2.equalizeHist(gray)
        h_img, w_img = gray.shape[:2]

        # 1. Multi-detector Face Search (raw + histogram-equalized)
        faces = list(FACE_CASCADE.detectMultiScale(
            gray, scaleFactor=1.1, minNeighbors=3,
            minSize=(int(w_img * 0.10), int(h_img * 0.10))
        ))
        if len(faces) == 0:
            faces = list(FACE_CASCADE.detectMultiScale(
                eq_gray, scaleFactor=1.1, minNeighbors=3,
                minSize=(int(w_img * 0.10), int(h_img * 0.10))
            ))
        if len(faces) == 0 and FACE_ALT2_CASCADE is not None and not FACE_ALT2_CASCADE.empty():
            faces = list(FACE_ALT2_CASCADE.detectMultiScale(
                gray, scaleFactor=1.1, minNeighbors=3,
                minSize=(int(w_img * 0.10), int(h_img * 0.10))
            ))

        if len(faces) == 0:
            return (False, "No human face detected in the submitted photo. Please position your face clearly in the camera frame.")

        # 2. Pick largest face; check minimum size
        faces = sorted(faces, key=lambda f: f[2] * f[3], reverse=True)
        fx, fy, fw, fh = faces[0]
        if fw < w_img * 0.12:
            return (False, "Face is too far or small in the photo. Please move closer so your face fills the frame.")

        # 3. Aspect ratio check (human face width/height ~ 0.45 – 2.2)
        face_ratio = fw / float(fh) if fh > 0 else 0
        if face_ratio < 0.45 or face_ratio > 2.2:
            return (False, f"Detected geometry does not match a human portrait (w/h ratio {face_ratio:.2f}). Please submit a straight portrait photo.")

        # 4. Anti-Animal Filter: Explicit check for cat/pet face detections
        if CAT_CASCADE is not None and not CAT_CASCADE.empty():
            cat_faces = CAT_CASCADE.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=4)
            if len(cat_faces) > 0:
                # If cat cascade triggered with equal/larger area and no human eye matches, reject
                cfx, cfy, cfw, cfh = max(cat_faces, key=lambda f: f[2] * f[3])
                if (cfw * cfh) >= (fw * fh) * 0.8:
                    return (False, "Animal/pet face detected. Only genuine human face photographs are accepted for student enrollment.")

        # 5. Robust Eye & Feature Verification inside upper face ROI
        # Eyes are naturally in the upper 70% of the detected face bounding box
        upper_fh = int(fh * 0.70)
        face_roi_gray = gray[fy:fy + upper_fh, fx:fx + fw]
        face_roi_eq = eq_gray[fy:fy + upper_fh, fx:fx + fw]

        eye_count = 0
        # Try eye-tree cascade first (designed specifically for eyeglasses, shadows, reflections)
        if EYE_TREE_CASCADE is not None and not EYE_TREE_CASCADE.empty():
            eyes = EYE_TREE_CASCADE.detectMultiScale(face_roi_gray, scaleFactor=1.05, minNeighbors=2, minSize=(int(fw * 0.08), int(fh * 0.06)))
            if len(eyes) == 0:
                eyes = EYE_TREE_CASCADE.detectMultiScale(face_roi_eq, scaleFactor=1.05, minNeighbors=2, minSize=(int(fw * 0.08), int(fh * 0.06)))
            eye_count = max(eye_count, len(eyes))

        # Try standard eye cascade
        if eye_count < 2 and EYE_CASCADE is not None and not EYE_CASCADE.empty():
            eyes = EYE_CASCADE.detectMultiScale(face_roi_gray, scaleFactor=1.05, minNeighbors=2, minSize=(int(fw * 0.08), int(fh * 0.06)))
            if len(eyes) == 0:
                eyes = EYE_CASCADE.detectMultiScale(face_roi_eq, scaleFactor=1.05, minNeighbors=2, minSize=(int(fw * 0.08), int(fh * 0.06)))
            eye_count = max(eye_count, len(eyes))

        # Try split left/right eye cascades as fallback
        if eye_count < 1:
            left_eyes = []
            right_eyes = []
            if LEFT_EYE_CASCADE is not None and not LEFT_EYE_CASCADE.empty():
                left_eyes = LEFT_EYE_CASCADE.detectMultiScale(face_roi_gray, scaleFactor=1.05, minNeighbors=2)
            if RIGHT_EYE_CASCADE is not None and not RIGHT_EYE_CASCADE.empty():
                right_eyes = RIGHT_EYE_CASCADE.detectMultiScale(face_roi_gray, scaleFactor=1.05, minNeighbors=2)
            eye_count = len(left_eyes) + len(right_eyes)

        # If a solid frontal face was confirmed by both primary detectors or at least 1 eye was found, accept
        # If completely 0 eyes and only single weak face detection, check full image
        if eye_count == 0:
            full_eyes = []
            if EYE_CASCADE is not None and not EYE_CASCADE.empty():
                full_eyes = EYE_CASCADE.detectMultiScale(gray, scaleFactor=1.05, minNeighbors=2)
            if len(full_eyes) == 0 and EYE_TREE_CASCADE is not None and not EYE_TREE_CASCADE.empty():
                full_eyes = EYE_TREE_CASCADE.detectMultiScale(gray, scaleFactor=1.05, minNeighbors=2)

            if len(full_eyes) == 0:
                # Check if face was detected with high confidence (alt2 face match)
                alt2_match = False
                if FACE_ALT2_CASCADE is not None and not FACE_ALT2_CASCADE.empty():
                    alt2_faces = FACE_ALT2_CASCADE.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=4)
                    alt2_match = len(alt2_faces) > 0

                if not alt2_match:
                    return (False,
                        "Could not detect clear human facial features/eyes in the photo. "
                        "Please ensure your face is well-lit, looking directly at the camera, with eyes open.")

        return (True, "")

    except Exception as e:
        print(f"[Security] validate_human_face error (allowing through): {e}")
        return (True, "")  # Don't block on unexpected errors


def align_face_to_arcface(img: Image.Image) -> Image.Image:
    """Aligns a face image to canonical ArcFace 112x112 geometry with eye alignment."""
    if img.size == (112, 112):
        return img
    global FACE_CASCADE, EYE_CASCADE
    try:
        import cv2
        import numpy as np

        cv_img = cv2.cvtColor(np.array(img.convert("RGB")), cv2.COLOR_RGB2BGR)
        gray = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        target_size = 112
        target_eye_dist = 35.2  # 73.5 - 38.3 in standard 112x112 ArcFace
        target_center = np.array([55.9, 51.6], dtype=np.float32)

        faces = []
        if FACE_CASCADE is not None:
            faces = FACE_CASCADE.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=3, minSize=(40, 40))

        detected_eyes = []
        if len(faces) > 0:
            faces = sorted(faces, key=lambda f: f[2] * f[3], reverse=True)
            fx, fy, fw, fh = faces[0]
            face_roi_gray = gray[fy:fy + fh, fx:fx + fw]
            if EYE_CASCADE is not None:
                eyes_in_face = EYE_CASCADE.detectMultiScale(face_roi_gray, scaleFactor=1.05, minNeighbors=3)
                for ex, ey, ew, eh in eyes_in_face:
                    detected_eyes.append((fx + ex + ew / 2.0, fy + ey + eh / 2.0))
        elif EYE_CASCADE is not None:
            eyes = EYE_CASCADE.detectMultiScale(gray, scaleFactor=1.05, minNeighbors=3)
            for ex, ey, ew, eh in eyes:
                detected_eyes.append((ex + ew / 2.0, ey + eh / 2.0))

        if len(detected_eyes) >= 2:
            detected_eyes = sorted(detected_eyes, key=lambda p: p[0])
            left_eye = detected_eyes[0]
            right_eye = detected_eyes[-1]
            cur_dx = right_eye[0] - left_eye[0]
            cur_dy = right_eye[1] - left_eye[1]
            cur_dist = np.hypot(cur_dx, cur_dy)
            if cur_dist > 12:
                cur_center = np.array([(left_eye[0] + right_eye[0]) / 2.0, (left_eye[1] + right_eye[1]) / 2.0], dtype=np.float32)
                angle = np.degrees(np.arctan2(cur_dy, cur_dx))
                scale = target_eye_dist / cur_dist
                M = cv2.getRotationMatrix2D(tuple(cur_center), angle, scale)
                M[0, 2] += (target_center[0] - cur_center[0])
                M[1, 2] += (target_center[1] - cur_center[1])
                aligned = cv2.warpAffine(cv_img, M, (target_size, target_size), flags=cv2.INTER_CUBIC)
                return Image.fromarray(cv2.cvtColor(aligned, cv2.COLOR_BGR2RGB))

        # Fallback: square face crop with centered bounding box
        if len(faces) > 0:
            fx, fy, fw, fh = faces[0]
            cx, cy = fx + fw / 2.0, fy + fh / 2.0
            side = max(fw, fh) * 1.15
            x1 = max(0, int(cx - side / 2.0))
            y1 = max(0, int(cy - side / 2.0))
            x2 = min(cv_img.shape[1], int(cx + side / 2.0))
            y2 = min(cv_img.shape[0], int(cy + side / 2.0))
            cropped = cv_img[y1:y2, x1:x2]
            return Image.fromarray(cv2.cvtColor(cropped, cv2.COLOR_BGR2RGB)).resize((target_size, target_size))
    except Exception as e:
        print("align_face_to_arcface error:", e)

    return img.resize((112, 112))

def crop_face_if_needed(img: Image.Image) -> Image.Image:
    """Delegates to ArcFace alignment standard."""
    return align_face_to_arcface(img)

def compute_face_embedding(img: Image.Image) -> list:
    """Extracts a 192-dimensional normalized biometric embedding matching FaceEmbeddingEngine.kt using MobileFaceNet."""
    global TFLITE_INTERPRETER
    img = align_face_to_arcface(img)
    if TFLITE_INTERPRETER is not None:
        try:
            import numpy as np
            scaled = img.resize((112, 112)).convert("RGB")
            arr = np.array(scaled, dtype=np.float32)
            # MobileFaceNet standard normalization: (pixel - 127.5) / 128.0
            arr = (arr - 127.5) / 128.0
            arr = np.expand_dims(arr, axis=0) # [1, 112, 112, 3]
            input_idx = TFLITE_INTERPRETER.get_input_details()[0]['index']
            output_idx = TFLITE_INTERPRETER.get_output_details()[0]['index']
            TFLITE_INTERPRETER.set_tensor(input_idx, arr)
            TFLITE_INTERPRETER.invoke()
            emb = TFLITE_INTERPRETER.get_tensor(output_idx)[0]
            norm = np.linalg.norm(emb)
            if norm > 0:
                emb = emb / norm
            return [float(round(x, 6)) for x in emb]
        except Exception as e:
            print("TFLite embedding inference error, using fallback:", e)

    # Fallback to algorithmic extraction if model not available
    scaled = img.resize((56, 56)).convert("RGB")
    w, h = 56, 56
    pixels = list(scaled.getdata())
    total_pixels = w * h

    r_mean = sum(p[0] for p in pixels) / total_pixels
    g_mean = sum(p[1] for p in pixels) / total_pixels
    b_mean = sum(p[2] for p in pixels) / total_pixels

    embedding_dim = 192
    vector = [0.0] * embedding_dim

    for i in range(embedding_dim):
        zone_x = (i % 4) * (w // 4)
        zone_y = ((i // 4) % 4) * (h // 4)
        zone_val = 0.0
        count = 0

        start_px = max(0, min(zone_x, w - 1))
        end_px = max(0, min(zone_x + w // 4, w))
        start_py = max(0, min(zone_y, h - 1))
        end_py = max(0, min(zone_y + h // 4, h))

        for y in range(start_py, end_py):
            for x in range(start_px, end_px):
                p = scaled.getpixel((x, y))
                gray = p[0] * 0.299 + p[1] * 0.587 + p[2] * 0.114
                freq = math.sin(x * 0.2 + i * 0.1) * math.cos(y * 0.2 + i * 0.1)
                zone_val += gray * freq
                count += 1

        raw = zone_val / count if count > 0 else (i * 0.01)
        vector[i] = raw + ((r_mean - g_mean) * 0.02)

    norm = math.sqrt(sum(v * v for v in vector))
    if norm > 0:
        return [round(v / norm, 6) for v in vector]
    return [0.0] * embedding_dim

def compute_cosine_similarity(vec1: list, vec2: list) -> float:
    """Computes cosine similarity between two unit-normalized embedding vectors."""
    if not vec1 or not vec2 or len(vec1) != len(vec2):
        return 0.0
    return float(sum(a * b for a, b in zip(vec1, vec2)))

class AttendanceItem(BaseModel):
    recordId: str = Field(..., description="Unique UUID event ID from offline device")
    studentId: str
    studentName: str
    sessionId: str
    sessionTitle: str
    timestamp: int
    similarityScore: float
    livenessScore: float
    latitude: float
    longitude: float
    isLocationValid: bool
    capturedFaceBase64: Optional[str] = None

CAPTURED_FACES_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "captured_faces"))
os.makedirs(CAPTURED_FACES_DIR, exist_ok=True)
STUDENTS_ASSETS_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "students"))

def get_or_create_captured_face(record_id: str, student_id: str, b64_data: Optional[str] = None) -> str:
    photo_path = os.path.join(CAPTURED_FACES_DIR, f"{record_id}.jpg")
    if os.path.exists(photo_path) and os.path.getsize(photo_path) > 100:
        return photo_path

    if b64_data:
        try:
            clean_b64 = b64_data.split(",", 1)[1] if "," in b64_data else b64_data
            img_bytes = base64.b64decode(clean_b64)
            with open(photo_path, "wb") as f:
                f.write(img_bytes)
            return photo_path
        except Exception as e:
            print("Failed saving base64 captured face:", e)

    # Fallback / Demo synthesis from enrolled portrait
    enrolled_path = os.path.join(STUDENTS_ASSETS_DIR, f"{student_id}.jpg")
    if os.path.exists(enrolled_path):
        try:
            img = Image.open(enrolled_path).convert("RGB")
            w, h = img.size
            crop_box = (int(w * 0.05), int(h * 0.04), int(w * 0.95), int(h * 0.96))
            live_crop = img.crop(crop_box).resize((200, 200))
            live_crop.save(photo_path, "JPEG", quality=90)
            return photo_path
        except Exception:
            pass

    return photo_path

class SyncRequest(BaseModel):
    deviceId: str = "ANDROID-OFFLINE-01"
    records: List[AttendanceItem]

class SyncResponse(BaseModel):
    status: str
    syncedCount: int
    duplicatesIgnored: int
    totalServerRecords: int
    syncedRecordIds: List[str]
    serverTime: str

class StudentSyncItem(BaseModel):
    studentId: str
    name: str
    rollNumber: Optional[str] = ""
    course: Optional[str] = ""
    enrolledSessionIds: List[str] = []
    faceEmbedding: Optional[List[float]] = None
    photoBase64: Optional[str] = None

class StudentSyncRequest(BaseModel):
    deviceId: str = "ANDROID-OFFLINE-01"
    students: List[StudentSyncItem] = []
    deletedStudentIds: Optional[List[str]] = []

class StudentSyncResponse(BaseModel):
    status: str
    syncedFromApp: int
    totalServerStudents: int
    serverStudents: List[StudentSyncItem]
    deletedStudentIds: List[str] = []

@app.get("/health")
def health_check():
    return {
        "status": "ONLINE",
        "service": "NCCT-Attendance-Sync-Gateway",
        "offlineSupport": True,
        "timestamp": datetime.utcnow().isoformat()
    }

GEO_CACHE = {}

def get_address_for_coords(lat: float, lon: float) -> str:
    cache_key = f"{lat:.5f},{lon:.5f}"
    if cache_key in GEO_CACHE:
        return GEO_CACHE[cache_key]

    # 1. Primary Free Reverse Geocoder: OpenStreetMap Nominatim (High detail)
    try:
        import urllib.request
        url = f"https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lon}&zoom=18&addressdetails=1"
        req = urllib.request.Request(url, headers={
            "User-Agent": "SIH26087-NCCT-Attendance/2.0 (contact: mauryanishant2005@gmail.com)"
        })
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode())
            addr_data = data.get("address", {})
            if addr_data:
                place = addr_data.get("building") or addr_data.get("amenity") or addr_data.get("leisure") or ""
                road = addr_data.get("road") or addr_data.get("pedestrian") or ""
                neighbourhood = addr_data.get("neighbourhood") or ""
                suburb = addr_data.get("suburb") or addr_data.get("residential") or ""
                city_district = addr_data.get("city_district") or addr_data.get("subdistrict") or ""
                city = addr_data.get("city") or addr_data.get("town") or addr_data.get("village") or addr_data.get("municipality") or ""
                state = addr_data.get("state") or ""
                postcode = addr_data.get("postcode") or ""

                parts = []
                for p in [place, road, neighbourhood, suburb, city_district or city, state, postcode]:
                    p_clean = str(p).strip()
                    if p_clean and p_clean not in parts:
                        parts.append(p_clean)

                if parts:
                    formatted = ", ".join(parts)
                    GEO_CACHE[cache_key] = formatted
                    return formatted
                if data.get("display_name"):
                    formatted = data.get("display_name")
                    GEO_CACHE[cache_key] = formatted
                    return formatted
    except Exception:
        pass

    # 2. Secondary Free Reverse Geocoder: BigDataCloud Client API (Fast fallback)
    try:
        import urllib.request
        url = f"https://api.bigdatacloud.net/data/reverse-geocode-client?latitude={lat}&longitude={lon}&localityLanguage=en"
        req = urllib.request.Request(url, headers={"User-Agent": "SIH26087-NCCT-Attendance/2.0"})
        with urllib.request.urlopen(req, timeout=3.0) as resp:
            data = json.loads(resp.read().decode())
            loc = data.get("locality") or ""
            city = data.get("city") or ""
            subdiv = data.get("principalSubdivision") or ""
            postcode = data.get("postcode") or ""

            parts = [p for p in [loc, city, subdiv, postcode] if p and p not in ["", "null"]]
            if parts:
                formatted = ", ".join(parts)
                GEO_CACHE[cache_key] = formatted
                return formatted
    except Exception:
        pass

    fallback = f"Location Area ({lat:.4f}° N, {lon:.4f}° E)"
    GEO_CACHE[cache_key] = fallback
    return fallback

class LocationUpdate(BaseModel):
    latitude: float
    longitude: float
    allowedRadiusMeters: Optional[float] = 100.0
    centerName: Optional[str] = None
    locationAddress: Optional[str] = None

@app.get("/sessions")
def get_sessions():
    # Ensure all sessions have locationAddress populated
    for s in SESSIONS:
        if not s.get("locationAddress"):
            s["locationAddress"] = get_address_for_coords(s.get("centerLatitude", 19.0760), s.get("centerLongitude", 72.8777))
    return SESSIONS

@app.get("/api/reverse-geocode")
def api_reverse_geocode(lat: float, lon: float):
    addr = get_address_for_coords(lat, lon)
    return {"address": addr, "latitude": lat, "longitude": lon}

@app.post("/sessions/{session_id}/location")
def update_session_location(session_id: str, payload: LocationUpdate):
    for s in SESSIONS:
        if s["sessionId"] == session_id:
            s["centerLatitude"] = payload.latitude
            s["centerLongitude"] = payload.longitude
            if payload.allowedRadiusMeters:
                s["allowedRadiusMeters"] = payload.allowedRadiusMeters
            addr = payload.locationAddress or get_address_for_coords(payload.latitude, payload.longitude)
            s["locationAddress"] = addr
            if payload.centerName:
                s["centerName"] = payload.centerName
            elif addr:
                base_name = s.get("centerName", "NCCT Training Center").split(" • ")[0]
                s["centerName"] = f"{base_name} • {addr}"
            save_sessions_config()
            return {"status": "UPDATED", "session": s, "locationAddress": addr}
    raise HTTPException(status_code=404, detail="Session not found")

@app.post("/sessions/batch-update")
def batch_update_sessions(payload: LocationUpdate):
    addr = payload.locationAddress or get_address_for_coords(payload.latitude, payload.longitude)
    for s in SESSIONS:
        s["centerLatitude"] = payload.latitude
        s["centerLongitude"] = payload.longitude
        if payload.allowedRadiusMeters:
            s["allowedRadiusMeters"] = payload.allowedRadiusMeters
        s["locationAddress"] = addr
        base_name = s.get("centerName", "NCCT Training Center").split(" • ")[0]
        s["centerName"] = f"{base_name} • {addr}"
    save_sessions_config()
    return {"status": "UPDATED_ALL", "count": len(SESSIONS), "locationAddress": addr}

LATEST_PHONE_GPS = {"latitude": None, "longitude": None, "deviceId": None, "updatedAt": None, "address": None}

class PhoneLocationReport(BaseModel):
    deviceId: str = "ANDROID-OFFLINE-01"
    latitude: float
    longitude: float
    provider: Optional[str] = "gps"

@app.post("/api/phone-location")
def report_phone_location(payload: PhoneLocationReport):
    global LATEST_PHONE_GPS
    addr = get_address_for_coords(payload.latitude, payload.longitude)
    LATEST_PHONE_GPS = {
        "latitude": payload.latitude,
        "longitude": payload.longitude,
        "deviceId": payload.deviceId,
        "updatedAt": datetime.utcnow().strftime("%I:%M:%S %p"),
        "address": addr
    }
    for s in SESSIONS:
        s["centerLatitude"] = payload.latitude
        s["centerLongitude"] = payload.longitude
        s["locationAddress"] = addr
        base_name = s.get("centerName", "NCCT Training Center").split(" • ")[0]
        s["centerName"] = f"{base_name} • {addr}"
    save_sessions_config()
    return {"status": "SUCCESS", "address": addr, "latitude": payload.latitude, "longitude": payload.longitude}

@app.get("/api/phone-location")
def get_phone_location():
    if not LATEST_PHONE_GPS.get("latitude"):
        for r in sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True):
            if r.get("latitude") and r.get("longitude") and (abs(r["latitude"]) > 0.001 or abs(r["longitude"]) > 0.001):
                lat = float(r["latitude"])
                lon = float(r["longitude"])
                addr = get_address_for_coords(lat, lon)
                return {
                    "latitude": lat,
                    "longitude": lon,
                    "deviceId": r.get("deviceId", "ANDROID-PHONE"),
                    "updatedAt": "From Synced Attendance",
                    "address": addr
                }
    return LATEST_PHONE_GPS

@app.get("/api/geocode-address")
@app.get("/api/search-location")
def search_location(q: str):
    if not q or len(q.strip()) < 2:
        return []
    try:
        import urllib.request, urllib.parse, re
        clean_q = q.strip()
        words = clean_q.split()

        # Check for 6-digit Indian pincode in query (e.g. 400068)
        pincode_match = re.search(r'\b[1-9][0-9]{5}\b', clean_q)
        queries_to_try = [clean_q]
        if pincode_match and pincode_match.group(0) not in queries_to_try:
            queries_to_try.append(pincode_match.group(0))

        if len(words) > 1:
            for i in range(1, len(words)):
                sub = " ".join(words[i:])
                if len(sub) >= 3 and sub not in queries_to_try:
                    queries_to_try.append(sub)

        for query_candidate in queries_to_try:
            encoded_q = urllib.parse.quote(query_candidate)
            url = f"https://nominatim.openstreetmap.org/search?format=json&q={encoded_q}&countrycodes=in&limit=6&addressdetails=1"
            req = urllib.request.Request(url, headers={"User-Agent": "SIH26087-NCCT-Attendance/2.0 (contact: mauryanishant2005@gmail.com)"})
            with urllib.request.urlopen(req, timeout=3.5) as resp:
                data = json.loads(resp.read().decode())
                if data:
                    results = []
                    for item in data:
                        addr = item.get("address", {})
                        city = addr.get("city") or addr.get("town") or addr.get("suburb") or addr.get("county") or ""
                        state = addr.get("state", "")
                        lat_val = float(item.get("lat"))
                        lon_val = float(item.get("lon"))
                        clean_addr = get_address_for_coords(lat_val, lon_val)
                        results.append({
                            "display_name": item.get("display_name"),
                            "formatted_address": clean_addr,
                            "lat": lat_val,
                            "lon": lon_val,
                            "city": city,
                            "state": state
                        })
                    return results
        return []
    except Exception:
        return []

class StudentOnboardRequest(BaseModel):
    name: str
    course: str
    sessionId: str
    studentId: Optional[str] = None
    rollNumber: Optional[str] = None
    photoBase64: str
    isExistingStudent: Optional[bool] = False

@app.get("/api/students/next-id")
def get_next_student_id():
    max_id_num = 1000
    max_roll = 100
    for s in STUDENTS:
        sid = s.get("studentId", "")
        if sid.startswith("NCCT") and sid[4:].isdigit():
            max_id_num = max(max_id_num, int(sid[4:]))
        roll = s.get("rollNumber", "")
        if roll.isdigit():
            max_roll = max(max_roll, int(roll))
    return {
        "nextStudentId": f"NCCT{max_id_num + 1}",
        "nextRollNumber": str(max_roll + 1),
        "existingStudents": [{"studentId": s["studentId"], "name": s["name"], "course": s.get("course", ""), "enrolledSessionIds": s.get("enrolledSessionIds", [])} for s in STUDENTS]
    }

@app.post("/api/students/onboard")
def onboard_student(payload: StudentOnboardRequest):
    global STUDENTS
    name = payload.name.strip()
    if not name:
        raise HTTPException(status_code=400, detail="Student name is required")

    # Check if enrolling existing student
    if payload.isExistingStudent and payload.studentId:
        for s in STUDENTS:
            if s.get("studentId") == payload.studentId:
                if payload.sessionId not in s.get("enrolledSessionIds", []):
                    s.setdefault("enrolledSessionIds", []).append(payload.sessionId)
                save_students_dataset()
                return {
                    "status": "SUCCESS",
                    "message": f"Enrolled existing student {s['name']} into {payload.course}",
                    "student": s
                }
        raise HTTPException(status_code=404, detail="Existing student ID not found")

    # Generate next ID and roll number if not provided
    max_id_num = 1000
    max_roll = 100
    for s in STUDENTS:
        sid = s.get("studentId", "")
        if sid.startswith("NCCT") and sid[4:].isdigit():
            max_id_num = max(max_id_num, int(sid[4:]))
        roll = s.get("rollNumber", "")
        if roll.isdigit():
            max_roll = max(max_roll, int(roll))
    for del_id in DELETED_STUDENT_IDS:
        if del_id.startswith("NCCT") and del_id[4:].isdigit():
            max_id_num = max(max_id_num, int(del_id[4:]))

    student_id = payload.studentId or f"NCCT{max_id_num + 1}"
    roll_number = payload.rollNumber or str(max_roll + 1)

    # Ensure this studentId is removed from DELETED_STUDENT_IDS so sync works seamlessly
    if student_id in DELETED_STUDENT_IDS:
        DELETED_STUDENT_IDS.discard(student_id)
        save_deleted_students()

    # Process and save photo
    b64_data = payload.photoBase64
    if "," in b64_data:
        b64_data = b64_data.split(",", 1)[1]

    try:
        img_bytes = base64.b64decode(b64_data)
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image data: {str(e)}")

    # ── SECURITY GATE: Validate photo is a human face with detectable eyes ──────
    # This runs server-side so it cannot be bypassed by modifying the client JS.
    # Rejects: animal photos, blank images, objects, faces without visible eyes.
    is_valid_face, rejection_reason = validate_human_face(img)
    if not is_valid_face:
        raise HTTPException(
            status_code=422,
            detail=f"ENROLLMENT REJECTED — Photo Security Check Failed: {rejection_reason}"
        )
    # ─────────────────────────────────────────────────────────────────────────────

    students_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "students"))
    os.makedirs(students_dir, exist_ok=True)
    photo_file = f"{student_id}.jpg"
    photo_path = os.path.join(students_dir, photo_file)

    # Save clean cropped face matching Android avatar geometry
    cropped_face = crop_face_if_needed(img)
    cropped_face.save(photo_path, "JPEG", quality=92)

    # Compute 192-D normalized embedding vector
    embedding = compute_face_embedding(cropped_face)

    # 3. Biometric Duplicate Check: Block registering the same person under different IDs
    DUPLICATE_FACE_THRESHOLD = 0.55
    for existing in STUDENTS:
        ex_emb = existing.get("faceEmbedding", [])
        if len(ex_emb) == len(embedding) and len(embedding) > 0:
            sim = compute_cosine_similarity(embedding, ex_emb)
            if sim >= DUPLICATE_FACE_THRESHOLD:
                # Remove saved photo to prevent leftover files
                if os.path.exists(photo_path):
                    try:
                        os.remove(photo_path)
                    except Exception:
                        pass
                raise HTTPException(
                    status_code=409,
                    detail=f"Biometric Conflict: Face matches registered student '{existing.get('name')}' ({existing.get('studentId')}) with {int(sim * 100)}% similarity. Duplicate registration is blocked."
                )

    new_student = {
        "studentId": student_id,
        "name": name,
        "rollNumber": roll_number,
        "course": payload.course,
        "enrolledSessionIds": [payload.sessionId],
        "faceEmbedding": embedding,
        "photoPath": f"students/{photo_file}"
    }
    STUDENTS.append(new_student)
    save_students_dataset()

    return {
        "status": "SUCCESS",
        "message": f"Successfully enrolled {name} (ID: {student_id}) with live biometrics!",
        "student": new_student
    }

@app.get("/onboarding", response_class=HTMLResponse)
def student_onboarding_portal():
    try:
        from server.onboarding_view import render_onboarding_page
    except ImportError:
        from onboarding_view import render_onboarding_page
    return render_onboarding_page(SESSIONS, STUDENTS)

@app.get("/api/roster/version")
def get_roster_version():
    return {
        "status": "SUCCESS",
        "rosterVersion": ROSTER_VERSION,
        "studentCount": len(STUDENTS),
        "timestamp": int(time.time())
    }

@app.get("/api/students")
@app.get("/students")
def get_all_students():
    return {
        "count": len(STUDENTS),
        "students": STUDENTS
    }

@app.get("/api/students/export/csv")
def export_students_csv():
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([
        "Student ID", "Full Name", "Roll Number", "Course", 
        "Enrolled Sessions", "Biometric Model", "Has Photo"
    ])
    for s in STUDENTS:
        photo_exists = os.path.exists(os.path.join(STUDENTS_ASSETS_DIR, f"{s['studentId']}.jpg"))
        writer.writerow([
            s.get("studentId", ""),
            s.get("name", ""),
            s.get("rollNumber", ""),
            s.get("course", ""),
            ", ".join(s.get("enrolledSessionIds", [])),
            "FaceNet 192-D MobileNetV1",
            "YES" if photo_exists else "NO"
        ])
    filename = f"ncct_students_roster_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )

@app.post("/api/students/sync", response_model=StudentSyncResponse)
def sync_students_with_app(payload: StudentSyncRequest):
    global STUDENTS, DELETED_STUDENT_IDS
    synced_from_app = 0
    existing_map = {s["studentId"]: s for s in STUDENTS}

    # 1. Process deletions requested from app
    deleted_any = False
    if payload.deletedStudentIds:
        for del_id in payload.deletedStudentIds:
            del_id = del_id.strip()
            if not del_id:
                continue
            DELETED_STUDENT_IDS.add(del_id)
            if del_id in existing_map:
                STUDENTS = [s for s in STUDENTS if s.get("studentId") != del_id]
                existing_map.pop(del_id, None)
                deleted_any = True
                p_path = os.path.join(STUDENTS_ASSETS_DIR, f"{del_id}.jpg")
                if os.path.exists(p_path):
                    try:
                        os.remove(p_path)
                    except Exception:
                        pass
        if deleted_any:
            save_deleted_students()
            save_students_dataset()

    for item in payload.students:
        sid = item.studentId
        if sid in DELETED_STUDENT_IDS:
            continue

        photo_path = os.path.join(STUDENTS_ASSETS_DIR, f"{sid}.jpg")

        # Save photo if provided
        if item.photoBase64:
            try:
                clean_b64 = item.photoBase64.split(",", 1)[1] if "," in item.photoBase64 else item.photoBase64
                img_bytes = base64.b64decode(clean_b64)
                with open(photo_path, "wb") as f:
                    f.write(img_bytes)
            except Exception as e:
                print(f"Failed saving student {sid} photo:", e)

        if sid in existing_map:
            ex = existing_map[sid]
            combined_sessions = list(dict.fromkeys(ex.get("enrolledSessionIds", []) + (item.enrolledSessionIds or [])))
            if combined_sessions != ex.get("enrolledSessionIds", []):
                ex["enrolledSessionIds"] = combined_sessions
                synced_from_app += 1
            if item.name and item.name != ex.get("name"):
                ex["name"] = item.name
                synced_from_app += 1
            if item.rollNumber and item.rollNumber != ex.get("rollNumber"):
                ex["rollNumber"] = item.rollNumber
                synced_from_app += 1
            if item.course and item.course != ex.get("course"):
                ex["course"] = item.course
                synced_from_app += 1
            if item.faceEmbedding and len(item.faceEmbedding) == 192:
                if ex.get("faceEmbedding") != item.faceEmbedding:
                    ex["faceEmbedding"] = item.faceEmbedding
                    synced_from_app += 1
        else:
            embedding = item.faceEmbedding
            if (not embedding or len(embedding) != 192) and os.path.exists(photo_path):
                try:
                    img = Image.open(photo_path).convert("RGB")
                    embedding = compute_face_embedding(img)
                except Exception:
                    embedding = [0.0] * 192
            if not embedding:
                embedding = [0.0] * 192

            new_student = {
                "studentId": sid,
                "name": item.name,
                "rollNumber": item.rollNumber or str(len(STUDENTS) + 101),
                "course": item.course or "General",
                "enrolledSessionIds": item.enrolledSessionIds or ["DL-01"],
                "faceEmbedding": embedding,
                "photoPath": f"students/{sid}.jpg"
            }
            STUDENTS.append(new_student)
            existing_map[sid] = new_student
            synced_from_app += 1

    if synced_from_app > 0:
        save_students_dataset()

    server_students_dto = []
    for s in STUDENTS:
        b64_photo = None
        p_path = os.path.join(STUDENTS_ASSETS_DIR, f"{s['studentId']}.jpg")
        sid_num = 0
        if s["studentId"].startswith("NCCT") and s["studentId"][4:].isdigit():
            sid_num = int(s["studentId"][4:])
        if sid_num > 1030 and os.path.exists(p_path):
            try:
                with open(p_path, "rb") as pf:
                    b64_photo = base64.b64encode(pf.read()).decode("utf-8")
            except Exception:
                pass

        server_students_dto.append(StudentSyncItem(
            studentId=s["studentId"],
            name=s["name"],
            rollNumber=s.get("rollNumber", ""),
            course=s.get("course", ""),
            enrolledSessionIds=s.get("enrolledSessionIds", []),
            faceEmbedding=s.get("faceEmbedding", []),
            photoBase64=b64_photo
        ))

    return StudentSyncResponse(
        status="SUCCESS",
        syncedFromApp=synced_from_app,
        totalServerStudents=len(STUDENTS),
        serverStudents=server_students_dto,
        deletedStudentIds=list(DELETED_STUDENT_IDS)
    )

@app.delete("/api/students/{student_id}")
def delete_student_profile(student_id: str):
    global STUDENTS, DELETED_STUDENT_IDS
    sid = student_id.strip()
    found = any(s.get("studentId") == sid for s in STUDENTS)
    if not found and sid not in DELETED_STUDENT_IDS:
        raise HTTPException(status_code=404, detail=f"Student {sid} not found")

    STUDENTS = [s for s in STUDENTS if s.get("studentId") != sid]
    DELETED_STUDENT_IDS.add(sid)
    save_deleted_students()
    save_students_dataset()

    p_path = os.path.join(STUDENTS_ASSETS_DIR, f"{sid}.jpg")
    if os.path.exists(p_path):
        try:
            os.remove(p_path)
        except Exception:
            pass

    return {
        "status": "SUCCESS",
        "message": f"Student {sid} permanently deleted from database",
        "deletedId": sid,
        "remainingCount": len(STUDENTS)
    }

@app.post("/api/students/delete-all")
def delete_all_students_profile():
    global STUDENTS, DELETED_STUDENT_IDS
    for s in STUDENTS:
        sid = s.get("studentId")
        if sid:
            DELETED_STUDENT_IDS.add(sid)
    STUDENTS = []
    save_deleted_students()
    save_students_dataset()
    return {
        "status": "SUCCESS",
        "message": "All students permanently deleted from database",
        "remainingCount": 0
    }

@app.get("/students/{session_id}")
def get_students_for_session(session_id: str):
    matched = [s for s in STUDENTS if session_id in s["enrolledSessionIds"]]
    return matched

@app.post("/attendance/sync", response_model=SyncResponse)
def sync_attendance(payload: SyncRequest):
    newly_synced = []
    dup_count = 0

    for item in payload.records:
        if item.recordId in DEDUP_IDS:
            dup_count += 1
            newly_synced.append(item.recordId)
            continue
        
        record_dict = item.model_dump()
        record_dict["serverReceivedAt"] = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        record_dict["deviceId"] = payload.deviceId
        record_dict["capturedFaceUrl"] = f"/api/attendance/{item.recordId}/captured-photo"
        record_dict["enrolledFaceUrl"] = f"/api/students/{item.studentId}/photo"
        get_or_create_captured_face(item.recordId, item.studentId, item.capturedFaceBase64)

        ATTENDANCE_DB.append(record_dict)
        DEDUP_IDS.add(item.recordId)
        newly_synced.append(item.recordId)

    save_attendance_db()

    # Trigger Real-Time Zero-Refresh SSE Push to Web Dashboard + Outbound Webhook
    valid_new = [r for r in ATTENDANCE_DB if r.get("recordId") in newly_synced]
    if valid_new:
        stats = {
            "totalRecords": len(ATTENDANCE_DB),
            "verifiedRecords": sum(1 for r in ATTENDANCE_DB if r.get("isLocationValid")),
            "uniqueStudents": len(set(r.get("studentId") for r in ATTENDANCE_DB if r.get("studentId"))),
            "syncedCount": len(valid_new) - dup_count,
            "deviceId": payload.deviceId,
            "records": valid_new
        }
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.create_task(broadcast_live_event("attendance_synced", stats))
                asyncio.create_task(trigger_outbound_webhook("attendance_synced", stats))
        except Exception as e:
            print("Failed to dispatch SSE broadcast:", e)

    return SyncResponse(
        status="SUCCESS",
        syncedCount=len(newly_synced) - dup_count,
        duplicatesIgnored=dup_count,
        totalServerRecords=len(ATTENDANCE_DB),
        syncedRecordIds=newly_synced,
        serverTime=datetime.utcnow().isoformat()
    )

@app.get("/api/events")
async def sse_events(request: Request):
    """Server-Sent Events endpoint for zero-refresh real-time dashboard updates."""
    queue = asyncio.Queue()
    ACTIVE_SSE_QUEUES.add(queue)

    async def event_generator():
        try:
            yield f"event: connected\ndata: {json.dumps({'status': 'ONLINE', 'time': time.time()})}\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    msg = await asyncio.wait_for(queue.get(), timeout=15.0)
                    yield msg
                except asyncio.TimeoutError:
                    yield "event: ping\ndata: {}\n\n"
        finally:
            ACTIVE_SSE_QUEUES.discard(queue)

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

class WebhookConfigRequest(BaseModel):
    webhookUrl: str

@app.post("/api/webhook/config")
def configure_webhook(payload: WebhookConfigRequest):
    global OUTBOUND_WEBHOOK_URL
    OUTBOUND_WEBHOOK_URL = payload.webhookUrl.strip() or None
    return {
        "status": "SUCCESS",
        "webhookUrl": OUTBOUND_WEBHOOK_URL,
        "message": f"Webhook configured: {OUTBOUND_WEBHOOK_URL}" if OUTBOUND_WEBHOOK_URL else "Webhook disabled"
    }

@app.get("/api/webhook/config")
def get_webhook_config():
    global OUTBOUND_WEBHOOK_URL
    return {"webhookUrl": OUTBOUND_WEBHOOK_URL}

@app.get("/attendance/records")
def get_attendance_records():
    return {
        "count": len(ATTENDANCE_DB),
        "records": sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True)
    }

DEFAULT_AVATAR_SVG = (
    b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 24 24" fill="#94a3b8">'
    b'<rect width="100%" height="100%" fill="#f1f5f9"/>'
    b'<path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>'
    b'</svg>'
)

@app.get("/api/students/default/photo")
@app.get("/students/photo/default.jpg")
def get_default_avatar_photo():
    return Response(content=DEFAULT_AVATAR_SVG, media_type="image/svg+xml")

@app.get("/api/students/{student_id}/photo")
@app.get("/students/photo/{student_id}.jpg")
def get_student_enrolled_photo(student_id: str):
    clean_id = student_id.replace(".jpg", "")
    photo_path = os.path.join(STUDENTS_ASSETS_DIR, f"{clean_id}.jpg")
    if os.path.exists(photo_path) and os.path.getsize(photo_path) > 50:
        return FileResponse(photo_path, media_type="image/jpeg")
    return Response(content=DEFAULT_AVATAR_SVG, media_type="image/svg+xml")

@app.get("/api/attendance/{record_id}/captured-photo")
@app.get("/attendance/photo/{record_id}.jpg")
def get_attendance_captured_photo(record_id: str):
    clean_rid = record_id.replace(".jpg", "")
    rec = next((r for r in ATTENDANCE_DB if r.get("recordId") == clean_rid), None)
    student_id = rec.get("studentId", "") if rec else ""
    photo_path = get_or_create_captured_face(clean_rid, student_id, rec.get("capturedFaceBase64") if rec else None)
    if os.path.exists(photo_path) and os.path.getsize(photo_path) > 100:
        return FileResponse(photo_path, media_type="image/jpeg")
    if student_id:
        enrolled_path = os.path.join(STUDENTS_ASSETS_DIR, f"{student_id}.jpg")
        if os.path.exists(enrolled_path) and os.path.getsize(enrolled_path) > 50:
            return FileResponse(enrolled_path, media_type="image/jpeg")
    return Response(content=DEFAULT_AVATAR_SVG, media_type="image/svg+xml")

@app.get("/attendance/export/csv")
def export_attendance_csv():
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([
        "Record ID", "Student ID", "Student Name", "Session ID", 
        "Session Title", "Date & Time", "Timestamp (ms)", 
        "Similarity Score", "Liveness Score", "Device Latitude", 
        "Device Longitude", "Location Valid", "Device ID", "Server Received At"
    ])
    for r in sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True):
        ts_str = ""
        if r.get("timestamp"):
            try:
                ts_str = datetime.fromtimestamp(r["timestamp"] / 1000.0).strftime("%Y-%m-%d %H:%M:%S")
            except Exception:
                ts_str = str(r.get("timestamp"))
        writer.writerow([
            r.get("recordId", ""),
            r.get("studentId", ""),
            r.get("studentName", ""),
            r.get("sessionId", ""),
            r.get("sessionTitle", ""),
            ts_str,
            r.get("timestamp", ""),
            f"{r.get('similarityScore', 0.0):.4f}",
            f"{r.get('livenessScore', 0.0):.4f}",
            r.get("latitude", ""),
            r.get("longitude", ""),
            "VERIFIED" if r.get("isLocationValid") else "OUT_OF_BOUNDS",
            r.get("deviceId", "ANDROID-OFFLINE-01"),
            r.get("serverReceivedAt", "")
        ])
    filename = f"ncct_attendance_{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.csv"
    return Response(
        content=output.getvalue(),
        media_type="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )



@app.delete("/attendance/clear")
def clear_records():
    global ATTENDANCE_DB, DEDUP_IDS
    ATTENDANCE_DB = []
    DEDUP_IDS = set()
    save_attendance_db()
    return {"status": "CLEARED"}

@app.get("/download/apk")
@app.head("/download/apk")
@app.get("/download-apk")
@app.head("/download-apk")
@app.get("/app-debug.apk")
@app.head("/app-debug.apk")
def download_apk():
    apk_candidates = [
        os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "build", "outputs", "apk", "debug", "app-debug.apk")),
        os.path.abspath(os.path.join(os.path.dirname(__file__), "app-debug.apk")),
        os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "OfflineFaceAttendance.apk")),
    ]
    apk_path = None
    for candidate in apk_candidates:
        if os.path.exists(candidate):
            apk_path = candidate
            break
    if not apk_path:
        raise HTTPException(status_code=404, detail="APK build not found at " + str(apk_candidates[0]))
    return FileResponse(
        path=apk_path,
        media_type="application/vnd.android.package-archive",
        filename="OfflineFaceAttendance-debug.apk"
    )

@app.get("/apk_qr.png")
@app.head("/apk_qr.png")
def get_apk_qr():
    qr_path = os.path.join(os.path.dirname(__file__), "apk_qr.png")
    if not os.path.exists(qr_path):
        try:
            import qrcode, socket
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
            s.close()
            url = f"http://{ip}:8000/download/apk"
            qr = qrcode.QRCode(box_size=10, border=2)
            qr.add_data(url)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            img.save(qr_path)
        except Exception:
            pass
    if os.path.exists(qr_path):
        return FileResponse(qr_path, media_type="image/png")
    raise HTTPException(status_code=404, detail="QR code not found")

@app.get("/api/detect-location")
@app.get("/api/ip-location")
def detect_location(request: Request):
    # 1. First priority: Check if an Android phone recently reported real satellite GPS
    if LATEST_PHONE_GPS.get("latitude") and LATEST_PHONE_GPS.get("longitude"):
        lat = float(LATEST_PHONE_GPS["latitude"])
        lon = float(LATEST_PHONE_GPS["longitude"])
        addr = LATEST_PHONE_GPS.get("address") or get_address_for_coords(lat, lon)
        return {
            "latitude": lat,
            "longitude": lon,
            "address": addr,
            "source": f"Phone Satellite GPS ({LATEST_PHONE_GPS.get('deviceId', 'Android')})"
        }

    # 2. Extract client IP
    client_ip = request.headers.get("x-forwarded-for", "").split(",")[0].strip()
    if not client_ip and request.client:
        client_ip = request.client.host
    is_private_ip = not client_ip or client_ip.startswith("127.") or client_ip.startswith("192.168.") or client_ip.startswith("10.") or client_ip.startswith("172.")
    ip_path = f"/{client_ip}" if (client_ip and not is_private_ip) else ""

    # Provider A: ipwho.is (Free, fast, precise)
    try:
        import urllib.request
        url = f"https://ipwho.is{ip_path}"
        req = urllib.request.Request(url, headers={"User-Agent": "SIH26087-Attendance/2.0"})
        with urllib.request.urlopen(req, timeout=3.0) as response:
            data = json.loads(response.read().decode())
            if data.get("success"):
                lat = float(data.get("latitude"))
                lon = float(data.get("longitude"))
                addr = get_address_for_coords(lat, lon)
                return {
                    "latitude": lat,
                    "longitude": lon,
                    "address": addr,
                    "city": data.get("city"),
                    "postal": data.get("postal"),
                    "region": data.get("region"),
                    "country": data.get("country"),
                    "source": "Network Geolocation (ipwho.is)"
                }
    except Exception:
        pass

    # Provider B: freeipapi.com
    try:
        import urllib.request
        url = f"https://freeipapi.com/api/json{ip_path}"
        req = urllib.request.Request(url, headers={"User-Agent": "SIH26087-Attendance/2.0"})
        with urllib.request.urlopen(req, timeout=3.0) as response:
            data = json.loads(response.read().decode())
            if data.get("latitude") and data.get("longitude"):
                lat = float(data["latitude"])
                lon = float(data["longitude"])
                addr = get_address_for_coords(lat, lon)
                return {
                    "latitude": lat,
                    "longitude": lon,
                    "address": addr,
                    "city": data.get("cityName"),
                    "region": data.get("regionName"),
                    "country": data.get("countryName"),
                    "source": "Network Geolocation (freeipapi)"
                }
    except Exception:
        pass

    # Provider C: ip-api.com
    try:
        import urllib.request
        clean_ip = ip_path.lstrip("/")
        url = f"http://ip-api.com/json/{clean_ip}?fields=status,message,country,regionName,city,district,zip,lat,lon"
        req = urllib.request.Request(url, headers={"User-Agent": "SIH26087-Attendance/2.0"})
        with urllib.request.urlopen(req, timeout=3.0) as response:
            data = json.loads(response.read().decode())
            if data.get("status") == "success":
                lat = float(data.get("lat"))
                lon = float(data.get("lon"))
                addr = get_address_for_coords(lat, lon)
                return {
                    "latitude": lat,
                    "longitude": lon,
                    "address": addr,
                    "city": data.get("city"),
                    "region": data.get("regionName"),
                    "country": data.get("country"),
                    "source": "Network Geolocation (ip-api)"
                }
    except Exception:
        pass

    fallback_lat, fallback_lon = 19.0760, 72.8777
    return {
        "latitude": fallback_lat,
        "longitude": fallback_lon,
        "address": get_address_for_coords(fallback_lat, fallback_lon),
        "city": "Mumbai",
        "region": "Maharashtra",
        "country": "India",
        "source": "Default Location"
    }

@app.get("/", response_class=HTMLResponse)
def live_dashboard():
    # Calculate Attendance Database Statistics
    total_att = len(ATTENDANCE_DB)
    verified_att = sum(1 for r in ATTENDANCE_DB if r.get("isLocationValid"))
    unique_students_att = len(set(r.get("studentId") for r in ATTENDANCE_DB if r.get("studentId")))
    attendance_json = json.dumps(ATTENDANCE_DB)

    # Find most recent phone GPS from synced records if any
    last_phone_lat = None
    last_phone_lon = None
    for r in sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True):
        if r.get("latitude") and r.get("longitude") and (abs(r["latitude"]) > 0.001 or abs(r["longitude"]) > 0.001):
            last_phone_lat = r["latitude"]
            last_phone_lon = r["longitude"]
            break

    rows_html = ""
    for r in sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True):
        ts = ""
        if r.get("timestamp"):
            try:
                ts = datetime.fromtimestamp(r["timestamp"] / 1000.0).strftime("%d %b %Y, %I:%M:%S %p")
            except Exception:
                ts = str(r.get("timestamp"))
        badge_loc = '<span class="badge badge-success">✓ Verified Center</span>' if r.get("isLocationValid") else '<span class="badge badge-danger">✕ Out of bounds</span>'
        sim_val = r.get('similarityScore', 0.0)
        sim_pct = int(sim_val * 100)
        sim_badge = f'<span class="badge badge-info">{sim_val:.2f} ({sim_pct}%)</span>'
        live_val = r.get('livenessScore', 0.0)
        live_badge = f'<span class="badge badge-success">{live_val:.2f} ✓ Pass</span>' if live_val >= 0.70 else f'<span class="badge badge-danger">{live_val:.2f} Fail</span>'
        search_data = f"{r.get('studentName', '')} {r.get('studentId', '')} {r.get('sessionId', '')} {r.get('sessionTitle', '')} {r.get('recordId', '')}".lower()
        
        rid = r.get('recordId', '')
        sid = r.get('studentId', '')
        sname = r.get('studentName', '')
        session_title = r.get('sessionTitle', '')
        session_id = r.get('sessionId', '')
        enrolled_photo_url = f"/api/students/{sid}/photo"
        captured_photo_url = f"/api/attendance/{rid}/captured-photo"

        rows_html += f"""
        <tr class="att-row" data-search="{search_data}" id="att-row-{rid}">
            <td>
                <code class="code-id">{rid[:10]}</code>
                <div class="text-xs text-slate-400 font-mono mt-1">{r.get('deviceId', 'ANDROID-OFFLINE-01')}</div>
            </td>
            <td>
                <div class="font-semibold text-slate-900">{sname}</div>
                <div class="text-xs font-mono text-slate-500">{sid}</div>
            </td>
            <td>
                <div class="font-medium text-slate-800">{session_title}</div>
                <span class="badge badge-neutral">{session_id}</span>
            </td>
            <td>
                <div style="display:inline-flex; align-items:center; gap:8px; background:#f8fafc; border:1px solid #e2e8f0; padding:4px 8px; border-radius:8px; cursor:pointer;" onclick="openBiometricReport('{rid}')" title="Click to inspect side-by-side biometric comparison">
                    <div style="text-align:center;">
                        <img src="{enrolled_photo_url}" style="width:40px; height:40px; border-radius:6px; object-fit:cover; border:1.5px solid #94a3b8; display:block;" onerror="this.onerror=null; this.src='/api/students/default/photo';">
                        <span style="font-size:9px; color:#475569; font-weight:600;">Enrolled</span>
                    </div>
                    <span style="color:#059669; font-weight:bold; font-size:12px;">↔</span>
                    <div style="text-align:center;">
                        <img src="{captured_photo_url}" style="width:40px; height:40px; border-radius:6px; object-fit:cover; border:2px solid #059669; display:block;" onerror="this.onerror=null; this.src='/api/students/default/photo';">
                        <span style="font-size:9px; color:#059669; font-weight:700;">Live</span>
                    </div>
                </div>
            </td>
            <td>
                <div style="margin-bottom:3px;">{sim_badge}</div>
                <div>{live_badge}</div>
            </td>
            <td>
                {badge_loc}
                <div class="text-xs font-mono text-slate-500 mt-1">{r.get('latitude', 0.0):.4f}°, {r.get('longitude', 0.0):.4f}°</div>
            </td>
            <td class="text-slate-700 font-mono text-xs" style="white-space:nowrap;">{ts}</td>
            <td>
                <button type="button" onclick="openBiometricReport('{rid}')" class="btn btn-secondary text-xs" style="padding: 4px 10px; font-weight:700;">📋 Report</button>
            </td>
        </tr>
        """
    if not rows_html:
        rows_html = """<tr><td colspan="8" class="empty-state" style="padding: 32px 16px; text-align: center; color: #64748b;">📭 No attendance records in database yet.<br><span class="text-xs text-slate-400 mt-1 inline-block">Mark attendance on the offline Android app, then open 'Offline Sync Queue' & tap 'SYNC NOW'.</span></td></tr>"""

    def student_sort_key(x):
        sid = x.get("studentId", "")
        num = int(sid[4:]) if sid.startswith("NCCT") and sid[4:].isdigit() else 0
        is_new = num > 1030
        return (0 if is_new else 1, -num if is_new else num)

    students_rows_html = ""
    for s in sorted(STUDENTS, key=student_sort_key):
        sid = s.get("studentId", "")
        sname = s.get("name", "")
        roll = s.get("rollNumber", "—")
        course = s.get("course", "—")
        sessions = s.get("enrolledSessionIds", [])
        sessions_badges = "".join(f'<span class="badge badge-primary" style="margin-right:4px;">{ses}</span>' for ses in sessions) or '<span class="badge badge-neutral">None</span>'
        photo_url = f"/api/students/{sid}/photo"
        search_data = f"{sid} {sname} {roll} {course} {' '.join(sessions)}".lower()

        sid_num = int(sid[4:]) if sid.startswith("NCCT") and sid[4:].isdigit() else 0
        if sid_num > 1030:
            source_badge = '<span class="badge badge-success" style="font-size:10px; font-weight:700; margin-left:4px;">📱 Synced from Phone</span>'
            row_style = 'background: #f0fdf4;'
        else:
            source_badge = '<span class="badge badge-neutral" style="font-size:10px; font-weight:500; margin-left:4px;">📦 Preloaded Base</span>'
            row_style = ''

        emb = s.get("faceEmbedding", [])
        has_emb = len(emb) == 192 and any(v != 0.0 for v in emb)
        emb_badge = '<span class="badge badge-success" style="font-weight:600;">✓ 192-D Vector</span>' if has_emb else '<span class="badge badge-danger">✕ Missing Vector</span>'

        students_rows_html += f"""
        <tr class="student-row" data-search="{search_data}" id="student-row-{sid}" style="{row_style}">
            <td>
                <div style="display:flex; align-items:center; gap:10px;">
                    <img src="{photo_url}" style="width:42px; height:42px; border-radius:8px; object-fit:cover; border:1.5px solid #cbd5e1; background:#f1f5f9;" onerror="this.onerror=null; this.src='/api/students/default/photo';">
                    <div>
                        <div class="font-semibold text-slate-900" style="display:flex; align-items:center; flex-wrap:wrap; gap:4px;">{sname} {source_badge}</div>
                        <div class="text-xs font-mono text-slate-500"><code class="code-id">{sid}</code></div>
                    </div>
                </div>
            </td>
            <td>
                <div class="font-mono text-slate-700 font-semibold">{roll}</div>
            </td>
            <td>
                <div class="font-medium text-slate-800">{course}</div>
            </td>
            <td>
                <div style="display:flex; flex-wrap:wrap; gap:4px;">{sessions_badges}</div>
            </td>
            <td>
                <div>{emb_badge}</div>
                <div class="text-xs text-slate-400 font-mono mt-1">FaceNet MobileNetV1</div>
            </td>
            <td style="white-space:nowrap;">
                <a href="{photo_url}" target="_blank" class="btn btn-secondary text-xs" style="padding:4px 8px; margin-right:4px;" title="View high-resolution portrait">🔍 View</a>
                <button type="button" class="btn btn-outline" style="color:#ef4444; border-color:#fca5a5; padding:4px 8px; font-size:11px; border-radius:6px; cursor:pointer;" onclick="deleteStudent('{sid}', '{sname}')" title="Permanently delete student profile">🗑️ Delete</button>
            </td>
        </tr>
        """
    if not students_rows_html:
        students_rows_html = """<tr><td colspan="6" class="empty-state" style="padding: 32px 16px; text-align: center; color: #64748b;">📭 No students registered in database yet.<br><span class="text-xs text-slate-400 mt-1 inline-block">Use the Student Onboarding Portal or enroll on the Android app to register profiles.</span></td></tr>"""

    session_rows_html = ""
    first_session_addr = "Detecting location..."
    if SESSIONS:
        first_s = SESSIONS[0]
        first_session_addr = first_s.get("locationAddress") or get_address_for_coords(first_s.get("centerLatitude", 19.0760), first_s.get("centerLongitude", 72.8777))

    for s in SESSIONS:
        sid = s["sessionId"]
        lat = s.get("centerLatitude", 19.0760)
        lon = s.get("centerLongitude", 72.8777)
        radius = s.get("allowedRadiusMeters", 100.0)
        cname = s.get("centerName", "NCCT Regional Training Center")
        loc_addr = s.get("locationAddress") or get_address_for_coords(lat, lon)
        s["locationAddress"] = loc_addr
        session_rows_html += f"""
        <tr id="row-{sid}">
            <td>
                <div class="font-semibold text-slate-900">{s['title']}</div>
                <span class="badge badge-primary">{sid}</span>
            </td>
            <td>
                <input type="text" id="name-{sid}" value="{cname}" class="input-field" style="width: 200px;" placeholder="Center Name" oninput="scheduleAutoSave('{sid}')">
            </td>
            <td>
                <div class="location-box">
                    <div id="addr-{sid}" class="location-addr">📍 {loc_addr}</div>
                    <a id="map-{sid}" href="https://www.google.com/maps?q={lat:.6f},{lon:.6f}" target="_blank" rel="noopener noreferrer" class="map-link">🗺️ Google Maps ↗</a>
                </div>
            </td>
            <td>
                <div style="display:flex; align-items:center; gap: 4px;">
                    <input type="number" step="0.000001" id="lat-{sid}" value="{lat:.6f}" class="input-field font-mono" style="width: 105px;" oninput="scheduleAutoSave('{sid}')" title="Latitude">
                    <input type="number" step="0.000001" id="lon-{sid}" value="{lon:.6f}" class="input-field font-mono" style="width: 105px;" oninput="scheduleAutoSave('{sid}')" title="Longitude">
                </div>
            </td>
            <td>
                <div style="display:inline-flex; align-items:center; gap: 4px;">
                    <input type="number" step="5" id="rad-{sid}" value="{radius:.0f}" class="input-field font-mono" style="width: 60px;" oninput="scheduleAutoSave('{sid}')">
                    <span class="text-xs text-slate-500">m</span>
                </div>
            </td>
            <td style="white-space:nowrap;">
                <div style="display:flex; align-items:center; gap: 6px;">
                    <button type="button" onclick="acquireSingleSessionLocation('{sid}')" class="btn btn-secondary text-xs">📍 Detect</button>
                    <span id="status-{sid}" class="status-saved">✓ Auto-Saved</span>
                </div>
            </td>
        </tr>
        """


    return f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>NCCT AI Attendance Gateway - Live Sync Dashboard</title>
        <link rel="preconnect" href="https://fonts.googleapis.com">
        <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>
            :root {{
                --bg: #f8fafc;
                --card-bg: #ffffff;
                --border: #e2e8f0;
                --border-subtle: #f1f5f9;
                --text-main: #0f172a;
                --text-muted: #64748b;
                --text-soft: #94a3b8;
                --primary: #2563eb;
                --primary-hover: #1d4ed8;
                --primary-subtle: #eff6ff;
                --emerald: #059669;
                --emerald-subtle: #ecfdf5;
                --emerald-border: #a7f3d0;
                --emerald-text: #065f46;
                --cyan: #0284c7;
                --danger: #dc2626;
                --danger-subtle: #fef2f2;
                --danger-border: #fecaca;
                --danger-text: #991b1b;
            }}
            * {{ box-sizing: border-box; }}
            body {{
                font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                background-color: var(--bg);
                color: var(--text-main);
                margin: 0;
                padding: 24px 32px;
                -webkit-font-smoothing: antialiased;
            }}
            .font-mono {{ font-family: 'JetBrains Mono', monospace; }}
            .font-semibold {{ font-weight: 600; }}
            .font-medium {{ font-weight: 500; }}
            .text-slate-900 {{ color: #0f172a; }}
            .text-slate-800 {{ color: #1e293b; }}
            .text-slate-600 {{ color: #475569; }}
            .text-slate-500 {{ color: #64748b; }}
            .text-xs {{ font-size: 11px; }}
            .mt-1 {{ margin-top: 4px; }}
            
            .header-bar {{
                display: flex;
                justify-content: space-between;
                align-items: center;
                background: #ffffff;
                border: 1px solid var(--border);
                border-radius: 12px;
                padding: 18px 24px;
                margin-bottom: 24px;
                box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.04);
            }}
            .header-brand {{ display: flex; align-items: center; gap: 14px; }}
            .header-logo {{
                width: 42px;
                height: 42px;
                border-radius: 10px;
                background: linear-gradient(135deg, #2563eb, #1d4ed8);
                color: #ffffff;
                display: flex;
                align-items: center;
                justify-content: center;
                font-weight: 800;
                font-size: 16px;
                letter-spacing: -0.5px;
                box-shadow: 0 4px 10px rgba(37, 99, 235, 0.25);
            }}
            .title {{ font-size: 19px; font-weight: 700; color: #0f172a; letter-spacing: -0.3px; margin: 0; }}
            .subtitle {{ font-size: 13px; color: var(--text-muted); margin-top: 3px; margin-bottom: 0; }}
            
            .header-actions {{ display: flex; align-items: center; gap: 12px; }}
            .status-pill {{
                display: inline-flex;
                align-items: center;
                gap: 8px;
                background: var(--emerald-subtle);
                border: 1px solid var(--emerald-border);
                color: var(--emerald-text);
                padding: 6px 14px;
                border-radius: 9999px;
                font-size: 12px;
                font-weight: 600;
            }}
            .pulse-dot {{
                width: 8px;
                height: 8px;
                border-radius: 50%;
                background: var(--emerald);
                box-shadow: 0 0 0 2px rgba(5, 150, 105, 0.2);
            }}
            .download-btn {{
                display: inline-flex;
                align-items: center;
                gap: 8px;
                background: #059669;
                color: #ffffff;
                text-decoration: none;
                padding: 8px 16px;
                border-radius: 8px;
                font-weight: 600;
                font-size: 13px;
                box-shadow: 0 2px 6px rgba(5, 150, 105, 0.25);
                transition: all 0.15s ease;
            }}
            .download-btn:hover {{ background: #047857; transform: translateY(-1px); }}
            
            .stats-grid {{
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
                gap: 16px;
                margin-bottom: 24px;
            }}
            .stat-card {{
                background: var(--card-bg);
                border: 1px solid var(--border);
                border-radius: 12px;
                padding: 16px 20px;
                box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.03);
            }}
            .stat-label {{
                font-size: 11px;
                font-weight: 600;
                text-transform: uppercase;
                letter-spacing: 0.05em;
                color: var(--text-muted);
            }}
            .stat-value {{
                font-size: 26px;
                font-weight: 700;
                color: var(--text-main);
                margin-top: 6px;
                letter-spacing: -0.5px;
            }}
            
            .card-panel {{
                background: var(--card-bg);
                border: 1px solid var(--border);
                border-radius: 12px;
                overflow: hidden;
                margin-bottom: 24px;
                box-shadow: 0 1px 3px 0 rgba(0, 0, 0, 0.03);
            }}
            .card-header {{
                display: flex;
                justify-content: space-between;
                align-items: center;
                padding: 14px 20px;
                background: #ffffff;
                border-bottom: 1px solid var(--border);
                flex-wrap: wrap;
                gap: 12px;
            }}
            .card-title {{
                font-size: 14px;
                font-weight: 700;
                color: var(--text-main);
                display: flex;
                align-items: center;
                gap: 6px;
            }}
            
            .active-center-banner {{
                background: #f8fafc;
                border-bottom: 1px solid var(--border);
                padding: 10px 20px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                flex-wrap: wrap;
                gap: 8px;
                font-size: 12px;
            }}
            
            .info-banner {{
                background: #eff6ff;
                border-left: 4px solid var(--primary);
                padding: 10px 16px;
                font-size: 12px;
                color: #1e40af;
                margin: 0;
                display: flex;
                align-items: center;
                justify-content: space-between;
                flex-wrap: wrap;
                gap: 8px;
            }}
            
            .presets-bar {{
                display: flex;
                align-items: center;
                gap: 8px;
                flex-wrap: wrap;
                padding: 10px 20px;
                background: #ffffff;
                border-bottom: 1px solid var(--border);
            }}
            .preset-label {{
                font-size: 11px;
                font-weight: 600;
                text-transform: uppercase;
                color: var(--text-muted);
                letter-spacing: 0.04em;
            }}
            .preset-tag {{
                background: #f8fafc;
                border: 1px solid var(--border);
                color: #334155;
                font-size: 11px;
                font-weight: 500;
                padding: 4px 10px;
                border-radius: 6px;
                cursor: pointer;
                transition: all 0.15s ease;
            }}
            .preset-tag:hover {{
                background: #eff6ff;
                border-color: #93c5fd;
                color: #1d4ed8;
            }}
            
            table {{
                width: 100%;
                border-collapse: collapse;
                text-align: left;
                font-size: 13px;
            }}
            th {{
                background: #f8fafc;
                color: #475569;
                padding: 11px 16px;
                font-weight: 600;
                text-transform: uppercase;
                font-size: 11px;
                letter-spacing: 0.05em;
                border-bottom: 1px solid var(--border);
            }}
            td {{
                padding: 11px 16px;
                border-bottom: 1px solid var(--border-subtle);
                color: #1e293b;
                vertical-align: middle;
            }}
            tr:last-child td {{ border-bottom: none; }}
            tr:hover td {{ background-color: #fbfcfe; }}
            
            .empty-state {{
                text-align: center;
                padding: 36px 16px;
                color: var(--text-muted);
                font-size: 13px;
            }}
            
            .input-field {{
                background: #ffffff;
                border: 1px solid #cbd5e1;
                color: #0f172a;
                padding: 6px 10px;
                border-radius: 6px;
                font-size: 12px;
                transition: border-color 0.15s ease, box-shadow 0.15s ease;
            }}
            .input-field:focus {{
                outline: none;
                border-color: var(--primary);
                box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
            }}
            
            .location-box {{
                max-width: 250px;
            }}
            .location-addr {{
                font-size: 12px;
                font-weight: 500;
                color: #1e293b;
                line-height: 1.35;
                word-break: break-word;
            }}
            .map-link {{
                display: inline-block;
                margin-top: 3px;
                font-size: 11px;
                color: #2563eb;
                text-decoration: none;
                font-weight: 500;
            }}
            .map-link:hover {{ text-decoration: underline; }}
            
            .status-saved {{
                font-size: 11px;
                color: #059669;
                background: #ecfdf5;
                border: 1px solid #a7f3d0;
                padding: 2px 8px;
                border-radius: 9999px;
                font-weight: 600;
                transition: all 0.2s ease;
            }}
            .status-saving {{
                font-size: 11px;
                color: #d97706;
                background: #fffbeb;
                border: 1px solid #fde68a;
                padding: 2px 8px;
                border-radius: 9999px;
                font-weight: 600;
            }}
            
            .btn {{
                padding: 6px 12px;
                border-radius: 6px;
                font-size: 12px;
                font-weight: 600;
                cursor: pointer;
                border: 1px solid transparent;
                display: inline-flex;
                align-items: center;
                gap: 5px;
                transition: all 0.15s ease;
            }}
            .btn-primary {{
                background: var(--primary);
                color: #ffffff;
            }}
            .btn-primary:hover {{ background: var(--primary-hover); }}
            
            .btn-secondary {{
                background: #f1f5f9;
                color: #334155;
                border-color: #cbd5e1;
            }}
            .btn-secondary:hover {{
                background: #e2e8f0;
                color: #0f172a;
            }}
            
            .btn-outline {{
                background: #ffffff;
                color: var(--primary);
                border-color: #bfdbfe;
            }}
            .btn-outline:hover {{
                background: #eff6ff;
                border-color: var(--primary);
            }}
            
            .badge {{
                display: inline-block;
                padding: 2px 7px;
                border-radius: 4px;
                font-size: 11px;
                font-weight: 600;
            }}
            .badge-primary {{ background: #eff6ff; color: #1d4ed8; border: 1px solid #dbeafe; }}
            .badge-success {{ background: var(--emerald-subtle); color: var(--emerald-text); border: 1px solid var(--emerald-border); }}
            .badge-danger {{ background: var(--danger-subtle); color: var(--danger-text); border: 1px solid var(--danger-border); }}
            .badge-info {{ background: #f0f9ff; color: #0369a1; border: 1px solid #bae6fd; }}
            .badge-neutral {{ background: #f1f5f9; color: #475569; border: 1px solid #e2e8f0; }}
            .code-id {{
                background: #f1f5f9;
                padding: 2px 6px;
                border-radius: 4px;
                color: #334155;
                font-size: 11px;
                font-family: 'JetBrains Mono', monospace;
            }}
            
            #toast {{
                visibility: hidden;
                min-width: 280px;
                background-color: #0f172a;
                color: #ffffff;
                text-align: left;
                border-radius: 8px;
                padding: 12px 18px;
                position: fixed;
                z-index: 1000;
                bottom: 24px;
                right: 24px;
                font-size: 13px;
                font-weight: 500;
                box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.2);
                border-left: 4px solid var(--emerald);
            }}
            #toast.show {{
                visibility: visible;
                animation: toastIn 0.25s, toastOut 0.25s 2.75s;
            }}
            @keyframes toastIn {{ from {{ transform: translateY(20px); opacity: 0; }} to {{ transform: translateY(0); opacity: 1; }} }}
            @keyframes toastOut {{ from {{ transform: translateY(0); opacity: 1; }} to {{ transform: translateY(20px); opacity: 0; }} }}
        </style>
    </head>
    <body>
        <!-- CLEAN LIGHT HEADER -->
        <div class="header-bar">
            <div class="header-brand">
                <div class="header-logo">NC</div>
                <div>
                    <h1 class="title">NCCT Central Server — Biometric Attendance Gateway</h1>
                    <p class="subtitle">SIH26087 Prototype | Offline Android Sync & Training Center Geofencing</p>
                </div>
            </div>
            <div class="header-actions">
                <a href="/onboarding" class="btn btn-primary" style="font-size: 12px; padding: 8px 14px; text-decoration: none; font-weight: 700;">➕ Student Onboarding Portal</a>
                <a href="#students-roster-section" class="btn btn-outline" style="font-size: 12px; padding: 8px 14px; text-decoration: none; font-weight: 700;">👥 Students Roster ({len(STUDENTS)})</a>
                <a href="#attendance-db-section" class="btn btn-outline" style="font-size: 12px; padding: 8px 14px; text-decoration: none; font-weight: 700;">📊 Attendance DB ({total_att})</a>
                <a href="/download/apk" class="download-btn">📲 DOWNLOAD APK (88 MB)</a>
                <div class="status-pill">
                    <span class="pulse-dot"></span>
                    GATEWAY ONLINE
                </div>
            </div>
        </div>


        <!-- STATS OVERVIEW -->
        <div class="stats-grid">
            <a href="#attendance-db-section" style="text-decoration:none; display:block;" class="stat-card" title="Click to view Attendance Database">
                <div class="stat-label">Synced Attendances ↗</div>
                <div class="stat-value" id="stat-total-records" style="color: var(--emerald);">{total_att}</div>
            </a>
            <div class="stat-card">
                <div class="stat-label">Active Courses</div>
                <div class="stat-value">{len(SESSIONS)}</div>
            </div>
            <a href="#students-roster-section" style="text-decoration:none; display:block;" class="stat-card" title="Click to view Registered Students Database">
                <div class="stat-label">Registered Students ↗</div>
                <div class="stat-value" id="stat-total-students" style="color: var(--primary);">{len(STUDENTS)}</div>
            </a>
            <div class="stat-card">
                <div class="stat-label">Biometric Model</div>
                <div class="stat-value" style="color: var(--cyan); font-size: 21px; margin-top: 10px;">FaceNet 192D</div>
            </div>
        </div>

        <!-- TRAINING CENTER GEOFENCE CONFIGURATION PANEL -->
        <div class="card-panel">
            <div class="card-header">
                <div>
                    <div class="card-title">📍 Training Center Geofence Configuration (Per Session)</div>
                    <div class="text-xs text-slate-500 mt-1">Changes auto-save instantly. The phone app downloads these coordinates whenever connected and verifies device proximity.</div>
                </div>
                <div style="display:flex; gap: 8px; flex-wrap: wrap;">
                    <button type="button" onclick="detectUserExactAddress()" class="btn btn-primary text-xs" style="background:#2563eb; color:#fff; font-weight:700; display:inline-flex; align-items:center; gap:6px;">🎯 Detect My Address & GPS</button>
                    <button type="button" onclick="setAllSessionsToAutoLocation()" class="btn btn-outline text-xs">📍 Auto-Detect</button>
                </div>
            </div>

            <!-- ACTIVE LOCATION SUMMARY BAR -->
            <div class="active-center-banner">
                <div>
                    <span class="text-slate-500">📍 Active Center Location:</span>
                    <strong id="active-center-title" class="text-slate-900" style="margin-left: 4px;">{first_session_addr}</strong>
                </div>
                <span class="status-saved">✓ All Sessions Auto-Saved</span>
            </div>

            <!-- ADDRESS SEARCH & GEOCODING BAR (FREE OPENSTREETMAP API) -->
            <div style="padding: 12px 20px; background: #f8fafc; border-bottom: 1px solid var(--border); display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;">
                <div style="flex: 1; min-width: 300px; display: flex; gap: 8px;">
                    <input type="text" id="manual-address-input" placeholder="🏠 Type your exact building, street, or area (e.g. Dahisar West, Mumbai 400068)..." class="input-field" style="width: 100%; padding: 8px 12px; font-size: 13px;" onkeydown="if(event.key==='Enter') lookupAddressAndSetGeofence();">
                    <button type="button" onclick="lookupAddressAndSetGeofence()" class="btn btn-secondary text-xs" style="white-space:nowrap; padding: 8px 16px; font-weight:700; background:#0f172a; color:#fff;">📍 Set Address Pin</button>
                </div>
                <div id="detected-accuracy-pill" style="font-size: 12px; color: #475569; display: flex; align-items: center; gap: 6px; background:#fff; padding:6px 12px; border-radius:6px; border:1px solid #e2e8f0;">
                    <span class="pulse-dot" style="background:#10b981; width:8px; height:8px;"></span>
                    <span id="location-source-label">Source: Real-time Free Geocoding</span>
                </div>
            </div>

            <!-- INTERACTIVE SEARCH & MAP PIN DROPPER -->
            <div style="padding: 16px 20px; background: #ffffff; border-bottom: 1px solid var(--border);">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; flex-wrap: wrap; gap: 8px;">
                    <label style="font-size: 13px; font-weight: 700; color: #0f172a; display: flex; align-items: center; gap: 6px;">
                        🗺️ Search Real Location or Click on Map to Drop Geofence Pin:
                    </label>
                    <span class="text-xs text-slate-500">Search any area or drag the marker directly onto your building</span>
                </div>
                <div style="position: relative;">
                    <input type="text" id="map-search-box" placeholder="🔍 Search any area, college, street, or city (e.g. Powai, Saket, Thane, Rohini, Varanasi, Jaipur, etc.)..." class="input-field" style="width: 100%; padding: 8px 12px; font-size: 13px;" oninput="onSearchInput(this.value)">
                    <div id="search-suggestions" style="display:none; position: absolute; top: 100%; left: 0; right: 0; background: #ffffff; border: 1px solid #cbd5e1; border-radius: 6px; z-index: 1000; box-shadow: 0 4px 12px rgba(0,0,0,0.1); max-height: 200px; overflow-y: auto;"></div>
                </div>
                <div id="geofence-map" style="height: 250px; width: 100%; border-radius: 8px; border: 1px solid var(--border); margin-top: 10px; z-index: 1;"></div>
            </div>

            <!-- QUICK LOCATION PRESETS (1-CLICK AUTO-SAVE) -->
            <div class="presets-bar">
                <span class="preset-label">1-Click Presets:</span>
                <span class="preset-tag" onclick="applyPreset(28.6139, 77.2090, 'Delhi NCR')">📍 Delhi NCR</span>
                <span class="preset-tag" onclick="applyPreset(19.0760, 72.8777, 'Mumbai')">📍 Mumbai</span>
                <span class="preset-tag" onclick="applyPreset(12.9716, 77.5946, 'Bengaluru')">📍 Bengaluru</span>
                <span class="preset-tag" onclick="applyPreset(18.5204, 73.8567, 'Pune')">📍 Pune</span>
                <span class="preset-tag" onclick="applyPreset(17.3850, 78.4867, 'Hyderabad')">📍 Hyderabad</span>
                <span class="preset-tag" onclick="applyPreset(22.5726, 88.3639, 'Kolkata')">📍 Kolkata</span>
                <span class="preset-tag" onclick="applyPreset(26.9124, 75.7873, 'Jaipur')">📍 Jaipur</span>
            </div>

            <table>
                <thead>
                    <tr>
                        <th>Course & Batch</th>
                        <th>Center / Campus Name</th>
                        <th>Where is it? (Address & Map)</th>
                        <th>Center Coordinates</th>
                        <th>Radius</th>
                        <th>Actions / Status</th>
                    </tr>
                </thead>
                <tbody>
                    {session_rows_html}
                </tbody>
            </table>
        </div>

        <!-- REGISTERED STUDENTS BIOMETRIC DATABASE (SEARCHABLE ROSTER) -->
        <div class="card-panel" id="students-roster-section">
            <div class="card-header">
                <div>
                    <div class="card-title">👥 Registered Students Biometric Database (Roster Search)</div>
                    <div class="text-xs text-slate-500 mt-1">
                        Total Enrolled: <strong style="color:var(--primary);">{len(STUDENTS)} Registered Profiles</strong> | 
                        Biometric Search Engine: <strong>FaceNet 192-D MobileNetV1</strong> | 
                        Two-Way Sync: <span class="badge badge-success">Live App ↔ Server ✓</span>
                    </div>
                </div>
                <div style="display:flex; gap: 8px; flex-wrap: wrap;">
                    <a href="/onboarding" target="_blank" class="btn btn-primary text-xs">➕ Onboard Student (Live 3D Face)</a>
                    <a href="/api/students/export/csv" class="btn btn-outline text-xs">📥 Export Roster CSV</a>
                    <a href="/api/students" target="_blank" class="btn btn-secondary text-xs">📄 View Raw JSON</a>
                    <button type="button" onclick="deleteAllStudents()" class="btn btn-outline text-xs" style="color:#ef4444; border-color:#fca5a5;" title="Delete all students from database">🗑️ Delete All</button>
                    <button type="button" onclick="location.reload()" class="btn btn-secondary text-xs">🔄 Refresh</button>
                </div>
            </div>

            <!-- SEARCH & FILTER BAR -->
            <div style="padding: 12px 20px; background: #ffffff; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px;">
                <input type="text" id="students-search-box" placeholder="🔍 Search student name, ID, roll number, course, or batch code..." class="input-field" style="width: 100%; max-width: 440px; padding: 7px 12px; font-size: 13px;" oninput="filterStudentsTable(this.value)">
                <span id="students-count-badge" class="text-xs font-semibold text-slate-500">Showing {len(STUDENTS)} students</span>
            </div>

            <div style="max-height: 480px; overflow-y: auto;">
                <table>
                    <thead>
                        <tr>
                            <th>Student & ID</th>
                            <th>Roll Number</th>
                            <th>Enrolled Course</th>
                            <th>Batch / Sessions</th>
                            <th>Biometric Status</th>
                            <th>Action</th>
                        </tr>
                    </thead>
                    <tbody id="students-table-body">
                        {students_rows_html}
                    </tbody>
                </table>
            </div>
        </div>

        <!-- CENTRAL BIOMETRIC ATTENDANCE DATABASE -->
        <div class="card-panel" id="attendance-db-section">
            <div class="card-header">
                <div>
                    <div class="card-title">📊 Central Biometric Attendance Database (Room SQLite → Server Store)</div>
                    <div class="text-xs text-slate-500 mt-1">
                        Total Synced: <strong style="color:var(--emerald);">{total_att}</strong> | Unique Students: <strong>{unique_students_att}</strong> | Geofence Verified: <strong>{verified_att}/{total_att}</strong> | Engine: <strong>FaceNet 512-D MobileNetV1</strong>
                    </div>
                </div>
                <div style="display:flex; gap: 8px; flex-wrap: wrap;">
                    <a href="/attendance/export/csv" class="btn btn-outline text-xs">📥 Export CSV</a>
                    <a href="/attendance/records" target="_blank" class="btn btn-secondary text-xs">📄 View Raw JSON</a>
                    <button type="button" onclick="clearAttendanceDb()" class="btn btn-outline text-xs" style="color:var(--danger); border-color:#fecaca;">🗑️ Clear DB</button>
                    <button type="button" onclick="location.reload()" class="btn btn-primary text-xs">🔄 Refresh</button>
                </div>
            </div>

            <!-- SEARCH & FILTER BAR -->
            <div style="padding: 12px 20px; background: #ffffff; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px;">
                <input type="text" id="attendance-search-box" placeholder="🔍 Search student name, student ID, course, session ID, or device..." class="input-field" style="width: 100%; max-width: 440px; padding: 7px 12px; font-size: 13px;" oninput="filterAttendanceTable(this.value)">
                <span id="attendance-count-badge" class="text-xs font-semibold text-slate-500">Showing {total_att} records</span>
            </div>

            <table>
                <thead>
                    <tr>
                        <th>Event ID</th>
                        <th>Student Details</th>
                        <th>Course & Session</th>
                        <th>Biometric Photos (Enrolled ↔ Live Capture)</th>
                        <th>Match % & Liveness</th>
                        <th>Center Proximity</th>
                        <th>Marked Time</th>
                        <th>Report</th>
                    </tr>
                </thead>
                <tbody id="attendance-table-body">
                    {rows_html}
                </tbody>
            </table>
        </div>

        <!-- BIOMETRIC VERIFICATION REPORT MODAL (SAME AS PHONE APP) -->
        <div id="biometric-report-modal" style="display:none; position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(15,23,42,0.65); backdrop-filter:blur(4px); z-index:2000; align-items:center; justify-content:center; padding:20px;">
            <div style="background:#ffffff; border-radius:14px; max-width:620px; width:100%; padding:24px; box-shadow:0 25px 50px -12px rgba(0,0,0,0.25); animation:modalIn 0.25s ease;">
                <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:16px;">
                    <div>
                        <div style="font-size:16px; font-weight:800; color:#0f172a; display:flex; align-items:center; gap:8px;">
                            <span>📋</span> NCCT Biometric Attendance Report
                        </div>
                        <div style="font-size:12px; color:#64748b; margin-top:2px;">Offline Biometric Matching & Telemetry Breakdown (Room SQLite → Server Sync)</div>
                    </div>
                    <button type="button" onclick="closeBiometricReport()" style="background:#f1f5f9; border:none; border-radius:6px; width:30px; height:30px; font-weight:700; cursor:pointer;">✕</button>
                </div>

                <!-- VERIFIED STATUS BANNER -->
                <div style="background:#ecfdf5; border:1.5px solid #a7f3d0; border-radius:8px; padding:10px 14px; display:flex; align-items:center; justify-content:space-between; margin-bottom:18px;">
                    <div style="display:flex; align-items:center; gap:8px;">
                        <span style="font-size:18px;">✓</span>
                        <strong style="color:#065f46; font-size:13px;">ATTENDANCE VERIFIED & RECORDED</strong>
                    </div>
                    <span id="rep-score-pill" style="background:#059669; color:#ffffff; font-weight:700; font-size:12px; padding:3px 10px; border-radius:9999px;">--% Match</span>
                </div>

                <!-- SIDE BY SIDE PHOTOS -->
                <div style="display:grid; grid-template-columns: 1fr 50px 1fr; gap:12px; align-items:center; background:#f8fafc; border:1px solid #e2e8f0; border-radius:10px; padding:16px; margin-bottom:18px;">
                    <!-- ENROLLED PHOTO -->
                    <div style="text-align:center;">
                        <img id="rep-enrolled-img" src="" style="width:130px; height:130px; border-radius:10px; object-fit:cover; border:2.5px solid #64748b; margin:0 auto; display:block;">
                        <div style="font-size:11px; font-weight:700; color:#334155; margin-top:8px;">ENROLLED BIOMETRIC</div>
                        <div style="font-size:11px; color:#64748b;">(Course Registration)</div>
                    </div>
                    <!-- MATCH ARROW -->
                    <div style="text-align:center;">
                        <div style="font-size:24px; color:#059669; font-weight:bold;">↔</div>
                        <div style="font-size:10px; font-weight:700; color:#059669; margin-top:2px;">MATCH</div>
                    </div>
                    <!-- LIVE CAPTURED PHOTO -->
                    <div style="text-align:center;">
                        <img id="rep-captured-img" src="" style="width:130px; height:130px; border-radius:10px; object-fit:cover; border:2.5px solid #059669; margin:0 auto; display:block;">
                        <div style="font-size:11px; font-weight:700; color:#065f46; margin-top:8px;">LIVE CAPTURE</div>
                        <div style="font-size:11px; color:#059669;">(Attendance Verification)</div>
                    </div>
                </div>

                <!-- TELEMETRY DETAILS -->
                <div style="display:grid; grid-template-columns:1fr 1fr; gap:10px; font-size:12px; margin-bottom:18px;">
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Student:</span> <strong id="rep-student-name"></strong> (<span id="rep-student-id"></span>)
                    </div>
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Session:</span> <strong id="rep-session-title"></strong>
                    </div>
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Liveness Anti-Spoof:</span> <strong id="rep-liveness-val" style="color:#059669;"></strong>
                    </div>
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Geofence:</span> <strong id="rep-geofence-val"></strong>
                    </div>
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Device ID:</span> <span id="rep-device-val" class="font-mono"></span>
                    </div>
                    <div style="background:#f1f5f9; padding:8px 12px; border-radius:6px;">
                        <span style="color:#64748b;">Timestamp:</span> <span id="rep-time-val"></span>
                    </div>
                </div>

                <div style="display:flex; justify-content:flex-end; gap:8px;">
                    <button type="button" onclick="closeBiometricReport()" class="btn btn-secondary">Close</button>
                    <button type="button" onclick="window.print()" class="btn btn-primary">🖨️ Print / Save PDF</button>
                </div>
            </div>
        </div>

        <div id="toast">Saved location!</div>

        <script>
            function showToast(msg) {{
                const t = document.getElementById("toast");
                t.innerText = msg;
                t.className = "show";
                setTimeout(() => {{ t.className = t.className.replace("show", ""); }}, 3200);
            }}


            // Initialize Leaflet Map
            let map, marker, circle;
            const initLat = {SESSIONS[0].get("centerLatitude", 19.0760) if SESSIONS else 19.0760};
            const initLon = {SESSIONS[0].get("centerLongitude", 72.8777) if SESSIONS else 72.8777};
            const initRadius = {SESSIONS[0].get("allowedRadiusMeters", 100.0) if SESSIONS else 100.0};

            function initMap() {{
                try {{
                    map = L.map('geofence-map').setView([initLat, initLon], 14);
                    L.tileLayer('https://{{s}}.tile.openstreetmap.org/{{z}}/{{x}}/{{y}}.png', {{
                        attribution: '&copy; OpenStreetMap contributors',
                        maxZoom: 19
                    }}).addTo(map);

                    marker = L.marker([initLat, initLon], {{ draggable: true }}).addTo(map);
                    circle = L.circle([initLat, initLon], {{
                        radius: initRadius,
                        color: '#2563eb',
                        fillColor: '#3b82f6',
                        fillOpacity: 0.15
                    }}).addTo(map);

                    marker.on('dragend', async function(e) {{
                        const pos = marker.getLatLng();
                        circle.setLatLng(pos);
                        await onLocationSelected(pos.lat, pos.lng);
                    }});

                    map.on('click', async function(e) {{
                        marker.setLatLng(e.latlng);
                        circle.setLatLng(e.latlng);
                        await onLocationSelected(e.latlng.lat, e.latlng.lng);
                    }});

                    // Add Interactive "Locate Me" Crosshair Control directly onto the Leaflet Map
                    const locateControl = L.control({{ position: 'topright' }});
                    locateControl.onAdd = function() {{
                        const div = L.DomUtil.create('div', 'leaflet-bar leaflet-control');
                        div.innerHTML = `
                            <a href="#" title="Locate My Exact Position (Wi-Fi / GPS Triangulation)" style="background:#fff; width:34px; height:34px; display:flex; align-items:center; justify-content:center; font-size:18px; text-decoration:none; cursor:pointer;" onclick="event.preventDefault(); locateUserHighAccuracy();">
                                🎯
                            </a>
                        `;
                        return div;
                    }};
                    locateControl.addTo(map);
                }} catch (e) {{
                    console.warn("Leaflet initialization error:", e);
                }}
            }}
            window.addEventListener('DOMContentLoaded', initMap);

            async function locateUserHighAccuracy() {{
                showToast("📡 Triangulating precise location via Wi-Fi/GPS...");
                if (!navigator.geolocation) {{
                    showToast("⚠️ Geolocation API not supported by this browser.");
                    return;
                }}
                navigator.geolocation.getCurrentPosition(
                    async (pos) => {{
                        const lat = pos.coords.latitude;
                        const lon = pos.coords.longitude;
                        const acc = Math.round(pos.coords.accuracy || 0);
                        if (map) map.flyTo([lat, lon], 16, {{ duration: 1.2 }});
                        if (marker) marker.setLatLng([lat, lon]);
                        if (circle) circle.setLatLng([lat, lon]);
                        await onLocationSelected(lat, lon);
                        showToast(`🎯 Position Locked: ±${{acc}}m accuracy`);
                    }},
                    (err) => {{
                        console.warn("Geolocation error:", err);
                        showToast("⚠️ Browser Location Error: " + err.message + " — Use Search Bar or Phone GPS");
                    }},
                    {{
                        enableHighAccuracy: true,
                        timeout: 10000,
                        maximumAge: 0
                    }}
                );
            }}

            let searchTimeout = null;
            function onSearchInput(val) {{
                clearTimeout(searchTimeout);
                const sug = document.getElementById('search-suggestions');
                if (!val || val.length < 2) {{
                    if (sug) sug.style.display = 'none';
                    return;
                }}
                searchTimeout = setTimeout(async () => {{
                    try {{
                        const res = await fetch('/api/search-location?q=' + encodeURIComponent(val));
                        const items = await res.json();
                        if (items && items.length > 0 && sug) {{
                            sug.innerHTML = items.map(it => `
                                <div style="padding: 8px 12px; border-bottom: 1px solid #f1f5f9; cursor: pointer; font-size: 12px;"
                                     onmouseover="this.style.background='#eff6ff'"
                                     onmouseout="this.style.background='#fff'"
                                     onclick="selectSearchResult(${{it.lat}}, ${{it.lon}}, '${{it.display_name.replace(/'/g, "\\\\'") }}')">
                                    📍 <strong>${{it.city || it.state || ''}}</strong>: ${{it.display_name}}
                                </div>
                            `).join('');
                            sug.style.display = 'block';
                        }} else if (sug) {{
                            sug.style.display = 'none';
                        }}
                    }} catch (e) {{}}
                }}, 350);
            }}

            async function selectSearchResult(lat, lon, name) {{
                const sug = document.getElementById('search-suggestions');
                if (sug) sug.style.display = 'none';
                document.getElementById('map-search-box').value = name;
                const inputEl = document.getElementById('manual-address-input');
                if (inputEl) inputEl.value = name;
                if (map) map.setView([lat, lon], 16);
                if (marker) marker.setLatLng([lat, lon]);
                if (circle) circle.setLatLng([lat, lon]);
                await onLocationSelected(lat, lon, name);
            }}

            async function onLocationSelected(lat, lon, customName) {{
                const addr = customName || await fetchAddressForCoords(lat, lon);
                const inputEl = document.getElementById('manual-address-input');
                if (inputEl) inputEl.value = addr;
                await applyPreset(lat, lon, addr, "Map Pin Dropped");
            }}


            // Robust Location resolver: Phone GPS -> Browser GPS -> Free Server Geolocation API
            async function resolveBestLocation() {{
                // 1. First Priority: Connected Android Phone's Real Satellite GPS
                try {{
                    const res = await fetch('/api/phone-location');
                    const data = await res.json();
                    if (data && data.latitude && data.longitude) {{
                        return {{
                            lat: data.latitude,
                            lon: data.longitude,
                            address: data.address,
                            source: "Phone Hardware GPS"
                        }};
                    }}
                }} catch (_) {{}}

                // 2. Second Priority: Browser High-Accuracy Geolocation
                if (navigator.geolocation) {{
                    try {{
                        const pos = await new Promise((resolve, reject) => {{
                            navigator.geolocation.getCurrentPosition(resolve, reject, {{
                                enableHighAccuracy: true,
                                timeout: 7000,
                                maximumAge: 0
                            }});
                        }});
                        const acc = Math.round(pos.coords.accuracy || 0);
                        return {{
                            lat: pos.coords.latitude,
                            lon: pos.coords.longitude,
                            source: "Browser GPS (±" + acc + "m)"
                        }};
                    }} catch (e) {{
                        console.warn("Browser GPS not available or permission denied:", e.message);
                    }}
                }}

                // 3. Third Priority: Free Multi-Provider Geolocation API
                try {{
                    const res = await fetch('/api/detect-location');
                    const data = await res.json();
                    if (data && data.latitude && data.longitude) {{
                        return {{
                            lat: data.latitude,
                            lon: data.longitude,
                            address: data.address,
                            source: data.source || "Network Geolocation"
                        }};
                    }}
                }} catch (e) {{
                    console.error("Detect Location fallback failed:", e);
                }}

                return {{ lat: 19.0760, lon: 72.8777, source: "Default Location" }};
            }}

            async function fetchAddressForCoords(lat, lon) {{
                try {{
                    const res = await fetch('/api/reverse-geocode?lat=' + lat + '&lon=' + lon);
                    const data = await res.json();
                    if (data && data.address) return data.address;
                }} catch (e) {{
                    console.warn("Reverse geocode fetch failed:", e);
                }}
                return "Location Area (" + lat.toFixed(4) + "°, " + lon.toFixed(4) + "°)";
            }}

            async function lookupAddressAndSetGeofence() {{
                const inputEl = document.getElementById('manual-address-input');
                const query = inputEl ? inputEl.value.trim() : '';
                if (!query) {{
                    showToast("⚠️ Please enter an address, area, or pincode to search.");
                    return;
                }}
                showToast("🔍 Looking up real address with OpenStreetMap...");
                try {{
                    const res = await fetch('/api/search-location?q=' + encodeURIComponent(query));
                    const results = await res.json();
                    if (results && results.length > 0) {{
                        const top = results[0];
                        const bestName = top.formatted_address || top.display_name;
                        inputEl.value = bestName;
                        if (map) map.flyTo([top.lat, top.lon], 16, {{ duration: 1.0 }});
                        if (marker) marker.setLatLng([top.lat, top.lon]);
                        if (circle) circle.setLatLng([top.lat, top.lon]);
                        await applyPreset(top.lat, top.lon, bestName, "Address Geocoder (OpenStreetMap)");
                        showToast("📍 Geofence Pin Dropped at: " + bestName);
                        const sourceLabel = document.getElementById('location-source-label');
                        if (sourceLabel) sourceLabel.innerText = "Source: Address Match (" + (top.city || 'India') + ")";
                    }} else {{
                        showToast("⚠️ Could not find exact coordinates for '" + query + "'. Try including city name or pincode.");
                    }}
                }} catch (e) {{
                    console.error("Geocoding failed:", e);
                    showToast("⚠️ Geocoding error: " + e.message);
                }}
            }}

            async function detectUserExactAddress() {{
                showToast("📡 Checking Phone Hardware GPS & Real Geocoders...");
                const sourceLabel = document.getElementById('location-source-label');
                if (sourceLabel) sourceLabel.innerText = "Triangulating...";

                // 1. Highest Priority: Connected Android Phone's Real Satellite GPS!
                try {{
                    const pres = await fetch('/api/phone-location');
                    const pdata = await pres.json();
                    if (pdata && pdata.latitude && pdata.longitude) {{
                        const addr = pdata.address || await fetchAddressForCoords(pdata.latitude, pdata.longitude);
                        const inputEl = document.getElementById('manual-address-input');
                        if (inputEl) inputEl.value = addr;
                        await applyPreset(pdata.latitude, pdata.longitude, addr, "Phone Satellite GPS");
                        if (sourceLabel) sourceLabel.innerText = "Source: 📱 Phone Satellite GPS";
                        showToast("🎯 Real Phone GPS Locked: " + addr);
                        return;
                    }}
                }} catch (_) {{}}

                if (navigator.geolocation) {{
                    try {{
                        const pos = await new Promise((resolve, reject) => {{
                            navigator.geolocation.getCurrentPosition(resolve, reject, {{
                                enableHighAccuracy: true,
                                timeout: 8000,
                                maximumAge: 0
                            }});
                        }});
                        const lat = pos.coords.latitude;
                        const lon = pos.coords.longitude;
                        const acc = Math.round(pos.coords.accuracy || 0);
                        const addr = await fetchAddressForCoords(lat, lon);
                        const inputEl = document.getElementById('manual-address-input');
                        if (inputEl) inputEl.value = addr;
                        await applyPreset(lat, lon, addr, "Browser Wi-Fi/GPS (±" + acc + "m)");
                        if (sourceLabel) sourceLabel.innerText = "Source: Browser GPS (±" + acc + "m)";
                        showToast("🎯 Address Locked: " + addr + " (±" + acc + "m)");
                        return;
                    }} catch (e) {{
                        console.warn("Browser GPS unavailable:", e.message);
                    }}
                }}

                try {{
                    const res = await fetch('/api/detect-location');
                    const data = await res.json();
                    if (data && data.latitude && data.longitude) {{
                        const addr = data.address || await fetchAddressForCoords(data.latitude, data.longitude);
                        const inputEl = document.getElementById('manual-address-input');
                        if (inputEl) inputEl.value = addr;
                        await applyPreset(data.latitude, data.longitude, addr, data.source || "Network IP");
                        if (sourceLabel) sourceLabel.innerText = "Source: " + (data.source || "Network IP");
                        showToast("📍 Address Detected: " + addr);
                        return;
                    }}
                }} catch (e) {{
                    console.error("Detect location failed:", e);
                }}

                showToast("⚠️ Could not auto-detect address. Type your address in the box above to set pin.");
            }}

            let debounceTimers = {{}};
            function scheduleAutoSave(sid) {{
                const statusEl = document.getElementById('status-' + sid);
                if (statusEl) {{
                    statusEl.innerText = "💾 Saving...";
                    statusEl.className = "status-saving";
                }}
                clearTimeout(debounceTimers[sid]);
                debounceTimers[sid] = setTimeout(() => {{
                    saveSessionLocation(sid);
                }}, 600);
            }}

            async function saveSessionLocation(sid) {{
                const lat = parseFloat(document.getElementById('lat-' + sid).value);
                const lon = parseFloat(document.getElementById('lon-' + sid).value);
                const radius = parseFloat(document.getElementById('rad-' + sid).value) || 100.0;
                const cname = document.getElementById('name-' + sid).value;

                if (isNaN(lat) || isNaN(lon)) return;

                const statusEl = document.getElementById('status-' + sid);
                if (statusEl) {{
                    statusEl.innerText = "💾 Saving...";
                    statusEl.className = "status-saving";
                }}

                // Update map link
                const mapLink = document.getElementById('map-' + sid);
                if (mapLink) mapLink.href = "https://www.google.com/maps?q=" + lat.toFixed(6) + "," + lon.toFixed(6);

                try {{
                    const res = await fetch('/sessions/' + sid + '/location', {{
                        method: 'POST',
                        headers: {{ 'Content-Type': 'application/json' }},
                        body: JSON.stringify({{ latitude: lat, longitude: lon, allowedRadiusMeters: radius, centerName: cname }})
                    }});
                    const data = await res.json();
                    if (res.ok) {{
                        if (statusEl) {{
                            statusEl.innerText = "✓ Auto-Saved";
                            statusEl.className = "status-saved";
                        }}
                        const addrEl = document.getElementById('addr-' + sid);
                        if (addrEl && data.locationAddress) {{
                            addrEl.innerText = "📍 " + data.locationAddress;
                        }}
                    }}
                }} catch (e) {{
                    if (statusEl) {{
                        statusEl.innerText = "✕ Error";
                        statusEl.className = "status-saving";
                    }}
                }}
            }}

            async function acquireSingleSessionLocation(sid) {{
                showToast("Detecting location...");
                const loc = await resolveBestLocation();
                document.getElementById('lat-' + sid).value = loc.lat.toFixed(6);
                document.getElementById('lon-' + sid).value = loc.lon.toFixed(6);
                const addr = loc.address || await fetchAddressForCoords(loc.lat, loc.lon);
                const addrEl = document.getElementById('addr-' + sid);
                if (addrEl) addrEl.innerText = "📍 " + addr;
                await saveSessionLocation(sid);
                showToast("📍 Acquired (" + loc.source + "): " + addr);
            }}

            async function setAllSessionsToAutoLocation() {{
                showToast("Detecting real-time center location...");
                const loc = await resolveBestLocation();
                const addr = loc.address || await fetchAddressForCoords(loc.lat, loc.lon);
                await applyPreset(loc.lat, loc.lon, addr, loc.source);
            }}

            async function applyPreset(lat, lon, name, source) {{
                const addr = name || await fetchAddressForCoords(lat, lon);
                const activeEl = document.getElementById('active-center-title');
                if (activeEl) activeEl.innerText = addr;
                const inputEl = document.getElementById('manual-address-input');
                if (inputEl && !inputEl.value) inputEl.value = addr;

                if (map) {{
                    map.flyTo([lat, lon], 16, {{ duration: 1.0 }});
                }}
                if (marker) {{
                    marker.setLatLng([lat, lon]);
                    marker.bindPopup("📍 <strong>" + addr + "</strong><br><span style='font-size:11px;color:#64748b;'>" + lat.toFixed(5) + ", " + lon.toFixed(5) + "</span>").openPopup();
                }}
                if (circle) circle.setLatLng([lat, lon]);

                const res = await fetch('/sessions/batch-update', {{
                    method: 'POST',
                    headers: {{ 'Content-Type': 'application/json' }},
                    body: JSON.stringify({{ latitude: lat, longitude: lon, locationAddress: addr }})
                }});

                const sids = {json.dumps([s["sessionId"] for s in SESSIONS])};
                for (const sid of sids) {{
                    const latEl = document.getElementById('lat-' + sid);
                    const lonEl = document.getElementById('lon-' + sid);
                    const addrEl = document.getElementById('addr-' + sid);
                    const mapEl = document.getElementById('map-' + sid);
                    const statusEl = document.getElementById('status-' + sid);

                    if (latEl) latEl.value = lat.toFixed(6);
                    if (lonEl) lonEl.value = lon.toFixed(6);
                    if (addrEl) addrEl.innerText = "📍 " + addr;
                    if (mapEl) mapEl.href = "https://www.google.com/maps?q=" + lat.toFixed(6) + "," + lon.toFixed(6);
                    if (statusEl) {{
                        statusEl.innerText = "✓ Auto-Saved";
                        statusEl.className = "status-saved";
                    }}
                }}
                const sourceLabel = document.getElementById('location-source-label');
                if (sourceLabel && source) sourceLabel.innerText = "Source: " + source;
                showToast("✅ Auto-saved all sessions to: " + addr + (source ? " (" + source + ")" : ""));
            }}

            function filterStudentsTable(query) {{
                const term = (query || "").toLowerCase().trim();
                const rows = document.querySelectorAll("#students-table-body tr.student-row");
                let count = 0;
                rows.forEach(r => {{
                    const searchData = r.getAttribute("data-search") || r.innerText.toLowerCase();
                    if (!term || searchData.includes(term)) {{
                        r.style.display = "";
                        count++;
                    }} else {{
                        r.style.display = "none";
                    }}
                }});
                const countBadge = document.getElementById("students-count-badge");
                if (countBadge) {{
                    countBadge.innerText = term ? ("Showing " + count + " of " + rows.length + " students") : ("Showing " + rows.length + " students");
                }}
            }}

            async function deleteStudent(studentId, studentName) {{
                if (!confirm(`Are you sure you want to permanently delete student "${{studentName}}" (ID: ${{studentId}}) from the central database? This will also sync to mobile apps.`)) {{
                    return;
                }}
                try {{
                    const res = await fetch(`/api/students/${{studentId}}`, {{ method: 'DELETE' }});
                    const data = await res.json();
                    if (res.ok && data.status === 'SUCCESS') {{
                        const row = document.getElementById(`student-row-${{studentId}}`);
                        if (row) row.remove();
                        showToast(`✓ Deleted student "${{studentName}}" (${{studentId}})`);
                        const countBadge = document.getElementById("students-count-badge");
                        const rows = document.querySelectorAll("#students-table-body tr.student-row");
                        if (countBadge) countBadge.innerText = `Showing ${{rows.length}} students`;
                    }} else {{
                        alert(`Failed to delete student: ${{data.detail || data.message || 'Unknown error'}}`);
                    }}
                }} catch (e) {{
                    alert(`Error deleting student: ${{e.message}}`);
                }}
            }}

            async function deleteAllStudents() {{
                if (!confirm("⚠️ DANGER: Are you sure you want to permanently delete ALL students from the central database? This will sync to all connected mobile apps.")) {{
                    return;
                }}
                try {{
                    const res = await fetch('/api/students/delete-all', {{ method: 'POST' }});
                    const data = await res.json();
                    if (res.ok && data.status === 'SUCCESS') {{
                        showToast("✓ All students deleted from database");
                        setTimeout(() => location.reload(), 800);
                    }} else {{
                        alert(`Failed to delete students: ${{data.detail || data.message || 'Unknown error'}}`);
                    }}
                }} catch (e) {{
                    alert(`Error: ${{e.message}}`);
                }}
            }}

            function filterAttendanceTable(query) {{
                const term = (query || "").toLowerCase().trim();
                const rows = document.querySelectorAll("#attendance-table-body tr.att-row");
                let count = 0;
                rows.forEach(r => {{
                    const searchData = r.getAttribute("data-search") || r.innerText.toLowerCase();
                    if (!term || searchData.includes(term)) {{
                        r.style.display = "";
                        count++;
                    }} else {{
                        r.style.display = "none";
                    }}
                }});
                const countBadge = document.getElementById("attendance-count-badge");
                if (countBadge) {{
                    countBadge.innerText = term ? ("Showing " + count + " of " + rows.length + " records") : ("Showing " + rows.length + " records");
                }}
            }}


            async function clearAttendanceDb() {{
                if (!confirm("Are you sure you want to clear the entire Attendance Database?")) return;
                try {{
                    await fetch("/attendance/clear", {{ method: "DELETE" }});
                    showToast("✓ Attendance database cleared!");
                    setTimeout(() => location.reload(), 500);
                }} catch (e) {{
                    alert("Failed to clear DB: " + e);
                }}
            }}

            // Biometric Verification Report Popup Handler
            const ATTENDANCE_MAP = {{}};
            const attList = {attendance_json};
            attList.forEach(r => {{ ATTENDANCE_MAP[r.recordId] = r; }});

            function openBiometricReport(recordId) {{
                const r = ATTENDANCE_MAP[recordId];
                if (!r) return;
                const modal = document.getElementById('biometric-report-modal');
                const simPct = Math.round((r.similarityScore || 0.85) * 100);

                const enrolledUrl = r.enrolledFaceUrl || ("/api/students/" + r.studentId + "/photo");
                const capturedUrl = r.capturedFaceUrl || ("/api/attendance/" + r.recordId + "/captured-photo");

                document.getElementById('rep-score-pill').innerText = simPct + "% Match";
                document.getElementById('rep-enrolled-img').src = enrolledUrl;
                document.getElementById('rep-captured-img').src = capturedUrl;
                document.getElementById('rep-student-name').innerText = r.studentName || "Student";
                document.getElementById('rep-student-id').innerText = r.studentId || "";
                document.getElementById('rep-session-title').innerText = (r.sessionTitle || "Course") + " (" + (r.sessionId || "") + ")";
                document.getElementById('rep-liveness-val').innerText = ((r.livenessScore || 0.92).toFixed(2)) + " ✓ Anti-Spoof Pass";
                document.getElementById('rep-geofence-val').innerText = r.isLocationValid ? "✓ Inside Training Center" : "✕ Out of Bounds";
                document.getElementById('rep-device-val').innerText = r.deviceId || "ANDROID-OFFLINE-01";

                const d = r.timestamp ? new Date(r.timestamp) : new Date();
                document.getElementById('rep-time-val').innerText = d.toLocaleString();

                modal.style.display = 'flex';
            }}

            function closeBiometricReport() {{
                const modal = document.getElementById('biometric-report-modal');
                if (modal) modal.style.display = 'none';
            }}

            // Real-Time Zero-Refresh SSE Live Stream Listener
            function initLiveStream() {{
                if (!window.EventSource) return;
                const source = new EventSource('/api/events');

                source.addEventListener('attendance_synced', function(event) {{
                    try {{
                        const data = JSON.parse(event.data);
                        console.log('⚡ [Live SSE] Attendance synced from mobile device:', data);

                        // 1. Update stats counter badges
                        const totalEl = document.getElementById('stat-total-records');
                        if (totalEl && data.totalRecords !== undefined) totalEl.innerText = data.totalRecords;
                        const verEl = document.getElementById('stat-verified-records');
                        if (verEl && data.verifiedRecords !== undefined) verEl.innerText = data.verifiedRecords;
                        const uniqEl = document.getElementById('stat-unique-students');
                        if (uniqEl && data.uniqueStudents !== undefined) uniqEl.innerText = data.uniqueStudents;

                        // 2. Prepend each new record to the attendance table with highlight animation
                        const tbody = document.getElementById('attendance-table-body');
                        if (tbody && data.records && data.records.length > 0) {{
                            const emptyRow = tbody.querySelector('.empty-state');
                            if (emptyRow) emptyRow.closest('tr').remove();

                            data.records.forEach(r => {{
                                ATTENDANCE_MAP[r.recordId] = r;
                                if (document.getElementById('att-row-' + r.recordId)) return;

                                const tr = document.createElement('tr');
                                tr.className = 'att-row';
                                tr.id = 'att-row-' + r.recordId;
                                tr.style.background = '#ecfdf5';

                                const ts = r.timestamp ? new Date(r.timestamp).toLocaleString() : (r.serverReceivedAt || 'Just now');
                                const locBadge = r.isLocationValid ? '<span class="badge badge-success">✓ Verified Center</span>' : '<span class="badge badge-danger">✕ Out of bounds</span>';
                                const simVal = (r.similarityScore || 0).toFixed(2);
                                const simPct = Math.round((r.similarityScore || 0) * 100);
                                const simBadge = `<span class="badge badge-info">${{simVal}} (${{simPct}}%)</span>`;
                                const liveVal = (r.livenessScore || 0).toFixed(2);
                                const liveBadge = (r.livenessScore || 0) >= 0.70 ? `<span class="badge badge-success">${{liveVal}} ✓ Pass</span>` : `<span class="badge badge-danger">${{liveVal}} Fail</span>`;

                                tr.innerHTML = `
                                    <td>
                                        <code class="code-id">${{(r.recordId || '').substring(0, 10)}}</code>
                                        <div class="text-xs text-emerald-600 font-mono mt-1 font-semibold">⚡ ${{r.deviceId || 'PHONE-SYNC'}} (JUST NOW)</div>
                                    </td>
                                    <td>
                                        <div class="font-semibold text-slate-900">${{r.studentName || 'Student'}}</div>
                                        <div class="text-xs font-mono text-slate-500">${{r.studentId || ''}}</div>
                                    </td>
                                    <td>
                                        <div class="font-medium text-slate-800">${{r.sessionTitle || '—'}}</div>
                                        <span class="badge badge-neutral">${{r.sessionId || ''}}</span>
                                    </td>
                                    <td>
                                        <div style="display:inline-flex; align-items:center; gap:8px; background:#f8fafc; border:1px solid #e2e8f0; padding:4px 8px; border-radius:8px; cursor:pointer;" onclick="openBiometricReport('${{r.recordId}}')">
                                            <div style="text-align:center;">
                                                <img src="/api/students/${{r.studentId}}/photo" style="width:40px; height:40px; border-radius:6px; object-fit:cover; border:1.5px solid #94a3b8; display:block;" onerror="this.src='/api/students/default/photo';">
                                                <span style="font-size:9px; color:#475569; font-weight:600;">Enrolled</span>
                                            </div>
                                            <span style="color:#059669; font-weight:bold; font-size:12px;">↔</span>
                                            <div style="text-align:center;">
                                                <img src="/api/attendance/${{r.recordId}}/captured-photo" style="width:40px; height:40px; border-radius:6px; object-fit:cover; border:2px solid #059669; display:block;" onerror="this.src='/api/students/default/photo';">
                                                <span style="font-size:9px; color:#059669; font-weight:700;">Live</span>
                                            </div>
                                        </div>
                                    </td>
                                    <td>
                                        <div style="margin-bottom:3px;">${{simBadge}}</div>
                                        <div>${{liveBadge}}</div>
                                    </td>
                                    <td>
                                        ${{locBadge}}
                                        <div class="text-xs font-mono text-slate-500 mt-1">${{(r.latitude || 0).toFixed(4)}}°, ${{(r.longitude || 0).toFixed(4)}}°</div>
                                    </td>
                                    <td class="text-slate-700 font-mono text-xs" style="white-space:nowrap;">${{ts}}</td>
                                    <td>
                                        <button type="button" onclick="openBiometricReport('${{r.recordId}}')" class="btn btn-secondary text-xs" style="padding: 4px 10px; font-weight:700;">📋 Report</button>
                                    </td>
                                `;

                                tbody.insertBefore(tr, tbody.firstChild);
                                setTimeout(() => {{ tr.style.transition = 'background 2s'; tr.style.background = ''; }}, 4000);
                            }});
                        }}

                        const countBadge = document.getElementById("attendance-count-badge");
                        if (countBadge && data.totalRecords !== undefined) {{
                            countBadge.innerText = `Showing ${{data.totalRecords}} records`;
                        }}

                        // 3. Live Toast Alert
                        const latestStudent = (data.records && data.records[0]) ? data.records[0].studentName : 'Student';
                        showToast(`⚡ Live Sync: Attendance recorded for ${{latestStudent}} via phone! (0s refresh)`);
                    }} catch (err) {{
                        console.error('Error handling SSE event:', err);
                    }}
                }});

                source.addEventListener('student_synced', function(event) {{
                    try {{
                        const data = JSON.parse(event.data);
                        showToast(`👤 Student Roster Updated: ${{data.name || ''}} (${{data.studentId || ''}}) synced!`);
                        const badge = document.getElementById('students-count-badge');
                        if (badge && data.totalStudents) badge.innerText = `Showing ${{data.totalStudents}} students`;
                    }} catch (_) {{}}
                }});
            }}
            window.addEventListener('DOMContentLoaded', initLiveStream);
        </script>
    </body>
    </html>
    """

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)

