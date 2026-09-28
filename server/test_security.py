"""
Security validation tests for the enrollment endpoint.
Tests: blank image, cat photo (simulated), no-face image, normal human face.
Run: python server/test_security.py
"""
import base64
import sys
import time
import io
import urllib.request
import urllib.error
import json

sys.stdout.reconfigure(encoding='utf-8')

try:
    from PIL import Image, ImageDraw, ImageFont
    import numpy as np
except ImportError:
    print("Install PIL: pip install Pillow")
    sys.exit(1)

SERVER = "http://localhost:8000"

def b64_from_pil(img: Image.Image) -> str:
    buf = io.BytesIO()
    img.save(buf, format='JPEG', quality=90)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()

def post_onboard(photo_b64: str, name="Test Person") -> tuple:
    payload = json.dumps({
        "name": name,
        "course": "Test Course",
        "sessionId": "DL-01",
        "photoBase64": photo_b64,
        "isExistingStudent": False
    }).encode('utf-8')
    req = urllib.request.Request(
        f"{SERVER}/api/students/onboard",
        data=payload,
        headers={'Content-Type': 'application/json'},
        method='POST'
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body = {}
        try:
            body = json.loads(e.read())
        except Exception:
            pass
        return e.code, body

def make_blank_image(w=300, h=300, color=(200, 200, 200)) -> Image.Image:
    """Solid grey rectangle — no face."""
    return Image.new('RGB', (w, h), color)

def make_animal_like_image() -> Image.Image:
    """
    Simulate an 'animal' image: draw an oval with pointy ears and whiskers
    that would confuse a naive face detector but fail eye detection.
    The Haar eye cascade should find 0 eyes inside this shape.
    """
    img = Image.new('RGB', (400, 400), (200, 180, 160))
    draw = ImageDraw.Draw(img)
    # Body oval (cat-like, wide)
    draw.ellipse([80, 120, 320, 300], fill=(150, 120, 90), outline=(100, 80, 60), width=3)
    # Pointy ears
    draw.polygon([(100, 130), (80, 60), (150, 110)], fill=(150, 120, 90), outline=(100, 80, 60))
    draw.polygon([(300, 130), (320, 60), (250, 110)], fill=(150, 120, 90), outline=(100, 80, 60))
    # Small slanted eyes (not human eye shape)
    draw.ellipse([140, 170, 175, 190], fill=(50, 200, 50))  # cat-green eyes
    draw.ellipse([225, 170, 260, 190], fill=(50, 200, 50))
    # Whiskers
    draw.line([(160, 240), (60, 220)], fill=(80, 60, 40), width=2)
    draw.line([(160, 248), (60, 248)], fill=(80, 60, 40), width=2)
    draw.line([(240, 240), (340, 220)], fill=(80, 60, 40), width=2)
    draw.line([(240, 248), (340, 248)], fill=(80, 60, 40), width=2)
    return img

def make_text_image() -> Image.Image:
    """Text-only image — no face at all."""
    img = Image.new('RGB', (400, 200), (255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.text((50, 80), "Hello World", fill=(0, 0, 0))
    return img

def make_very_small_face_image() -> Image.Image:
    """Distant crowd shot — face exists but is tiny (< 15% of width)."""
    img = Image.new('RGB', (800, 600), (180, 200, 220))
    draw = ImageDraw.Draw(img)
    # Tiny face at top (about 5% of width = 40px)
    draw.ellipse([370, 20, 430, 90], fill=(220, 180, 150))
    return img

# ── Run tests ─────────────────────────────────────────────────────────────────
print("=" * 60)
print("ENROLLMENT SECURITY VALIDATION TESTS")
print(f"Server: {SERVER}")
print("=" * 60)

# Wait for server
time.sleep(2)
try:
    urllib.request.urlopen(f"{SERVER}/health", timeout=5)
except Exception as e:
    print(f"Server not reachable: {e}")
    sys.exit(1)

tests = [
    ("Blank grey image (no face)", make_blank_image(), 422),
    ("Animal/cat-like drawing", make_animal_like_image(), 422),
    ("Text-only image", make_text_image(), 422),
    ("Very small face (crowd shot)", make_very_small_face_image(), 422),
]

# Test with a real enrolled human face image
import os
sample_student_path = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "students", "NCCT1002.jpg")
if os.path.exists(sample_student_path):
    # Test valid human face
    real_img = Image.open(sample_student_path)
    # Since NCCT1002 is already registered, this should pass human face check and hit 409 duplicate check or 200
    b64 = b64_from_pil(real_img)
    status, body = post_onboard(b64, name="Duplicate Check Test")
    print(f"  ℹ️ Real human photo test status: {status} (Expected 409 duplicate or 200 success, got {status})")
    detail = body.get('detail', body.get('message', str(body)))[:120]
    print(f"     Message: {detail}")
    if status in (200, 409):
        print("  ✅ PASS: Real human face passed photo validation gate successfully!")
    else:
        print(f"  ❌ FAIL: Real human face rejected unexpectedly with status {status}")


passed = 0
failed = 0

for test_name, img, expected_status in tests:
    b64 = b64_from_pil(img)
    status, body = post_onboard(b64, name=f"Test_{test_name[:20]}")
    detail = body.get('detail', body.get('message', str(body)))[:120]
    ok = status == expected_status
    if ok:
        passed += 1
        print(f"  ✅ PASS  [{status}] {test_name}")
        print(f"          Reason: {detail}")
    else:
        failed += 1
        print(f"  ❌ FAIL  [got {status}, expected {expected_status}] {test_name}")
        print(f"          Response: {detail}")
    print()

print("=" * 60)
print(f"Results: {passed} passed / {failed} failed out of {len(tests)} tests")
if failed == 0:
    print("✅ All security tests passed — non-human photos are correctly rejected")
else:
    print("⚠️  Some tests failed — review server validate_human_face() function")
print("=" * 60)
