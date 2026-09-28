import os
import json
import csv
import io
import math
import base64
from typing import List, Optional
from datetime import datetime
from PIL import Image
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, FileResponse
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

def save_students_dataset():
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

def compute_face_embedding(img: Image.Image) -> list:
    """Extracts a 192-dimensional spatial harmonic normalized biometric embedding matching FaceEmbeddingEngine.kt."""
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
    cache_key = f"{lat:.4f},{lon:.4f}"
    if cache_key in GEO_CACHE:
        return GEO_CACHE[cache_key]

    # Fast offline checks for common presets
    if abs(lat - 28.6139) < 0.05 and abs(lon - 77.2090) < 0.05:
        addr = "Kartavya Path, Central Secretariat, New Delhi, Delhi 110004"
        GEO_CACHE[cache_key] = addr
        return addr
    if (abs(lat - 19.0760) < 0.05 and abs(lon - 72.8777) < 0.05) or (abs(lat - 19.0748) < 0.05 and abs(lon - 72.8856) < 0.05):
        addr = "Kurla West, Bandra Complex, Mumbai, Maharashtra 400070"
        GEO_CACHE[cache_key] = addr
        return addr
    if abs(lat - 12.9716) < 0.05 and abs(lon - 77.5946) < 0.05:
        addr = "MG Road, Sampangi Rama Nagar, Bengaluru, Karnataka 560001"
        GEO_CACHE[cache_key] = addr
        return addr
    if abs(lat - 18.5204) < 0.05 and abs(lon - 73.8567) < 0.05:
        addr = "Shivajinagar, Pune, Maharashtra 411005"
        GEO_CACHE[cache_key] = addr
        return addr
    if abs(lat - 17.3850) < 0.05 and abs(lon - 78.4867) < 0.05:
        addr = "Abids, Hyderabad, Telangana 500001"
        GEO_CACHE[cache_key] = addr
        return addr
    if abs(lat - 22.5726) < 0.05 and abs(lon - 88.3639) < 0.05:
        addr = "BBD Bagh, Dalhousie Square, Kolkata, West Bengal 700001"
        GEO_CACHE[cache_key] = addr
        return addr
    if abs(lat - 26.9124) < 0.05 and abs(lon - 75.7873) < 0.05:
        addr = "C-Scheme, Ashok Nagar, Jaipur, Rajasthan 302001"
        GEO_CACHE[cache_key] = addr
        return addr

    try:
        import urllib.request
        url = f"https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lon}&zoom=16&addressdetails=1"
        req = urllib.request.Request(url, headers={"User-Agent": "NCCT-Attendance-Portal/1.0"})
        with urllib.request.urlopen(req, timeout=2.5) as resp:
            data = json.loads(resp.read().decode())
            addr_data = data.get("address", {})
            suburb = addr_data.get("suburb") or addr_data.get("neighbourhood") or addr_data.get("road") or ""
            city = addr_data.get("city") or addr_data.get("town") or addr_data.get("county") or ""
            state = addr_data.get("state", "")
            postcode = addr_data.get("postcode", "")
            parts = [p for p in [suburb, city, state, postcode] if p]
            short_addr = ", ".join(parts) if parts else data.get("display_name", "")
            if short_addr:
                GEO_CACHE[cache_key] = short_addr
                return short_addr
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
    return LATEST_PHONE_GPS

@app.get("/api/search-location")
def search_location(q: str):
    if not q or len(q.strip()) < 2:
        return []
    try:
        import urllib.request, urllib.parse
        encoded_q = urllib.parse.quote(q.strip())
        url = f"https://nominatim.openstreetmap.org/search?format=json&q={encoded_q}&countrycodes=in&limit=6&addressdetails=1"
        req = urllib.request.Request(url, headers={"User-Agent": "NCCT-Attendance-Portal/1.0"})
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode())
            results = []
            for item in data:
                addr = item.get("address", {})
                city = addr.get("city") or addr.get("town") or addr.get("county") or ""
                state = addr.get("state", "")
                results.append({
                    "display_name": item.get("display_name"),
                    "lat": float(item.get("lat")),
                    "lon": float(item.get("lon")),
                    "city": city,
                    "state": state
                })
            return results
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

    student_id = payload.studentId or f"NCCT{max_id_num + 1}"
    roll_number = payload.rollNumber or str(max_roll + 1)

    # Process and save photo
    b64_data = payload.photoBase64
    if "," in b64_data:
        b64_data = b64_data.split(",", 1)[1]

    try:
        img_bytes = base64.b64decode(b64_data)
        img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image data: {str(e)}")

    students_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "students"))
    os.makedirs(students_dir, exist_ok=True)
    photo_file = f"{student_id}.jpg"
    photo_path = os.path.join(students_dir, photo_file)
    img.save(photo_path, "JPEG", quality=90)

    # Compute 192-D normalized embedding vector
    embedding = compute_face_embedding(img)

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
        
        ATTENDANCE_DB.append(record_dict)
        DEDUP_IDS.add(item.recordId)
        newly_synced.append(item.recordId)

    save_attendance_db()

    return SyncResponse(
        status="SUCCESS",
        syncedCount=len(newly_synced) - dup_count,
        duplicatesIgnored=dup_count,
        totalServerRecords=len(ATTENDANCE_DB),
        syncedRecordIds=newly_synced,
        serverTime=datetime.utcnow().isoformat()
    )

@app.get("/attendance/records")
def get_attendance_records():
    return {
        "count": len(ATTENDANCE_DB),
        "records": sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True)
    }

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

@app.post("/attendance/seed-demo")
def seed_demo_attendance():
    global ATTENDANCE_DB, DEDUP_IDS
    import uuid
    demo_pool = STUDENTS[:8] if len(STUDENTS) >= 8 else STUDENTS
    now_ms = int(datetime.utcnow().timestamp() * 1000)
    added = 0
    for i, s in enumerate(demo_pool):
        rid = f"EVT-DEMO-{uuid.uuid4().hex[:6].upper()}"
        if rid in DEDUP_IDS:
            continue
        session_id = s.get("enrolledSessionIds", ["DL-01"])[0] if s.get("enrolledSessionIds") else "DL-01"
        matched_sess = next((sess for sess in SESSIONS if sess["sessionId"] == session_id), SESSIONS[0] if SESSIONS else {})
        session_title = matched_sess.get("title", "Digital Literacy")
        lat = matched_sess.get("centerLatitude", 19.0760) + (i * 0.00008)
        lon = matched_sess.get("centerLongitude", 72.8777) + (i * 0.00008)

        rec = {
            "recordId": rid,
            "studentId": s.get("studentId", f"NCCT100{i+1}"),
            "studentName": s.get("name", f"Student {i+1}"),
            "sessionId": session_id,
            "sessionTitle": session_title,
            "timestamp": now_ms - (i * 240000),
            "similarityScore": round(0.81 + (i % 4) * 0.04, 3),
            "livenessScore": round(0.92 + (i % 3) * 0.02, 3),
            "latitude": lat,
            "longitude": lon,
            "isLocationValid": True,
            "deviceId": "ANDROID-OFFLINE-01",
            "serverReceivedAt": datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
        }
        ATTENDANCE_DB.append(rec)
        DEDUP_IDS.add(rid)
        added += 1

    save_attendance_db()
    return {"status": "SUCCESS", "added": added, "total": len(ATTENDANCE_DB)}

@app.delete("/attendance/clear")
def clear_records():
    global ATTENDANCE_DB, DEDUP_IDS
    ATTENDANCE_DB = []
    DEDUP_IDS = set()
    save_attendance_db()
    return {"status": "CLEARED"}

@app.get("/download/apk")
@app.head("/download/apk")
@app.get("/app-debug.apk")
@app.head("/app-debug.apk")
def download_apk():
    apk_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "app", "build", "outputs", "apk", "debug", "app-debug.apk"))
    if not os.path.exists(apk_path):
        raise HTTPException(status_code=404, detail="APK build not found at " + apk_path)
    return FileResponse(
        path=apk_path,
        media_type="application/vnd.android.package-archive",
        filename="OfflineFaceAttendance-debug.apk"
    )

@app.get("/apk_qr.png")
def get_apk_qr():
    qr_path = os.path.join(os.path.dirname(__file__), "apk_qr.png")
    if os.path.exists(qr_path):
        return FileResponse(qr_path, media_type="image/png")
    raise HTTPException(status_code=404, detail="QR code not found")

@app.get("/api/ip-location")
def get_ip_location():
    try:
        import urllib.request
        req = urllib.request.Request(
            "http://ip-api.com/json/?fields=status,message,country,regionName,city,lat,lon",
            headers={"User-Agent": "NCCT-Attendance-Server"}
        )
        with urllib.request.urlopen(req, timeout=4) as response:
            data = json.loads(response.read().decode())
            if data.get("status") == "success":
                return {
                    "latitude": data.get("lat"),
                    "longitude": data.get("lon"),
                    "city": data.get("city"),
                    "region": data.get("regionName"),
                    "country": data.get("country"),
                    "source": "IP Geolocation"
                }
    except Exception:
        pass
    return {
        "latitude": 28.6139,
        "longitude": 77.2090,
        "city": "New Delhi (Default)",
        "region": "Delhi",
        "country": "India",
        "source": "Default Preset"
    }

@app.get("/", response_class=HTMLResponse)
def live_dashboard():
    # Calculate Attendance Database Statistics
    total_att = len(ATTENDANCE_DB)
    verified_att = sum(1 for r in ATTENDANCE_DB if r.get("isLocationValid"))
    unique_students_att = len(set(r.get("studentId") for r in ATTENDANCE_DB if r.get("studentId")))

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
        
        rows_html += f"""
        <tr class="att-row" data-search="{search_data}">
            <td>
                <code class="code-id">{r.get('recordId')[:12]}</code>
                <div class="text-xs text-slate-400 font-mono mt-1">{r.get('deviceId', 'ANDROID-OFFLINE-01')}</div>
            </td>
            <td>
                <div class="font-semibold text-slate-900">{r.get('studentName')}</div>
                <div class="text-xs font-mono text-slate-500">{r.get('studentId')}</div>
            </td>
            <td>
                <div class="font-medium text-slate-800">{r.get('sessionTitle')}</div>
                <span class="badge badge-neutral">{r.get('sessionId')}</span>
            </td>
            <td class="text-slate-700 font-mono text-xs" style="white-space:nowrap;">{ts}</td>
            <td>{sim_badge}</td>
            <td>{live_badge}</td>
            <td>
                {badge_loc}
                <div class="text-xs font-mono text-slate-500 mt-1">{r.get('latitude', 0.0):.4f}°, {r.get('longitude', 0.0):.4f}°</div>
            </td>
            <td>
                <span class="badge badge-primary">SYNCED</span>
                <div class="text-xs text-slate-400 mt-1">{r.get('serverReceivedAt', '')}</div>
            </td>
        </tr>
        """
    if not rows_html:
        rows_html = """<tr><td colspan="8" class="empty-state" style="padding: 32px 16px; text-align: center; color: #64748b;">📭 No attendance records in database yet.<br><span class="text-xs text-slate-400 mt-1 inline-block">Mark attendance on the offline Android app, then open 'Sync Gateway' & tap 'Sync with Server', or click 'Seed Demo Records' above.</span></td></tr>"""

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

    phone_gps_btn = ""
    if last_phone_lat and last_phone_lon:
        phone_gps_btn = f"""<button type="button" onclick="applyPreset({last_phone_lat}, {last_phone_lon}, 'Phone GPS')" class="btn btn-outline text-xs">📱 Match Phone's GPS ({last_phone_lat:.4f}, {last_phone_lon:.4f})</button>"""

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
                <a href="#attendance-db-section" class="btn btn-outline" style="font-size: 12px; padding: 8px 14px; text-decoration: none; font-weight: 700;">📊 Attendance DB ({total_att})</a>
                <a href="/download/apk" class="download-btn">📲 DOWNLOAD APK (88 MB)</a>
                <div class="status-pill">
                    <span class="pulse-dot"></span>
                    GATEWAY ONLINE
                </div>
            </div>
        </div>

        <!-- LAN WARNING BANNER IF OPENED OVER 192.168.x.x -->
        <div id="lan-warning-banner" style="display:none; background: #fffbeb; border: 1.5px solid #f59e0b; color: #92400e; padding: 14px 20px; border-radius: 10px; margin-bottom: 20px; font-size: 13px; line-height: 1.5;">
            <div style="display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px;">
                <div>
                    <strong>⚠️ Chrome Security Restriction:</strong> You are accessing via local IP (<code class="font-mono" id="current-host-ip"></code>). Google Chrome blocks laptop Wi-Fi GPS over plain HTTP and falls back to ISP gateway.
                </div>
                <a id="switch-localhost-link" href="http://localhost:8000" style="background: #d97706; color: #ffffff; padding: 7px 16px; border-radius: 6px; text-decoration: none; font-weight: 700; font-size: 12px; display: inline-flex; align-items: center; gap: 6px;">
                    🚀 Open via http://localhost:8000 (Enables Real Wi-Fi/GPS)
                </a>
            </div>
        </div>

        <!-- STATS OVERVIEW -->
        <div class="stats-grid">
            <a href="#attendance-db-section" style="text-decoration:none; display:block;" class="stat-card" title="Click to view Attendance Database">
                <div class="stat-label">Synced Attendances ↗</div>
                <div class="stat-value" style="color: var(--emerald);">{total_att}</div>
            </a>
            <div class="stat-card">
                <div class="stat-label">Active Courses</div>
                <div class="stat-value">{len(SESSIONS)}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Registered Students</div>
                <div class="stat-value">{len(STUDENTS)}</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Biometric Model</div>
                <div class="stat-value" style="color: var(--cyan); font-size: 21px; margin-top: 10px;">FaceNet 512D</div>
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
                    <button id="phone-gps-quick-btn" type="button" class="btn btn-outline text-xs" style="display:none;"></button>
                    {phone_gps_btn}
                    <button type="button" onclick="setAllSessionsToAutoLocation()" class="btn btn-primary text-xs">📍 Auto-Detect Location</button>
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
                    <button type="button" onclick="seedDemoAttendance()" class="btn btn-secondary text-xs">🧪 Seed Demo Records</button>
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
                        <th>Event & Device</th>
                        <th>Student Details</th>
                        <th>Course & Session</th>
                        <th>Biometric Timestamp</th>
                        <th>Cosine Match</th>
                        <th>Liveness Score</th>
                        <th>Center Proximity</th>
                        <th>Sync Gateway</th>
                    </tr>
                </thead>
                <tbody id="attendance-table-body">
                    {rows_html}
                </tbody>
            </table>
        </div>

        <div id="toast">Saved location!</div>

        <script>
            function showToast(msg) {{
                const t = document.getElementById("toast");
                t.innerText = msg;
                t.className = "show";
                setTimeout(() => {{ t.className = t.className.replace("show", ""); }}, 3200);
            }}

            // Check if user is accessing on local IP instead of localhost
            if (window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {{
                const banner = document.getElementById('lan-warning-banner');
                if (banner) banner.style.display = 'block';
                const hostEl = document.getElementById('current-host-ip');
                if (hostEl) hostEl.innerText = window.location.host;
                const linkEl = document.getElementById('switch-localhost-link');
                if (linkEl) linkEl.href = 'http://localhost:' + (window.location.port || '8000');
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
                }} catch (e) {{
                    console.warn("Leaflet initialization error:", e);
                }}
            }}
            window.addEventListener('DOMContentLoaded', initMap);

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
                if (map) map.setView([lat, lon], 16);
                if (marker) marker.setLatLng([lat, lon]);
                if (circle) circle.setLatLng([lat, lon]);
                await onLocationSelected(lat, lon, name);
            }}

            async function onLocationSelected(lat, lon, customName) {{
                await applyPreset(lat, lon, customName || "Selected Map Location");
            }}

            // Poll for phone GPS
            setInterval(async () => {{
                try {{
                    const res = await fetch('/api/phone-location');
                    const data = await res.json();
                    if (data && data.latitude && data.longitude) {{
                        const phoneBtn = document.getElementById('phone-gps-quick-btn');
                        if (phoneBtn) {{
                            phoneBtn.style.display = 'inline-flex';
                            phoneBtn.innerHTML = `📱 Use Phone's Real GPS (${{data.latitude.toFixed(4)}}, ${{data.longitude.toFixed(4)}})`;
                            phoneBtn.onclick = () => {{
                                if (map) map.setView([data.latitude, data.longitude], 16);
                                if (marker) marker.setLatLng([data.latitude, data.longitude]);
                                if (circle) circle.setLatLng([data.latitude, data.longitude]);
                                onLocationSelected(data.latitude, data.longitude, data.address || "Phone GPS");
                            }};
                        }}
                    }}
                }} catch (_) {{}}
            }}, 4000);

            // Robust Location resolver: Browser GPS if secure context, else server IP geolocation
            async function resolveBestLocation() {{
                if (window.isSecureContext && navigator.geolocation) {{
                    try {{
                        const pos = await new Promise((resolve, reject) => {{
                            navigator.geolocation.getCurrentPosition(resolve, reject, {{
                                enableHighAccuracy: true,
                                timeout: 4000
                            }});
                        }});
                        return {{
                            lat: pos.coords.latitude,
                            lon: pos.coords.longitude,
                            source: "Browser GPS"
                        }};
                    }} catch (e) {{
                        console.warn("Browser GPS not available or permission denied:", e.message);
                    }}
                }}

                // Seamless Fallback: Server IP Geolocation (works anywhere over HTTP/LAN)
                try {{
                    const res = await fetch('/api/ip-location');
                    const data = await res.json();
                    if (data && data.latitude && data.longitude) {{
                        return {{
                            lat: data.latitude,
                            lon: data.longitude,
                            source: data.city ? "IP Geo (" + data.city + ")" : "Network Location"
                        }};
                    }}
                }} catch (e) {{
                    console.error("IP Location fallback failed:", e);
                }}

                return {{ lat: 28.6139, lon: 77.2090, source: "Default Preset" }};
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
                const addr = await fetchAddressForCoords(loc.lat, loc.lon);
                const addrEl = document.getElementById('addr-' + sid);
                if (addrEl) addrEl.innerText = "📍 " + addr;
                await saveSessionLocation(sid);
                showToast("📍 Acquired & Auto-Saved: " + addr);
            }}

            async function setAllSessionsToAutoLocation() {{
                showToast("Detecting center location...");
                const loc = await resolveBestLocation();
                const addr = await fetchAddressForCoords(loc.lat, loc.lon);
                await applyPreset(loc.lat, loc.lon, addr);
                showToast("📍 Auto-saved all sessions to: " + addr);
            }}

            async function applyPreset(lat, lon, name) {{
                showToast("Applying & Auto-saving " + name + "...");
                const addr = await fetchAddressForCoords(lat, lon);
                const activeEl = document.getElementById('active-center-title');
                if (activeEl) activeEl.innerText = addr;

                if (map) map.setView([lat, lon], 15);
                if (marker) marker.setLatLng([lat, lon]);
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
                showToast("✓ Auto-saved " + name + " (" + addr + ") to all sessions!");
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

            async function seedDemoAttendance() {{
                try {{
                    showToast("Generating demo biometric attendance records...");
                    const res = await fetch("/attendance/seed-demo", {{ method: "POST" }});
                    const data = await res.json();
                    showToast("✓ Added " + data.added + " demo attendance records!");
                    setTimeout(() => location.reload(), 500);
                }} catch (e) {{
                    alert("Failed to seed demo: " + e);
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
        </script>
    </body>
    </html>
    """

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)

