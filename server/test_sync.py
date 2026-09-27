import urllib.request
import json
import time

BASE_URL = "http://127.0.0.1:8000"

def test_gateway():
    print("==================================================")
    print("NCCT Offline AI Attendance Sync Gateway - Test Run")
    print("==================================================")

    # 1. Health Check
    try:
        # Clear server state for clean test
        clear_req = urllib.request.Request(f"{BASE_URL}/attendance/clear", method="DELETE")
        try:
            with urllib.request.urlopen(clear_req) as _:
                pass
        except Exception:
            pass

        req = urllib.request.Request(f"{BASE_URL}/health")
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode())
            print(f"[1] Health Check: {data}")
            assert data["status"] == "ONLINE"
    except Exception as e:
        print(f"Server not running at {BASE_URL}. Start server with: python server/main.py")
        return

    # 2. Get Sessions
    req = urllib.request.Request(f"{BASE_URL}/sessions")
    with urllib.request.urlopen(req) as resp:
        sessions = json.loads(resp.read().decode())
        print(f"[2] Active Sessions Count: {len(sessions)}")
        for s in sessions:
            print(f"    - {s['title']} ({s['sessionId']}) at {s['startTime']}")

    # 3. Test Attendance Sync (Simulating offline batch upload)
    sync_payload = {
        "deviceId": "ANDROID-OFFLINE-TESTER",
        "records": [
            {
                "recordId": "rec-sih-001",
                "studentId": "NCCT1024",
                "studentName": "Nishant Maurya",
                "sessionId": "DL-01",
                "sessionTitle": "Digital Literacy",
                "timestamp": int(time.time() * 1000),
                "similarityScore": 0.92,
                "livenessScore": 0.95,
                "latitude": 19.0760,
                "longitude": 72.8777,
                "isLocationValid": True
            },
            {
                "recordId": "rec-sih-002",
                "studentId": "NCCT1031",
                "studentName": "Aarav Sharma",
                "sessionId": "DL-01",
                "sessionTitle": "Digital Literacy",
                "timestamp": int(time.time() * 1000) + 5000,
                "similarityScore": 0.88,
                "livenessScore": 0.91,
                "latitude": 19.0760,
                "longitude": 72.8777,
                "isLocationValid": True
            }
        ]
    }

    req = urllib.request.Request(
        f"{BASE_URL}/attendance/sync",
        data=json.dumps(sync_payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        sync_result = json.loads(resp.read().decode())
        print(f"[3] Sync Response: {sync_result}")
        assert sync_result["status"] == "SUCCESS"
        assert sync_result["syncedCount"] == 2

    # 4. Test Deduplication Idempotency
    print("[4] Testing Deduplication (Uploading identical payload again)...")
    req = urllib.request.Request(
        f"{BASE_URL}/attendance/sync",
        data=json.dumps(sync_payload).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        dup_result = json.loads(resp.read().decode())
        print(f"    Deduplication Result: {dup_result}")
        assert dup_result["syncedCount"] == 0
        assert dup_result["duplicatesIgnored"] == 2
        print("    [OK] Idempotency verified: Duplicates safely ignored.")

    # 5. Fetch Synced Ledger
    req = urllib.request.Request(f"{BASE_URL}/attendance/records")
    with urllib.request.urlopen(req) as resp:
        records_data = json.loads(resp.read().decode())
        print(f"[5] Server Attendance Ledger Total: {records_data['count']} records")

    print("\n[OK] ALL SERVER GATEWAY TESTS PASSED.")

if __name__ == "__main__":
    test_gateway()
