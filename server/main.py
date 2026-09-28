import os
import json
from typing import List, Optional
from datetime import datetime
from fastapi import FastAPI, HTTPException, Request
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

class LocationUpdate(BaseModel):
    latitude: float
    longitude: float
    centerName: Optional[str] = None

@app.get("/sessions")
def get_sessions():
    return SESSIONS

@app.post("/sessions/{session_id}/location")
def update_session_location(session_id: str, payload: LocationUpdate):
    for s in SESSIONS:
        if s["sessionId"] == session_id:
            s["centerLatitude"] = payload.latitude
            s["centerLongitude"] = payload.longitude
            if payload.centerName:
                s["centerName"] = payload.centerName
            return {"status": "UPDATED", "session": s}
    raise HTTPException(status_code=404, detail="Session not found")

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

@app.delete("/attendance/clear")
def clear_records():
    global ATTENDANCE_DB, DEDUP_IDS
    ATTENDANCE_DB = []
    DEDUP_IDS = set()
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

@app.get("/", response_class=HTMLResponse)
def live_dashboard():
    rows_html = ""
    for r in sorted(ATTENDANCE_DB, key=lambda x: x.get("timestamp", 0), reverse=True):
        ts = datetime.fromtimestamp(r["timestamp"] / 1000.0).strftime("%I:%M:%S %p")
        badge_loc = '<span class="badge badge-success">Verified</span>' if r.get("isLocationValid") else '<span class="badge badge-danger">Out of bounds</span>'
        rows_html += f"""
        <tr>
            <td><code>{r.get('recordId')[:8]}...</code></td>
            <td><strong>{r.get('studentName')}</strong><br><small class="text-muted">{r.get('studentId')}</small></td>
            <td>{r.get('sessionTitle')} <br><small class="badge badge-secondary">{r.get('sessionId')}</small></td>
            <td>{ts}</td>
            <td><span class="badge badge-info">{r.get('similarityScore', 0.0):.2f}</span></td>
            <td><span class="badge badge-success">{r.get('livenessScore', 0.0):.2f}</span></td>
            <td>{badge_loc}<br><small>{r.get('latitude', 0.0):.4f}, {r.get('longitude', 0.0):.4f}</small></td>
            <td><span class="badge badge-primary">SYNCED</span></td>
        </tr>
        """
    if not rows_html:
        rows_html = """<tr><td colspan="8" style="text-align:center; padding: 2rem; color: #888;">No attendance records synced yet. Mark attendance on the offline Android app, then trigger sync.</td></tr>"""

    return f"""
    <!DOCTYPE html>
    <html lang="en">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>NCCT AI Attendance Gateway - Live Sync Dashboard</title>
        <meta http-equiv="refresh" content="5">
        <style>
            :root {{
                --bg: #0b0f19;
                --card: #111827;
                --border: #1f2937;
                --text: #f3f4f6;
                --accent: #10b981;
                --cyan: #06b6d4;
                --amber: #f59e0b;
                --danger: #ef4444;
            }}
            body {{
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                background-color: var(--bg);
                color: var(--text);
                margin: 0;
                padding: 24px;
            }}
            .header {{
                display: flex;
                justify-content: space-between;
                align-items: center;
                border-bottom: 1px solid var(--border);
                padding-bottom: 16px;
                margin-bottom: 24px;
            }}
            .title {{ font-size: 20px; font-weight: 700; color: #fff; }}
            .subtitle {{ font-size: 13px; color: #9ca3af; margin-top: 4px; }}
            .status-pill {{
                display: inline-flex;
                align-items: center;
                gap: 8px;
                background: rgba(16, 185, 129, 0.15);
                border: 1px solid var(--accent);
                color: var(--accent);
                padding: 6px 14px;
                border-radius: 9999px;
                font-size: 12px;
                font-weight: 600;
            }}
            .grid {{
                display: grid;
                grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                gap: 16px;
                margin-bottom: 24px;
            }}
            .metric-card {{
                background: var(--card);
                border: 1px solid var(--border);
                border-radius: 12px;
                padding: 16px;
            }}
            .metric-label {{ font-size: 12px; text-transform: uppercase; color: #9ca3af; letter-spacing: 0.05em; }}
            .metric-val {{ font-size: 28px; font-weight: 700; color: #fff; margin-top: 6px; }}
            .table-container {{
                background: var(--card);
                border: 1px solid var(--border);
                border-radius: 12px;
                overflow: hidden;
            }}
            table {{
                width: 100%;
                border-collapse: collapse;
                text-align: left;
                font-size: 13px;
            }}
            th {{
                background: #1e293b;
                color: #cbd5e1;
                padding: 12px 16px;
                font-weight: 600;
                text-transform: uppercase;
                font-size: 11px;
                letter-spacing: 0.05em;
            }}
            td {{
                padding: 12px 16px;
                border-top: 1px solid var(--border);
            }}
            .badge {{
                display: inline-block;
                padding: 3px 8px;
                border-radius: 4px;
                font-size: 11px;
                font-weight: 600;
            }}
            .badge-primary {{ background: rgba(59, 130, 246, 0.2); color: #60a5fa; }}
            .badge-success {{ background: rgba(16, 185, 129, 0.2); color: #34d399; }}
            .badge-info {{ background: rgba(6, 182, 212, 0.2); color: #22d3ee; }}
            .badge-secondary {{ background: #374151; color: #d1d5db; }}
            .badge-danger {{ background: rgba(239, 68, 68, 0.2); color: #f87171; }}
            .text-muted {{ color: #9ca3af; }}
            .download-btn {{
                display: inline-flex;
                align-items: center;
                gap: 8px;
                background: linear-gradient(135deg, #10b981 0%, #059669 100%);
                color: #ffffff;
                text-decoration: none;
                padding: 10px 18px;
                border-radius: 8px;
                font-weight: 700;
                font-size: 13px;
                box-shadow: 0 4px 14px rgba(16, 185, 129, 0.4);
                transition: transform 0.15s ease;
            }}
            .download-btn:hover {{ transform: scale(1.03); }}
        </style>
    </head>
    <body>
        <div class="header">
            <div>
                <div class="title">NCCT Central Server — Biometric Attendance Gateway</div>
                <div class="subtitle">SIH26087 Prototype | Offline Android Sync Monitor (Auto-refreshes every 5s)</div>
            </div>
            <div style="display:flex; align-items:center; gap: 12px;">
                <a href="/download/apk" class="download-btn">📲 DOWNLOAD APK (88 MB)</a>
                <div class="status-pill">
                    <span style="width:8px; height:8px; border-radius:50%; background:var(--accent);"></span>
                    GATEWAY ONLINE
                </div>
            </div>
        </div>

        <div class="grid">
            <div class="metric-card">
                <div class="metric-label">Synced Attendances</div>
                <div class="metric-val" style="color: var(--accent);">{len(ATTENDANCE_DB)}</div>
            </div>
            <div class="metric-card">
                <div class="metric-label">Active Sessions</div>
                <div class="metric-val">{len(SESSIONS)}</div>
            </div>
            <div class="metric-card">
                <div class="metric-label">Registered Students</div>
                <div class="metric-val">{len(STUDENTS)}</div>
            </div>
            <div class="metric-card">
                <div class="metric-label">Biometric Verification</div>
                <div class="metric-val" style="color: var(--cyan);">On-Device AI</div>
            </div>
        </div>

        <div class="table-container">
            <table>
                <thead>
                    <tr>
                        <th>Record ID</th>
                        <th>Student</th>
                        <th>Session</th>
                        <th>Marked Time</th>
                        <th>Cosine Match</th>
                        <th>Liveness</th>
                        <th>Location Check</th>
                        <th>Sync Status</th>
                    </tr>
                </thead>
                <tbody>
                    {rows_html}
                </tbody>
            </table>
        </div>
    </body>
    </html>
    """

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
