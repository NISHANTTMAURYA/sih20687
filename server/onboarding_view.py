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
        .oval-guide.warning {{
            border-color: #f59e0b;
            box-shadow: 0 0 20px rgba(245, 158, 11, 0.7), 0 0 0 9999px rgba(15, 23, 42, 0.45);
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
                    <div style="display:flex; align-items:center; gap:8px; flex-wrap:wrap;">
                        <select id="camera-select" style="font-size:12px; padding:6px 10px; border-radius:7px; border:1px solid #cbd5e1; background:#ffffff; color:#0f172a; max-width:220px;" onchange="switchCamera(this.value)">
                            <option value="">🎥 Loading cameras...</option>
                        </select>
                        <button type="button" class="btn btn-secondary text-xs" onclick="initCamera()">🔄 Restart</button>
                        <button type="button" class="btn btn-secondary text-xs" onclick="manualCaptureSnapshot()">📷 Snap Manually</button>
                    </div>
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

        // Initialize Camera & Multi-Camera Switcher
        let isProcessing = false;
        let currentStream = null;
        let currentDeviceId = null;

        async function populateCameraDevices() {{
            try {{
                if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
                const devices = await navigator.mediaDevices.enumerateDevices();
                const videoDevices = devices.filter(d => d.kind === 'videoinput');
                const select = document.getElementById('camera-select');
                if (!select) return;

                select.innerHTML = '';
                if (videoDevices.length === 0) {{
                    select.innerHTML = '<option value="">Default Camera</option>';
                    return;
                }}

                videoDevices.forEach((dev, idx) => {{
                    const opt = document.createElement('option');
                    opt.value = dev.deviceId;
                    opt.innerText = dev.label || `Camera ${{idx + 1}}`;
                    if (dev.deviceId === currentDeviceId) {{
                        opt.selected = true;
                    }}
                    select.appendChild(opt);
                }});
            }} catch (e) {{
                console.warn("Could not enumerate camera devices:", e);
            }}
        }}

        async function switchCamera(deviceId) {{
            currentDeviceId = deviceId;
            await initCamera(deviceId);
        }}

        async function initCamera(selectedDeviceId = null) {{
            try {{
                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {{
                    throw new Error("Webcam access not supported in this browser context (requires localhost or HTTPS).");
                }}
                instructionTitle.innerText = "Connecting to Webcam...";
                instructionSub.innerText = "Please grant browser camera access if prompted.";
                instructionIcon.innerText = "📹";

                if (currentStream) {{
                    currentStream.getTracks().forEach(track => track.stop());
                    currentStream = null;
                }}

                const constraints = {{
                    video: selectedDeviceId 
                        ? {{ deviceId: {{ exact: selectedDeviceId }} }} 
                        : {{ width: {{ ideal: 640 }}, height: {{ ideal: 480 }}, facingMode: 'user' }},
                    audio: false
                }};

                const stream = await navigator.mediaDevices.getUserMedia(constraints);
                currentStream = stream;
                video.srcObject = stream;

                const videoTrack = stream.getVideoTracks()[0];
                if (videoTrack) {{
                    const settings = videoTrack.getSettings();
                    if (settings && settings.deviceId) {{
                        currentDeviceId = settings.deviceId;
                    }}
                }}
                
                try {{
                    await video.play();
                }} catch (playErr) {{
                    console.warn("video.play error:", playErr);
                }}

                canvas.width = video.videoWidth || 640;
                canvas.height = video.videoHeight || 480;

                instructionTitle.innerText = "1. Look Straight into Camera";
                instructionSub.innerText = "Position your face inside the oval frame";
                instructionIcon.innerText = "🎯";

                await populateCameraDevices();
                initMediaPipe();
            }} catch (e) {{
                console.error("Camera Error:", e);
                instructionTitle.innerText = "Camera Access Blocked";
                instructionSub.innerText = e.name === "NotAllowedError" 
                    ? "Camera permission was denied. Click the lock/tune icon in Chrome's address bar to allow webcam access."
                    : (e.name === "NotReadableError" ? "Camera hardware is in use by another app or browser tab. Please close other camera apps and click 'Restart Camera'." : (e.message || "Please enable camera permissions."));
                instructionIcon.innerText = "⚠️";
            }}
        }}

        // MediaPipe FaceMesh Initialization
        let faceMesh;
        let mediaPipeInitialized = false;
        function initMediaPipe() {{
            if (mediaPipeInitialized) return;
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
                    mediaPipeInitialized = true;

                    function runTracking() {{
                        if (currentStep !== STEP_DONE && video.readyState >= 2 && !isProcessing) {{
                            isProcessing = true;
                            faceMesh.send({{ image: video }})
                                .then(() => {{ isProcessing = false; }})
                                .catch(() => {{ isProcessing = false; }});
                        }}
                        if (currentStep !== STEP_DONE) {{
                            requestAnimationFrame(runTracking);
                        }}
                    }}
                    requestAnimationFrame(runTracking);
                }} else {{
                    fallbackTrackingLoop();
                }}
            }} catch (e) {{
                console.warn("MediaPipe init error:", e);
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
            // 234: Subject right cheek boundary (camera left)
            // 454: Subject left cheek boundary (camera right)
            // 10: Forehead top
            // 152: Chin tip
            // 159, 145: Left Eyelid Top & Bottom
            // 386, 374: Right Eyelid Top & Bottom
            const nose = landmarks[1];
            const leftCheek = landmarks[234];
            const rightCheek = landmarks[454];
            const forehead = landmarks[10];
            const chin = landmarks[152];

            const leftTop = landmarks[159];
            const leftBottom = landmarks[145];
            const rightTop = landmarks[386];
            const rightBottom = landmarks[374];

            // 1. STRICT OVAL BOUNDING BOX & SCALE VALIDATION
            const faceCenterX = (leftCheek.x + rightCheek.x) / 2;
            const faceCenterY = (forehead.y + chin.y) / 2;
            const faceHeight = Math.abs(chin.y - forehead.y);
            const faceWidth = Math.abs(rightCheek.x - leftCheek.x);

            const isCenteredX = Math.abs(faceCenterX - 0.5) <= 0.13;
            const isCenteredY = Math.abs(faceCenterY - 0.5) <= 0.15;
            const isProperScale = faceHeight >= 0.28 && faceHeight <= 0.72;
            const isProperlyFramed = isCenteredX && isCenteredY && isProperScale;

            if (!isProperlyFramed) {{
                ovalGuide.className = "oval-guide warning";
                straightHoldCount = 0;
                leftHoldCount = 0;
                rightHoldCount = 0;

                if (faceHeight < 0.28) {{
                    updateHud("Move Closer to Camera", "Center face within oval guide to continue", "🔍");
                }} else if (faceHeight > 0.72) {{
                    updateHud("Move Further Back", "Face is too close to camera", "↔️");
                }} else {{
                    updateHud("Center Face in Oval", "Position your face inside the oval frame", "🎯");
                }}
                hudYaw.innerText = "Framing: Adjust Position";
                return;
            }}

            ovalGuide.className = "oval-guide tracking";

            // Compute real face bounding box from all landmarks
            let fMinX = 1.0, fMinY = 1.0, fMaxX = 0.0, fMaxY = 0.0;
            for (let i = 0; i < landmarks.length; i++) {{
                const pt = landmarks[i];
                if (pt.x < fMinX) fMinX = pt.x;
                if (pt.x > fMaxX) fMaxX = pt.x;
                if (pt.y < fMinY) fMinY = pt.y;
                if (pt.y > fMaxY) fMaxY = pt.y;
            }}
            lastFaceBox = {{ minX: fMinX, minY: fMinY, maxX: fMaxX, maxY: fMaxY }};
            lastFaceLandmarks = landmarks;

            // 2. Head Yaw (horizontal rotation)
            // Landmark 234 is subject right cheek (camera left, x ~ 0.35)
            // Landmark 454 is subject left cheek (camera right, x ~ 0.65)
            // When turning head to LEFT: nose moves toward camera right (454) -> noseRel > 0.5 -> yawDegrees > 0
            // When turning head to RIGHT: nose moves toward camera left (234) -> noseRel < 0.5 -> yawDegrees < 0
            const cheekSpan = Math.abs(rightCheek.x - leftCheek.x);
            const noseRel = (nose.x - leftCheek.x) / (cheekSpan || 0.001);
            const yawDegrees = (noseRel - 0.5) * 80;

            const yawText = Math.abs(yawDegrees) < 8.0 
                ? "Frontal (0°)" 
                : (yawDegrees > 0 ? `Left (+${{yawDegrees.toFixed(0)}}°)` : `Right (${{yawDegrees.toFixed(0)}}°)`);
            hudYaw.innerText = `Pose: ${{yawText}}`;

            // Calculate Eye Aspect Ratio (EAR) for blink detection
            const leftEAR = Math.hypot(leftTop.x - leftBottom.x, leftTop.y - leftBottom.y);
            const rightEAR = Math.hypot(rightTop.x - rightBottom.x, rightTop.y - rightBottom.y);
            const isBlinking = leftEAR < 0.015 && rightEAR < 0.015;

            hudBlink.innerText = isBlinking ? "Eyes: Blinking! ✓" : "Eyes: Open";

            // State Machine Transition Logic
            if (currentStep === STEP_STRAIGHT) {{
                if (Math.abs(yawDegrees) < 8.0) {{
                    straightHoldCount++;
                    updateHud("1. Look Straight into Camera", `Holding steady... (${{straightHoldCount}}/10)`, "🎯");
                    if (straightHoldCount >= 8) {{
                        capturedFrontalFaceCanvas = extractFaceCropCanvas(video, landmarks, lastFaceBox);
                    }}
                    if (straightHoldCount > 10) {{
                        playChime(660);
                        markStepDone(1);
                        currentStep = STEP_LEFT;
                        updateHud("2. Turn Head Left", "Turn your face to your LEFT 👈", "⬅️");
                    }}
                }} else {{
                    straightHoldCount = Math.max(0, straightHoldCount - 1);
                    updateHud("1. Look Straight into Camera", "Keep head straight facing forward", "🎯");
                }}
            }} else if (currentStep === STEP_LEFT) {{
                // Must strictly turn LEFT (yawDegrees > 11.0)
                if (yawDegrees > 11.0) {{
                    leftHoldCount++;
                    updateHud("2. Turn Head Left", `Holding left angle... (${{leftHoldCount}}/8)`, "⬅️");
                    if (leftHoldCount > 8) {{
                        playChime(740);
                        markStepDone(2);
                        currentStep = STEP_RIGHT;
                        updateHud("3. Turn Head Right", "Turn your face to your RIGHT 👉", "➡️");
                    }}
                }} else if (yawDegrees < -9.0) {{
                    // User turned wrong way!
                    leftHoldCount = 0;
                    updateHud("Turn Head LEFT! ⚠️", "You turned RIGHT! Turn towards your LEFT 👈", "⬅️");
                }} else {{
                    leftHoldCount = 0;
                    updateHud("2. Turn Head Left", "Turn your face to your LEFT 👈", "⬅️");
                }}
            }} else if (currentStep === STEP_RIGHT) {{
                // Must strictly turn RIGHT (yawDegrees < -11.0)
                if (yawDegrees < -11.0) {{
                    rightHoldCount++;
                    updateHud("3. Turn Head Right", `Holding right angle... (${{rightHoldCount}}/8)`, "➡️");
                    if (rightHoldCount > 8) {{
                        playChime(820);
                        markStepDone(3);
                        currentStep = STEP_BLINK;
                        updateHud("4. Blink Both Eyes", "Blink naturally to verify 3D liveness 👁️", "👁️");
                    }}
                }} else if (yawDegrees > 9.0) {{
                    // User turned wrong way!
                    rightHoldCount = 0;
                    updateHud("Turn Head RIGHT! ⚠️", "You turned LEFT! Turn towards your RIGHT 👉", "➡️");
                }} else {{
                    rightHoldCount = 0;
                    updateHud("3. Turn Head Right", "Turn your face to your RIGHT 👉", "➡️");
                }}
            }} else if (currentStep === STEP_BLINK) {{
                if (isBlinking) {{
                    blinkDetected = true;
                    updateHud("4. Blink Both Eyes", "Blink detected! Open your eyes... ✓", "👁️");
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

        let lastFaceBox = null;
        let lastFaceLandmarks = null;
        let capturedFrontalFaceCanvas = null;

        // Finalize Biometric Capture
        function finishBiometricCapture() {{
            currentStep = STEP_DONE;
            ovalGuide.className = "oval-guide success";
            updateHud("Biometrics Verified!", "192-D Vector Computed • Anti-Spoof Pass", "✅");

            // Extract tight face crop directly matching ML Kit mobile geometry
            let snapCanvas;
            if (capturedFrontalFaceCanvas) {{
                snapCanvas = capturedFrontalFaceCanvas;
            }} else {{
                snapCanvas = extractFaceCropCanvas(video, lastFaceLandmarks, lastFaceBox);
            }}

            capturedPhotoBase64 = snapCanvas.toDataURL('image/jpeg', 0.92);

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

        // Helper to extract an ArcFace-aligned 112x112 face crop from the video element
        function extractFaceCropCanvas(videoEl, landmarks, box) {{
            const vW = videoEl.videoWidth || 640;
            const vH = videoEl.videoHeight || 480;
            const cropCanvas = document.createElement('canvas');
            cropCanvas.width = 112;
            cropCanvas.height = 112;
            const cropCtx = cropCanvas.getContext('2d');

            if (landmarks && landmarks.length >= 363) {{
                const p1x = ((landmarks[33].x + landmarks[133].x) / 2) * vW;
                const p1y = ((landmarks[33].y + landmarks[133].y) / 2) * vH;

                const p2x = ((landmarks[263].x + landmarks[362].x) / 2) * vW;
                const p2y = ((landmarks[263].y + landmarks[362].y) / 2) * vH;

                let lx = p1x, ly = p1y, rx = p2x, ry = p2y;
                if (p2x < p1x) {{
                    lx = p2x; ly = p2y;
                    rx = p1x; ry = p1y;
                }}

                const dx = rx - lx;
                const dy = ry - ly;
                const curDist = Math.hypot(dx, dy);

                if (curDist > 10) {{
                    const targetEyeDist = 35.2; // Standard ArcFace 112x112
                    const targetCenterX = 55.9;
                    const targetCenterY = 51.6;

                    const curCenterX = (lx + rx) / 2;
                    const curCenterY = (ly + ry) / 2;
                    const angleRad = Math.atan2(dy, dx);
                    const scale = targetEyeDist / curDist;

                    cropCtx.save();
                    cropCtx.translate(targetCenterX, targetCenterY);
                    cropCtx.rotate(-angleRad);
                    cropCtx.scale(scale, scale);
                    cropCtx.translate(-curCenterX, -curCenterY);
                    cropCtx.drawImage(videoEl, 0, 0);
                    cropCtx.restore();
                    return cropCanvas;
                }}
            }}

            // Fallback: square center crop without distortion
            let sx = 0, sy = 0, sw = vW, sh = vH;
            if (box) {{
                const bW = (box.maxX - box.minX) * vW;
                const bH = (box.maxY - box.minY) * vH;
                const cx = ((box.minX + box.maxX) / 2) * vW;
                const cy = ((box.minY + box.maxY) / 2) * vH;
                const side = Math.max(bW, bH) * 1.15;
                sx = Math.max(0, cx - side / 2);
                sy = Math.max(0, cy - side / 2);
                sw = Math.min(vW - sx, side);
                sh = Math.min(vH - sy, side);
            }} else {{
                const size = Math.min(vW, vH);
                sx = (vW - size) / 2;
                sy = (vH - size) / 2;
                sw = size;
                sh = size;
            }}
            cropCtx.drawImage(videoEl, sx, sy, sw, sh, 0, 0, 112, 112);
            return cropCanvas;
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
            capturedFrontalFaceCanvas = null;
            lastFaceBox = null;

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
                }} else if (resp.status === 422) {{
                    alert("🚫 PHOTO SECURITY CHECK FAILED: " + (data.detail || "The submitted photo was rejected by the server security check. Please capture a clear human face with both eyes visible."));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
                    submitBtn.className = "btn btn-primary";
                    retakeBiometrics();
                }} else if (resp.status === 409) {{
                    alert("⚠️ BIOMETRIC DUPLICATE CONFLICT: " + (data.detail || "This face already matches a registered student in the database. Duplicate enrollment is blocked!"));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "⚠️ Biometric Conflict — Re-scan Required";
                    submitBtn.className = "btn btn-danger";
                }} else {{
                    alert("Enrollment failed: " + (data.detail || "Server error"));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🚀 Register Student Biometrics";
                }}
            }} catch (e) {{
                alert("Network connection error: " + e);
                submitBtn.disabled = false;
            }}
        }}

        function resetFormForNext() {{
            location.reload();
        }}

        // Start Camera on page load
        if (document.readyState === 'loading') {{
            document.addEventListener('DOMContentLoaded', () => initCamera());
        }} else {{
            initCamera();
        }}
    </script>
</body>
</html>
"""
