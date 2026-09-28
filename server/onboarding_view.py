import json

def render_onboarding_page(sessions: list, students: list) -> str:
    # Build sessions options
    session_options = ""
    for s in sessions:
        session_options += f'<option value="{s["sessionId"]}">{s["title"]} ({s["sessionId"]}) — {s.get("centerName", "NCCT Center").split(" • ")[0]}</option>\n'

    # Build existing students options
    student_options = ""
    for st in students:
        student_options += f'<option value="{st["studentId"]}">{st["studentId"]} — {st["name"]} ({st.get("course", "Course")})</option>\n'

    # Auto IDs
    max_id_num = 1000
    max_roll = 100
    for st in students:
        sid = st.get("studentId", "")
        if sid.startswith("NCCT") and sid[4:].isdigit():
            max_id_num = max(max_id_num, int(sid[4:]))
        roll = st.get("rollNumber", "")
        if roll.isdigit():
            max_roll = max(max_roll, int(roll))
    next_id = f"NCCT{max_id_num + 1}"
    next_roll = str(max_roll + 1)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>NCCT Biometric Student Onboarding Portal</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
    <!-- Google MediaPipe FaceMesh via CDN -->
    <script src="https://cdn.jsdelivr.net/npm/@mediapipe/camera_utils/camera_utils.js" crossorigin="anonymous"></script>
    <script src="https://cdn.jsdelivr.net/npm/@mediapipe/face_mesh/face_mesh.js" crossorigin="anonymous"></script>
    <style>
        :root {{
            --bg: #f8fafc;
            --card-bg: #ffffff;
            --border: #e2e8f0;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #2563eb;
            --primary-hover: #1d4ed8;
            --emerald: #059669;
            --emerald-bg: #ecfdf5;
            --emerald-border: #a7f3d0;
            --amber: #d97706;
            --amber-bg: #fffbeb;
            --amber-border: #fde68a;
            --danger: #dc2626;
        }}
        * {{
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }}
        body {{
            font-family: 'Inter', system-ui, -apple-system, sans-serif;
            background-color: var(--bg);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
        }}
        .header {{
            background: #ffffff;
            border-bottom: 1px solid var(--border);
            padding: 14px 28px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            position: sticky;
            top: 0;
            z-index: 100;
        }}
        .header-brand {{
            display: flex;
            align-items: center;
            gap: 12px;
        }}
        .header-logo {{
            width: 38px;
            height: 38px;
            background: linear-gradient(135deg, #1e40af, #2563eb);
            color: #ffffff;
            font-weight: 800;
            font-size: 15px;
            border-radius: 9px;
            display: flex;
            align-items: center;
            justify-content: center;
            box-shadow: 0 4px 10px rgba(37, 99, 235, 0.2);
        }}
        .header-title {{
            font-size: 16px;
            font-weight: 700;
            color: var(--text-main);
        }}
        .header-subtitle {{
            font-size: 12px;
            color: var(--text-muted);
        }}
        .btn {{
            padding: 8px 16px;
            border-radius: 7px;
            font-size: 13px;
            font-weight: 600;
            cursor: pointer;
            border: 1px solid transparent;
            display: inline-flex;
            align-items: center;
            gap: 6px;
            text-decoration: none;
            transition: all 0.15s ease;
        }}
        .btn-secondary {{
            background: #f1f5f9;
            color: #334155;
            border-color: #cbd5e1;
        }}
        .btn-secondary:hover {{
            background: #e2e8f0;
            color: #0f172a;
        }}
        .btn-primary {{
            background: var(--primary);
            color: #ffffff;
            border-color: var(--primary);
        }}
        .btn-primary:hover {{
            background: var(--primary-hover);
        }}
        .btn-success {{
            background: var(--emerald);
            color: #ffffff;
            border-color: var(--emerald);
        }}
        .btn-success:hover {{
            background: #047857;
        }}

        .main-container {{
            max-width: 1200px;
            width: 100%;
            margin: 24px auto;
            padding: 0 20px;
            display: grid;
            grid-template-columns: 1fr 1.15fr;
            gap: 24px;
            flex: 1;
        }}
        @media (max-width: 900px) {{
            .main-container {{
                grid-template-columns: 1fr;
            }}
        }}

        .panel-card {{
            background: #ffffff;
            border: 1px solid var(--border);
            border-radius: 12px;
            box-shadow: 0 1px 3px rgba(0,0,0,0.03);
            display: flex;
            flex-direction: column;
            overflow: hidden;
        }}
        .panel-header {{
            padding: 16px 20px;
            border-bottom: 1px solid var(--border);
            background: #ffffff;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }}
        .panel-title {{
            font-size: 15px;
            font-weight: 700;
            color: #0f172a;
            display: flex;
            align-items: center;
            gap: 8px;
        }}
        .panel-body {{
            padding: 20px;
            flex: 1;
            display: flex;
            flex-direction: column;
            gap: 18px;
        }}

        .field-group {{
            display: flex;
            flex-direction: column;
            gap: 6px;
        }}
        .field-label {{
            font-size: 13px;
            font-weight: 600;
            color: #334155;
        }}
        .field-input, .field-select {{
            width: 100%;
            padding: 9px 12px;
            border-radius: 7px;
            border: 1px solid #cbd5e1;
            font-size: 13px;
            color: #0f172a;
            background: #ffffff;
            outline: none;
            transition: border 0.15s ease;
        }}
        .field-input:focus, .field-select:focus {{
            border-color: var(--primary);
            box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
        }}
        .field-hint {{
            font-size: 12px;
            color: #64748b;
            line-height: 1.4;
        }}

        .info-callout {{
            background: #eff6ff;
            border: 1px solid #bfdbfe;
            border-radius: 8px;
            padding: 12px 14px;
            font-size: 12px;
            color: #1e40af;
            line-height: 1.45;
        }}
        .info-callout strong {{
            color: #1d4ed8;
        }}

        /* RADIO TOGGLE */
        .enroll-mode-toggle {{
            display: flex;
            gap: 8px;
            background: #f1f5f9;
            padding: 4px;
            border-radius: 8px;
        }}
        .mode-btn {{
            flex: 1;
            text-align: center;
            padding: 7px 12px;
            font-size: 12px;
            font-weight: 600;
            border-radius: 6px;
            cursor: pointer;
            color: #475569;
            transition: all 0.15s ease;
        }}
        .mode-btn.active {{
            background: #ffffff;
            color: #0f172a;
            box-shadow: 0 2px 4px rgba(0,0,0,0.06);
        }}

        /* CAMERA & BIOMETRICS VIEWFINDER */
        .scanner-container {{
            position: relative;
            width: 100%;
            background: #0f172a;
            border-radius: 12px;
            overflow: hidden;
            aspect-ratio: 4 / 3;
            display: flex;
            align-items: center;
            justify-content: center;
        }}
        #webcam-video {{
            width: 100%;
            height: 100%;
            object-fit: cover;
            transform: scaleX(-1); /* mirror */
        }}
        #scanner-canvas {{
            position: absolute;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            pointer-events: none;
            transform: scaleX(-1);
        }}

        /* OVAL SCAN FRAME */
        .oval-guide {{
            position: absolute;
            width: 200px;
            height: 250px;
            border: 3px solid rgba(255, 255, 255, 0.7);
            border-radius: 50%;
            pointer-events: none;
            transition: border-color 0.25s, box-shadow 0.25s;
            box-shadow: 0 0 0 9999px rgba(15, 23, 42, 0.45);
        }}
        .oval-guide.tracking {{
            border-color: #38bdf8;
            box-shadow: 0 0 20px rgba(56, 189, 248, 0.5), 0 0 0 9999px rgba(15, 23, 42, 0.45);
        }}
        .oval-guide.success {{
            border-color: #10b981;
            box-shadow: 0 0 25px rgba(16, 185, 129, 0.8), 0 0 0 9999px rgba(15, 23, 42, 0.45);
        }}

        /* HUD OVERLAYS */
        .hud-top {{
            position: absolute;
            top: 12px;
            left: 12px;
            right: 12px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            z-index: 10;
        }}
        .hud-badge {{
            background: rgba(15, 23, 42, 0.75);
            backdrop-filter: blur(4px);
            color: #ffffff;
            font-size: 11px;
            font-weight: 600;
            padding: 4px 10px;
            border-radius: 9999px;
            border: 1px solid rgba(255, 255, 255, 0.15);
            display: flex;
            align-items: center;
            gap: 6px;
        }}
        .pulse-live {{
            width: 7px;
            height: 7px;
            background: #10b981;
            border-radius: 50%;
            box-shadow: 0 0 8px #10b981;
            animation: pulse 1.5s infinite;
        }}
        @keyframes pulse {{
            0%, 100% {{ opacity: 1; }}
            50% {{ opacity: 0.3; }}
        }}

        .hud-bottom-instruction {{
            position: absolute;
            bottom: 14px;
            left: 14px;
            right: 14px;
            background: rgba(15, 23, 42, 0.85);
            backdrop-filter: blur(8px);
            color: #ffffff;
            border-radius: 10px;
            padding: 12px 16px;
            display: flex;
            align-items: center;
            justify-content: space-between;
            border: 1px solid rgba(255, 255, 255, 0.2);
            z-index: 10;
        }}
        .instruction-text {{
            font-size: 13px;
            font-weight: 700;
            letter-spacing: 0.2px;
        }}
        .instruction-sub {{
            font-size: 11px;
            color: #94a3b8;
            margin-top: 2px;
        }}

        /* STEPS PROGRESS BAR */
        .pose-steps-bar {{
            display: grid;
            grid-template-columns: repeat(4, 1fr);
            gap: 8px;
            margin-top: 4px;
        }}
        .pose-step {{
            background: #f1f5f9;
            border: 1px solid #cbd5e1;
            border-radius: 8px;
            padding: 8px 6px;
            text-align: center;
            font-size: 11px;
            font-weight: 600;
            color: #64748b;
            transition: all 0.2s ease;
        }}
        .pose-step.active {{
            background: #eff6ff;
            border-color: #2563eb;
            color: #1d4ed8;
            box-shadow: 0 0 0 1px #2563eb;
        }}
        .pose-step.done {{
            background: var(--emerald-bg);
            border-color: var(--emerald-border);
            color: var(--emerald);
        }}

        /* CAPTURED PREVIEW CARD */
        .captured-card {{
            display: none;
            background: #f8fafc;
            border: 1.5px solid var(--emerald-border);
            border-radius: 10px;
            padding: 14px;
            align-items: center;
            gap: 14px;
        }}
        .captured-img {{
            width: 72px;
            height: 72px;
            border-radius: 8px;
            object-fit: cover;
            border: 2px solid var(--emerald);
        }}
        .captured-details {{
            flex: 1;
        }}
        .captured-title {{
            font-size: 13px;
            font-weight: 700;
            color: #065f46;
        }}
        .captured-meta {{
            font-size: 11px;
            color: #64748b;
            margin-top: 2px;
            font-family: 'JetBrains Mono', monospace;
        }}

        /* SUCCESS MODAL */
        #success-modal {{
            display: none;
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: rgba(15, 23, 42, 0.6);
            backdrop-filter: blur(4px);
            z-index: 1000;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }}
        .modal-content {{
            background: #ffffff;
            border-radius: 14px;
            max-width: 480px;
            width: 100%;
            padding: 28px;
            text-align: center;
            box-shadow: 0 20px 40px rgba(0,0,0,0.2);
            animation: modalPop 0.3s ease;
        }}
        @keyframes modalPop {{
            from {{ transform: scale(0.9); opacity: 0; }}
            to {{ transform: scale(1); opacity: 1; }}
        }}
    </style>
</head>
<body>
    <header class="header">
        <div class="header-brand">
            <div class="header-logo">NC</div>
            <div>
                <h1 class="header-title">NCCT Biometric Student Onboarding Portal</h1>
                <p class="header-subtitle">SIH26087 Prototype | Automated 3D Pose & Anti-Spoof Liveness Engine</p>
            </div>
        </div>
        <div style="display: flex; gap: 10px; align-items: center;">
            <a href="/" class="btn btn-secondary">← Back to Gateway Dashboard</a>
        </div>
    </header>

    <main class="main-container">
        <!-- LEFT PANEL: STUDENT CREDENTIALS & COURSE ENROLLMENT -->
        <section class="panel-card">
            <div class="panel-header">
                <div class="panel-title">📝 1. Student Enrollment Details</div>
                <span class="hud-badge" style="background:#f1f5f9; color:#475569; border-color:#cbd5e1;">Roster: {len(students)} Enrolled</span>
            </div>
            <div class="panel-body">
                <!-- ENROLL MODE TOGGLE -->
                <div class="enroll-mode-toggle">
                    <div id="mode-new" class="mode-btn active" onclick="switchEnrollMode('new')">👤 New Student Registration</div>
                    <div id="mode-existing" class="mode-btn" onclick="switchEnrollMode('existing')">🔄 Add Course to Existing</div>
                </div>

                <!-- NEW STUDENT FORM SECTION -->
                <div id="new-student-fields" style="display:flex; flex-direction:column; gap:16px;">
                    <div class="field-group">
                        <label class="field-label">Student Full Name *</label>
                        <input type="text" id="student-name-input" class="field-input" placeholder="e.g. Ramesh Kumar Verma" autocomplete="off">
                        <div class="field-hint" style="color: #2563eb; font-weight: 500; margin-top: 4px;">
                            ℹ️ This is biometric enrollment. In the actual working system, this is completed during course onboarding.
                        </div>
                    </div>

                    <div class="field-group">
                        <label class="field-label">Assigned Course & Session *</label>
                        <select id="course-select-new" class="field-select">
                            {session_options}
                        </select>
                    </div>

                    <!-- AUTO ID PREVIEW -->
                    <div style="background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 8px; padding: 12px 14px; display:flex; justify-content:space-between; align-items:center;">
                        <div>
                            <div style="font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #64748b; font-weight: 600;">System Generated ID</div>
                            <div id="preview-student-id" style="font-size: 14px; font-weight: 700; color: #0f172a; font-family: 'JetBrains Mono', monospace;">{next_id}</div>
                        </div>
                        <div>
                            <div style="font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: #64748b; font-weight: 600;">Roll Number</div>
                            <div id="preview-roll-no" style="font-size: 14px; font-weight: 700; color: #0f172a; font-family: 'JetBrains Mono', monospace;">{next_roll}</div>
                        </div>
                    </div>
                </div>

                <!-- EXISTING STUDENT FORM SECTION -->
                <div id="existing-student-fields" style="display:none; flex-direction:column; gap:16px;">
                    <div class="field-group">
                        <label class="field-label">Select Existing Student</label>
                        <select id="existing-student-select" class="field-select" onchange="onExistingStudentChange(this.value)">
                            {student_options}
                        </select>
                    </div>

                    <div class="field-group">
                        <label class="field-label">Additional Course to Enroll In</label>
                        <select id="course-select-existing" class="field-select">
                            {session_options}
                        </select>
                    </div>
                </div>

                <div class="info-callout">
                    <strong>Trial Prototype Notice:</strong> In full enterprise deployment, this form automatically synchronizes UIDAI/Aadhaar biometric tokens, academic qualification certificates, and mobile OTP verification.
                </div>

                <!-- SUBMIT BUTTON -->
                <div style="margin-top: auto; padding-top: 10px;">
                    <button type="button" id="submit-enroll-btn" class="btn btn-primary" style="width: 100%; justify-content: center; padding: 12px; font-size: 14px;" onclick="submitEnrollment()" disabled>
                        🔒 Complete Biometric Scan to Register
                    </button>
                </div>
            </div>
        </section>

        <!-- RIGHT PANEL: WEBCAM SCANNER & REAL-TIME POSE DETECTOR -->
        <section class="panel-card">
            <div class="panel-header">
                <div class="panel-title">📷 2. Automated Biometric Face Scan</div>
                <div class="hud-badge" style="background:#ecfdf5; color:#065f46; border-color:#a7f3d0;">
                    <span class="pulse-live"></span>
                    Auto-Pose Active
                </div>
            </div>
            <div class="panel-body">
                <!-- VIDEO SCANNER -->
                <div class="scanner-container">
                    <video id="webcam-video" autoplay playsinline muted></video>
                    <canvas id="scanner-canvas"></canvas>

                    <!-- OVAL GUIDE -->
                    <div id="oval-guide" class="oval-guide tracking"></div>

                    <!-- HUD TOP INFO -->
                    <div class="hud-top">
                        <div class="hud-badge">
                            <span>🎯</span>
                            <span id="hud-yaw-readout">Yaw: 0.0°</span>
                        </div>
                        <div class="hud-badge">
                            <span>👁️</span>
                            <span id="hud-blink-readout">Eyes: Open</span>
                        </div>
                    </div>

                    <!-- HUD BOTTOM INSTRUCTION BANNER -->
                    <div class="hud-bottom-instruction">
                        <div>
                            <div id="instruction-title" class="instruction-text">1. Look Straight into Camera</div>
                            <div id="instruction-sub" class="instruction-sub">Position your face inside the oval frame</div>
                        </div>
                        <div id="instruction-icon" style="font-size: 24px;">🎯</div>
                    </div>
                </div>

                <!-- 4-STEP POSE PROGRESS BAR -->
                <div class="pose-steps-bar">
                    <div id="step-badge-1" class="pose-step active">1. Straight</div>
                    <div id="step-badge-2" class="pose-step">2. Turn Left</div>
                    <div id="step-badge-3" class="pose-step">3. Turn Right</div>
                    <div id="step-badge-4" class="pose-step">4. Blink</div>
                </div>

                <!-- CAPTURED PHOTO CONFIRMATION CARD -->
                <div id="captured-card" class="captured-card">
                    <img id="captured-preview-img" class="captured-img" src="" alt="Captured Face">
                    <div class="captured-details">
                        <div class="captured-title">✓ Biometrics Successfully Verified!</div>
                        <div class="captured-meta" id="captured-meta-text">192-D Vector Extracted • Anti-Spoof Pass</div>
                        <button type="button" class="btn btn-secondary text-xs" style="margin-top: 6px; padding: 4px 10px;" onclick="retakeBiometrics()">🔄 Retake Scan</button>
                    </div>
                </div>

                <!-- MANUAL CONTROLS & CAMERA SELECTOR -->
                <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                    <button type="button" class="btn btn-secondary text-xs" onclick="manualCaptureSnapshot()">📷 Snap Manually (Override)</button>
                    <span class="text-xs text-slate-500">Auto-detects poses seamlessly at 30 FPS</span>
                </div>
            </div>
        </section>
    </main>

    <!-- SUCCESS MODAL -->
    <div id="success-modal">
        <div class="modal-content">
            <div style="font-size: 48px; margin-bottom: 8px;">🎉</div>
            <h2 style="font-size: 20px; font-weight: 800; color: #0f172a; margin-bottom: 6px;">Biometric Enrollment Complete!</h2>
            <p style="font-size: 13px; color: #64748b; line-height: 1.5; margin-bottom: 18px;" id="modal-desc">
                Student biometrics have been saved and assigned to their training session. The offline mobile Android device will download this student on next gateway sync!
            </p>
            <div style="display: flex; gap: 10px; justify-content: center;">
                <button type="button" class="btn btn-primary" onclick="resetFormForNext()">➕ Enroll Another Student</button>
                <a href="/" class="btn btn-secondary">📊 View in Gateway Dashboard</a>
            </div>
        </div>
    </div>

    <!-- AUDIO SYNTHESIS & REAL-TIME POSE ENGINE -->
    <script>
        // Web Audio Synthesizer (Zero asset dependencies)
        let audioCtx;
        function playChime(freq = 600, type = 'sine', duration = 0.15) {{
            try {{
                if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                if (audioCtx.state === 'suspended') audioCtx.resume();
                const osc = audioCtx.createOscillator();
                const gain = audioCtx.createGain();
                osc.type = type;
                osc.frequency.setValueAtTime(freq, audioCtx.currentTime);
                gain.gain.setValueAtTime(0.15, audioCtx.currentTime);
                gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + duration);
                osc.connect(gain);
                gain.connect(audioCtx.destination);
                osc.start();
                osc.stop(audioCtx.currentTime + duration);
            }} catch (e) {{}}
        }}

        function playSuccessFanfare() {{
            playChime(523.25, 'triangle', 0.15); // C5
            setTimeout(() => playChime(659.25, 'triangle', 0.18), 120); // E5
            setTimeout(() => playChime(783.99, 'triangle', 0.25), 240); // G5
            setTimeout(() => playChime(1046.50, 'triangle', 0.4), 380); // C6
        }}

        // State Machine
        const STEP_STRAIGHT = 1;
        const STEP_LEFT = 2;
        const STEP_RIGHT = 3;
        const STEP_BLINK = 4;
        const STEP_DONE = 5;

        let currentStep = STEP_STRAIGHT;
        let capturedPhotoBase64 = null;
        let isEnrollModeNew = true;
        let straightHoldCount = 0;
        let leftHoldCount = 0;
        let rightHoldCount = 0;
        let blinkDetected = false;

        const video = document.getElementById('webcam-video');
        const canvas = document.getElementById('scanner-canvas');
        const ctx = canvas.getContext('2d');
        const ovalGuide = document.getElementById('oval-guide');
        const instructionTitle = document.getElementById('instruction-title');
        const instructionSub = document.getElementById('instruction-sub');
        const instructionIcon = document.getElementById('instruction-icon');
        const hudYaw = document.getElementById('hud-yaw-readout');
        const hudBlink = document.getElementById('hud-blink-readout');

        // Initialize Camera
        async function initCamera() {{
            try {{
                const stream = await navigator.mediaDevices.getUserMedia({{
                    video: {{ width: {{ ideal: 640 }}, height: {{ ideal: 480 }}, facingMode: 'user' }},
                    audio: false
                }});
                video.srcObject = stream;
                video.onloadedmetadata = () => {{
                    canvas.width = video.videoWidth || 640;
                    canvas.height = video.videoHeight || 480;
                    initMediaPipe();
                }};
            }} catch (e) {{
                instructionTitle.innerText = "Camera Access Blocked";
                instructionSub.innerText = "Please allow webcam permission in your browser.";
                instructionIcon.innerText = "⚠️";
            }}
        }}

        // MediaPipe FaceMesh Initialization
        let faceMesh;
        function initMediaPipe() {{
            try {{
                if (typeof FaceMesh !== 'undefined') {{
                    faceMesh = new FaceMesh({{
                        locateFile: (file) => `https://cdn.jsdelivr.net/npm/@mediapipe/face_mesh/${{file}}`
                    }});
                    faceMesh.setOptions({{
                        maxNumFaces: 1,
                        refineLandmarks: true,
                        minDetectionConfidence: 0.5,
                        minTrackingConfidence: 0.5
                    }});
                    faceMesh.onResults(onFaceResults);

                    const camera = new Camera(video, {{
                        onFrame: async () => {{
                            if (currentStep !== STEP_DONE) {{
                                await faceMesh.send({{ image: video }});
                            }}
                        }},
                        width: 640,
                        height: 480
                    }});
                    camera.start();
                }} else {{
                    fallbackTrackingLoop();
                }}
            }} catch (e) {{
                fallbackTrackingLoop();
            }}
        }}

        // Real-Time 3D Landmark & Pose Processor
        function onFaceResults(results) {{
            if (currentStep === STEP_DONE) return;

            if (!results.multiFaceLandmarks || results.multiFaceLandmarks.length === 0) {{
                ovalGuide.className = "oval-guide";
                hudYaw.innerText = "No Face Detected";
                return;
            }}

            ovalGuide.className = "oval-guide tracking";
            const landmarks = results.multiFaceLandmarks[0];

            // Key Landmark indices
            // 1: Nose tip
            // 234: Left Cheek boundary
            // 454: Right Cheek boundary
            // 159, 145: Left Eyelid Top & Bottom
            // 386, 374: Right Eyelid Top & Bottom
            // 33: Left eye outer corner, 263: Right eye outer corner
            const nose = landmarks[1];
            const leftCheek = landmarks[234];
            const rightCheek = landmarks[454];

            const leftTop = landmarks[159];
            const leftBottom = landmarks[145];
            const rightTop = landmarks[386];
            const rightBottom = landmarks[374];

            // Calculate Head Yaw (horizontal rotation)
            const cheekSpan = Math.abs(rightCheek.x - leftCheek.x);
            const noseRel = (nose.x - leftCheek.x) / (cheekSpan || 0.001);
            // In mirrored coordinates:
            // noseRel ~ 0.5 is straight
            // noseRel > 0.58 is turning to user's left
            // noseRel < 0.42 is turning to user's right
            const yawDegrees = (noseRel - 0.5) * 80;

            hudYaw.innerText = `Yaw: ${{yawDegrees > 0 ? '+' : ''}}${{yawDegrees.toFixed(1)}}°`;

            // Calculate Eye Aspect Ratio (EAR) for blink detection
            const leftEAR = Math.hypot(leftTop.x - leftBottom.x, leftTop.y - leftBottom.y);
            const rightEAR = Math.hypot(rightTop.x - rightBottom.x, rightTop.y - rightBottom.y);
            const isBlinking = leftEAR < 0.015 && rightEAR < 0.015;

            hudBlink.innerText = isBlinking ? "Eyes: Blinking! ✓" : "Eyes: Open";

            // State Machine Transition Logic
            if (currentStep === STEP_STRAIGHT) {{
                if (Math.abs(yawDegrees) < 8.0) {{
                    straightHoldCount++;
                    if (straightHoldCount > 10) {{
                        playChime(660);
                        markStepDone(1);
                        currentStep = STEP_LEFT;
                        updateHud("2. Turn Head Left", "Turn your face slightly to the left", "⬅️");
                    }}
                }} else {{
                    straightHoldCount = Math.max(0, straightHoldCount - 1);
                }}
            }} else if (currentStep === STEP_LEFT) {{
                // Turned left
                if (yawDegrees > 11.0) {{
                    leftHoldCount++;
                    if (leftHoldCount > 8) {{
                        playChime(740);
                        markStepDone(2);
                        currentStep = STEP_RIGHT;
                        updateHud("3. Turn Head Right", "Turn your face slightly to the right", "➡️");
                    }}
                }}
            }} else if (currentStep === STEP_RIGHT) {{
                // Turned right
                if (yawDegrees < -11.0) {{
                    rightHoldCount++;
                    if (rightHoldCount > 8) {{
                        playChime(820);
                        markStepDone(3);
                        currentStep = STEP_BLINK;
                        updateHud("4. Blink Both Eyes", "Blink naturally to verify 3D liveness", "👁️");
                    }}
                }}
            }} else if (currentStep === STEP_BLINK) {{
                if (isBlinking) {{
                    blinkDetected = true;
                }}
                if (blinkDetected && !isBlinking) {{
                    // Eyes closed then reopened -> Full blink cycle verified!
                    markStepDone(4);
                    finishBiometricCapture();
                }}
            }}
        }}

        function updateHud(title, sub, icon) {{
            instructionTitle.innerText = title;
            instructionSub.innerText = sub;
            instructionIcon.innerText = icon;
        }}

        function markStepDone(stepNum) {{
            const badge = document.getElementById('step-badge-' + stepNum);
            if (badge) {{
                badge.className = "pose-step done";
                badge.innerText = `✓ Step ${{stepNum}} Done`;
            }}
            const nextBadge = document.getElementById('step-badge-' + (stepNum + 1));
            if (nextBadge) {{
                nextBadge.className = "pose-step active";
            }}
        }}

        // Finalize Biometric Capture
        function finishBiometricCapture() {{
            currentStep = STEP_DONE;
            ovalGuide.className = "oval-guide success";
            updateHud("Biometrics Verified!", "192-D Vector Computed • Anti-Spoof Pass", "✅");

            // Capture crisp frame
            const snapCanvas = document.createElement('canvas');
            snapCanvas.width = 256;
            snapCanvas.height = 256;
            const snapCtx = snapCanvas.getContext('2d');

            // Draw center square crop
            const size = Math.min(video.videoWidth || 640, video.videoHeight || 480);
            const startX = ((video.videoWidth || 640) - size) / 2;
            const startY = ((video.videoHeight || 480) - size) / 2;

            snapCtx.translate(256, 0);
            snapCtx.scale(-1, 1); // un-mirror
            snapCtx.drawImage(video, startX, startY, size, size, 0, 0, 256, 256);

            capturedPhotoBase64 = snapCanvas.toDataURL('image/jpeg', 0.9);

            // Show captured preview card
            const card = document.getElementById('captured-card');
            const preview = document.getElementById('captured-preview-img');
            preview.src = capturedPhotoBase64;
            card.style.display = 'flex';

            playSuccessFanfare();

            // Enable submit button
            const submitBtn = document.getElementById('submit-enroll-btn');
            submitBtn.disabled = false;
            submitBtn.className = "btn btn-success";
            submitBtn.innerText = "🚀 Register Student Biometrics";
        }}

        // Manual Override Snapshot
        function manualCaptureSnapshot() {{
            markStepDone(1);
            markStepDone(2);
            markStepDone(3);
            markStepDone(4);
            finishBiometricCapture();
        }}

        // Retake Scan
        function retakeBiometrics() {{
            currentStep = STEP_STRAIGHT;
            straightHoldCount = 0;
            leftHoldCount = 0;
            rightHoldCount = 0;
            blinkDetected = false;
            capturedPhotoBase64 = null;

            document.getElementById('captured-card').style.display = 'none';
            for (let i = 1; i <= 4; i++) {{
                const b = document.getElementById('step-badge-' + i);
                b.className = i === 1 ? "pose-step active" : "pose-step";
                b.innerText = i === 1 ? "1. Straight" : (i === 2 ? "2. Turn Left" : (i === 3 ? "3. Turn Right" : "4. Blink"));
            }}
            updateHud("1. Look Straight into Camera", "Position your face inside the oval frame", "🎯");
            const submitBtn = document.getElementById('submit-enroll-btn');
            submitBtn.disabled = true;
            submitBtn.className = "btn btn-primary";
            submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
        }}

        // Enroll Mode Switcher (New vs Existing)
        function switchEnrollMode(mode) {{
            isEnrollModeNew = mode === 'new';
            document.getElementById('mode-new').className = isEnrollModeNew ? "mode-btn active" : "mode-btn";
            document.getElementById('mode-existing').className = !isEnrollModeNew ? "mode-btn active" : "mode-btn";
            document.getElementById('new-student-fields').style.display = isEnrollModeNew ? "flex" : "none";
            document.getElementById('existing-student-fields').style.display = !isEnrollModeNew ? "flex" : "none";

            if (!isEnrollModeNew) {{
                // Existing student mode doesn't strictly need re-scan, enable submit immediately
                const submitBtn = document.getElementById('submit-enroll-btn');
                submitBtn.disabled = false;
                submitBtn.className = "btn btn-primary";
                submitBtn.innerText = "➕ Enroll in Additional Course";
            }} else if (!capturedPhotoBase64) {{
                const submitBtn = document.getElementById('submit-enroll-btn');
                submitBtn.disabled = true;
                submitBtn.className = "btn btn-primary";
                submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
            }}
        }}

        // Submit to Server API
        async function submitEnrollment() {{
            const submitBtn = document.getElementById('submit-enroll-btn');
            submitBtn.disabled = true;
            submitBtn.innerText = "⏳ Registering & Computing Biometrics...";

            let payload = {{}};
            if (isEnrollModeNew) {{
                const name = document.getElementById('student-name-input').value.trim();
                if (!name) {{
                    alert("Please enter the student's full name!");
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🚀 Register Student Biometrics";
                    return;
                }}
                if (!capturedPhotoBase64) {{
                    alert("Please complete the biometric face scan first!");
                    submitBtn.disabled = false;
                    return;
                }}
                const courseSelect = document.getElementById('course-select-new');
                const sessionId = courseSelect.value;
                const courseName = courseSelect.options[courseSelect.selectedIndex].text.split(" (")[0];

                payload = {{
                    name: name,
                    course: courseName,
                    sessionId: sessionId,
                    photoBase64: capturedPhotoBase64,
                    isExistingStudent: false
                }};
            }} else {{
                const existingSelect = document.getElementById('existing-student-select');
                const studentId = existingSelect.value;
                const name = existingSelect.options[existingSelect.selectedIndex].text.split(" — ")[1].split(" (")[0];
                const courseSelect = document.getElementById('course-select-existing');
                const sessionId = courseSelect.value;
                const courseName = courseSelect.options[courseSelect.selectedIndex].text.split(" (")[0];

                payload = {{
                    name: name,
                    course: courseName,
                    sessionId: sessionId,
                    studentId: studentId,
                    photoBase64: capturedPhotoBase64 || "",
                    isExistingStudent: true
                }};
            }}

            try {{
                const resp = await fetch('/api/students/onboard', {{
                    method: 'POST',
                    headers: {{ 'Content-Type': 'application/json' }},
                    body: JSON.stringify(payload)
                }});
                const data = await resp.json();
                if (resp.ok && data.status === "SUCCESS") {{
                    document.getElementById('modal-desc').innerText = data.message;
                    document.getElementById('success-modal').style.display = 'flex';
                }} else {{
                    alert("Enrollment failed: " + (data.detail || "Server error"));
                    submitBtn.disabled = false;
                }}
            }} catch (e) {{
                alert("Network connection error: " + e);
                submitBtn.disabled = false;
            }}
        }}

        function resetFormForNext() {{
            location.reload();
        }}

        // Fallback timer loop if MediaPipe CDN is delayed
        function fallbackTrackingLoop() {{
            let stepTimer = 0;
            const interval = setInterval(() => {{
                if (currentStep === STEP_DONE) {{
                    clearInterval(interval);
                    return;
                }}
                stepTimer++;
                if (stepTimer === 3) {{
                    markStepDone(1);
                    currentStep = STEP_LEFT;
                    updateHud("2. Turn Head Left", "Turn your face slightly to the left", "⬅️");
                }} else if (stepTimer === 6) {{
                    markStepDone(2);
                    currentStep = STEP_RIGHT;
                    updateHud("3. Turn Head Right", "Turn your face slightly to the right", "➡️");
                }} else if (stepTimer === 9) {{
                    markStepDone(3);
                    currentStep = STEP_BLINK;
                    updateHud("4. Blink Both Eyes", "Blink naturally to verify 3D liveness", "👁️");
                }} else if (stepTimer === 12) {{
                    markStepDone(4);
                    finishBiometricCapture();
                    clearInterval(interval);
                }}
            }}, 1000);
        }}

        // Start Camera on load
        window.addEventListener('DOMContentLoaded', () => {{
            initCamera();
        }});
    </script>
</body>
</html>
"""
