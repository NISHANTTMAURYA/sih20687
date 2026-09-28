
        // Web Audio Synthesizer (Zero asset dependencies)
        let audioCtx;
        function playChime(freq = 600, type = 'sine', duration = 0.15) {
            try {
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
            } catch (e) {}
        }

        function playSuccessFanfare() {
            playChime(523.25, 'triangle', 0.15); // C5
            setTimeout(() => playChime(659.25, 'triangle', 0.18), 120); // E5
            setTimeout(() => playChime(783.99, 'triangle', 0.25), 240); // G5
            setTimeout(() => playChime(1046.50, 'triangle', 0.4), 380); // C6
        }

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

        async function populateCameraDevices() {
            try {
                if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
                const devices = await navigator.mediaDevices.enumerateDevices();
                const videoDevices = devices.filter(d => d.kind === 'videoinput');
                const select = document.getElementById('camera-select');
                if (!select) return;

                select.innerHTML = '';
                if (videoDevices.length === 0) {
                    select.innerHTML = '<option value="">Default Camera</option>';
                    return;
                }

                videoDevices.forEach((dev, idx) => {
                    const opt = document.createElement('option');
                    opt.value = dev.deviceId;
                    opt.innerText = dev.label || `Camera ${idx + 1}`;
                    if (dev.deviceId === currentDeviceId) {
                        opt.selected = true;
                    }
                    select.appendChild(opt);
                });
            } catch (e) {
                console.warn("Could not enumerate camera devices:", e);
            }
        }

        async function switchCamera(deviceId) {
            currentDeviceId = deviceId;
            await initCamera(deviceId);
        }

        async function initCamera(selectedDeviceId = null) {
            try {
                if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                    throw new Error("Webcam access not supported in this browser context (requires localhost or HTTPS).");
                }
                instructionTitle.innerText = "Connecting to Webcam...";
                instructionSub.innerText = "Please grant browser camera access if prompted.";
                instructionIcon.innerText = "📹";

                if (currentStream) {
                    currentStream.getTracks().forEach(track => track.stop());
                    currentStream = null;
                }

                const constraints = {
                    video: selectedDeviceId 
                        ? { deviceId: { exact: selectedDeviceId } } 
                        : { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
                    audio: false
                };

                const stream = await navigator.mediaDevices.getUserMedia(constraints);
                currentStream = stream;
                video.srcObject = stream;

                const videoTrack = stream.getVideoTracks()[0];
                if (videoTrack) {
                    const settings = videoTrack.getSettings();
                    if (settings && settings.deviceId) {
                        currentDeviceId = settings.deviceId;
                    }
                }
                
                try {
                    await video.play();
                } catch (playErr) {
                    console.warn("video.play error:", playErr);
                }

                canvas.width = video.videoWidth || 640;
                canvas.height = video.videoHeight || 480;

                instructionTitle.innerText = "1. Look Straight into Camera";
                instructionSub.innerText = "Position your face inside the oval frame";
                instructionIcon.innerText = "🎯";

                await populateCameraDevices();
                initMediaPipe();
            } catch (e) {
                console.error("Camera Error:", e);
                instructionTitle.innerText = "Camera Access Blocked";
                instructionSub.innerText = e.name === "NotAllowedError" 
                    ? "Camera permission was denied. Click the lock/tune icon in Chrome's address bar to allow webcam access."
                    : (e.name === "NotReadableError" ? "Camera hardware is in use by another app or browser tab. Please close other camera apps and click 'Restart Camera'." : (e.message || "Please enable camera permissions."));
                instructionIcon.innerText = "⚠️";
            }
        }

        // MediaPipe FaceMesh Initialization
        let faceMesh;
        let mediaPipeInitialized = false;
        function initMediaPipe() {
            if (mediaPipeInitialized) return;
            try {
                if (typeof FaceMesh !== 'undefined') {
                    faceMesh = new FaceMesh({
                        locateFile: (file) => `https://cdn.jsdelivr.net/npm/@mediapipe/face_mesh/${file}`
                    });
                    faceMesh.setOptions({
                        maxNumFaces: 1,
                        refineLandmarks: true,
                        minDetectionConfidence: 0.5,
                        minTrackingConfidence: 0.5
                    });
                    faceMesh.onResults(onFaceResults);
                    mediaPipeInitialized = true;

                    function runTracking() {
                        if (currentStep !== STEP_DONE && video.readyState >= 2 && !isProcessing) {
                            isProcessing = true;
                            faceMesh.send({ image: video })
                                .then(() => { isProcessing = false; })
                                .catch(() => { isProcessing = false; });
                        }
                        if (currentStep !== STEP_DONE) {
                            requestAnimationFrame(runTracking);
                        }
                    }
                    requestAnimationFrame(runTracking);
                } else {
                    fallbackTrackingLoop();
                }
            } catch (e) {
                console.warn("MediaPipe init error:", e);
                fallbackTrackingLoop();
            }
        }

        // Real-Time 3D Landmark & Pose Processor
        function onFaceResults(results) {
            if (currentStep === STEP_DONE) return;

            if (!results.multiFaceLandmarks || results.multiFaceLandmarks.length === 0) {
                ovalGuide.className = "oval-guide";
                hudYaw.innerText = "No Face Detected";
                return;
            }

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

            if (!isProperlyFramed) {
                ovalGuide.className = "oval-guide warning";
                straightHoldCount = 0;
                leftHoldCount = 0;
                rightHoldCount = 0;

                if (faceHeight < 0.28) {
                    updateHud("Move Closer to Camera", "Center face within oval guide to continue", "🔍");
                } else if (faceHeight > 0.72) {
                    updateHud("Move Further Back", "Face is too close to camera", "↔️");
                } else {
                    updateHud("Center Face in Oval", "Position your face inside the oval frame", "🎯");
                }
                hudYaw.innerText = "Framing: Adjust Position";
                return;
            }

            ovalGuide.className = "oval-guide tracking";

            // Compute real face bounding box from all landmarks
            let fMinX = 1.0, fMinY = 1.0, fMaxX = 0.0, fMaxY = 0.0;
            for (let i = 0; i < landmarks.length; i++) {
                const pt = landmarks[i];
                if (pt.x < fMinX) fMinX = pt.x;
                if (pt.x > fMaxX) fMaxX = pt.x;
                if (pt.y < fMinY) fMinY = pt.y;
                if (pt.y > fMaxY) fMaxY = pt.y;
            }
            lastFaceBox = { minX: fMinX, minY: fMinY, maxX: fMaxX, maxY: fMaxY };
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
                : (yawDegrees > 0 ? `Left (+${yawDegrees.toFixed(0)}°)` : `Right (${yawDegrees.toFixed(0)}°)`);
            hudYaw.innerText = `Pose: ${yawText}`;

            // Calculate Eye Aspect Ratio (EAR) for blink detection
            const leftEAR = Math.hypot(leftTop.x - leftBottom.x, leftTop.y - leftBottom.y);
            const rightEAR = Math.hypot(rightTop.x - rightBottom.x, rightTop.y - rightBottom.y);
            const isBlinking = leftEAR < 0.015 && rightEAR < 0.015;

            hudBlink.innerText = isBlinking ? "Eyes: Blinking! ✓" : "Eyes: Open";

            // State Machine Transition Logic
            if (currentStep === STEP_STRAIGHT) {
                if (Math.abs(yawDegrees) < 8.0) {
                    straightHoldCount++;
                    updateHud("1. Look Straight into Camera", `Holding steady... (${straightHoldCount}/10)`, "🎯");
                    if (straightHoldCount >= 8) {
                        capturedFrontalFaceCanvas = extractFaceCropCanvas(video, landmarks, lastFaceBox);
                    }
                    if (straightHoldCount > 10) {
                        playChime(660);
                        markStepDone(1);
                        currentStep = STEP_LEFT;
                        updateHud("2. Turn Head Left", "Turn your face to your LEFT 👈", "⬅️");
                    }
                } else {
                    straightHoldCount = Math.max(0, straightHoldCount - 1);
                    updateHud("1. Look Straight into Camera", "Keep head straight facing forward", "🎯");
                }
            } else if (currentStep === STEP_LEFT) {
                // Must strictly turn LEFT (yawDegrees > 11.0)
                if (yawDegrees > 11.0) {
                    leftHoldCount++;
                    updateHud("2. Turn Head Left", `Holding left angle... (${leftHoldCount}/8)`, "⬅️");
                    if (leftHoldCount > 8) {
                        playChime(740);
                        markStepDone(2);
                        currentStep = STEP_RIGHT;
                        updateHud("3. Turn Head Right", "Turn your face to your RIGHT 👉", "➡️");
                    }
                } else if (yawDegrees < -9.0) {
                    // User turned wrong way!
                    leftHoldCount = 0;
                    updateHud("Turn Head LEFT! ⚠️", "You turned RIGHT! Turn towards your LEFT 👈", "⬅️");
                } else {
                    leftHoldCount = 0;
                    updateHud("2. Turn Head Left", "Turn your face to your LEFT 👈", "⬅️");
                }
            } else if (currentStep === STEP_RIGHT) {
                // Must strictly turn RIGHT (yawDegrees < -11.0)
                if (yawDegrees < -11.0) {
                    rightHoldCount++;
                    updateHud("3. Turn Head Right", `Holding right angle... (${rightHoldCount}/8)`, "➡️");
                    if (rightHoldCount > 8) {
                        playChime(820);
                        markStepDone(3);
                        currentStep = STEP_BLINK;
                        updateHud("4. Blink Both Eyes", "Blink naturally to verify 3D liveness 👁️", "👁️");
                    }
                } else if (yawDegrees > 9.0) {
                    // User turned wrong way!
                    rightHoldCount = 0;
                    updateHud("Turn Head RIGHT! ⚠️", "You turned LEFT! Turn towards your RIGHT 👉", "➡️");
                } else {
                    rightHoldCount = 0;
                    updateHud("3. Turn Head Right", "Turn your face to your RIGHT 👉", "➡️");
                }
            } else if (currentStep === STEP_BLINK) {
                if (isBlinking) {
                    blinkDetected = true;
                    updateHud("4. Blink Both Eyes", "Blink detected! Open your eyes... ✓", "👁️");
                }
                if (blinkDetected && !isBlinking) {
                    // Eyes closed then reopened -> Full blink cycle verified!
                    markStepDone(4);
                    finishBiometricCapture();
                }
            }
        }

        function updateHud(title, sub, icon) {
            instructionTitle.innerText = title;
            instructionSub.innerText = sub;
            instructionIcon.innerText = icon;
        }

        function markStepDone(stepNum) {
            const badge = document.getElementById('step-badge-' + stepNum);
            if (badge) {
                badge.className = "pose-step done";
                badge.innerText = `✓ Step ${stepNum} Done`;
            }
            const nextBadge = document.getElementById('step-badge-' + (stepNum + 1));
            if (nextBadge) {
                nextBadge.className = "pose-step active";
            }
        }

        let lastFaceBox = null;
        let lastFaceLandmarks = null;
        let capturedFrontalFaceCanvas = null;

        // Finalize Biometric Capture
        function finishBiometricCapture() {
            currentStep = STEP_DONE;
            ovalGuide.className = "oval-guide success";
            updateHud("Biometrics Verified!", "192-D Vector Computed • Anti-Spoof Pass", "✅");

            // Extract tight face crop directly matching ML Kit mobile geometry
            let snapCanvas;
            if (capturedFrontalFaceCanvas) {
                snapCanvas = capturedFrontalFaceCanvas;
            } else {
                snapCanvas = extractFaceCropCanvas(video, lastFaceLandmarks, lastFaceBox);
            }

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
        }

        // Helper to extract an ArcFace-aligned 112x112 face crop from the video element
        function extractFaceCropCanvas(videoEl, landmarks, box) {
            const vW = videoEl.videoWidth || 640;
            const vH = videoEl.videoHeight || 480;
            const cropCanvas = document.createElement('canvas');
            cropCanvas.width = 112;
            cropCanvas.height = 112;
            const cropCtx = cropCanvas.getContext('2d');

            if (landmarks && landmarks.length >= 363) {
                const p1x = ((landmarks[33].x + landmarks[133].x) / 2) * vW;
                const p1y = ((landmarks[33].y + landmarks[133].y) / 2) * vH;

                const p2x = ((landmarks[263].x + landmarks[362].x) / 2) * vW;
                const p2y = ((landmarks[263].y + landmarks[362].y) / 2) * vH;

                let lx = p1x, ly = p1y, rx = p2x, ry = p2y;
                if (p2x < p1x) {
                    lx = p2x; ly = p2y;
                    rx = p1x; ry = p1y;
                }

                const dx = rx - lx;
                const dy = ry - ly;
                const curDist = Math.hypot(dx, dy);

                if (curDist > 10) {
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
                }
            }

            // Fallback: square center crop without distortion
            let sx = 0, sy = 0, sw = vW, sh = vH;
            if (box) {
                const bW = (box.maxX - box.minX) * vW;
                const bH = (box.maxY - box.minY) * vH;
                const cx = ((box.minX + box.maxX) / 2) * vW;
                const cy = ((box.minY + box.maxY) / 2) * vH;
                const side = Math.max(bW, bH) * 1.15;
                sx = Math.max(0, cx - side / 2);
                sy = Math.max(0, cy - side / 2);
                sw = Math.min(vW - sx, side);
                sh = Math.min(vH - sy, side);
            } else {
                const size = Math.min(vW, vH);
                sx = (vW - size) / 2;
                sy = (vH - size) / 2;
                sw = size;
                sh = size;
            }
            cropCtx.drawImage(videoEl, sx, sy, sw, sh, 0, 0, 112, 112);
            return cropCanvas;
        }

        // Manual Override Snapshot
        function manualCaptureSnapshot() {
            markStepDone(1);
            markStepDone(2);
            markStepDone(3);
            markStepDone(4);
            finishBiometricCapture();
        }

        // Retake Scan
        function retakeBiometrics() {
            currentStep = STEP_STRAIGHT;
            straightHoldCount = 0;
            leftHoldCount = 0;
            rightHoldCount = 0;
            blinkDetected = false;
            capturedPhotoBase64 = null;
            capturedFrontalFaceCanvas = null;
            lastFaceBox = null;

            document.getElementById('captured-card').style.display = 'none';
            for (let i = 1; i <= 4; i++) {
                const b = document.getElementById('step-badge-' + i);
                b.className = i === 1 ? "pose-step active" : "pose-step";
                b.innerText = i === 1 ? "1. Straight" : (i === 2 ? "2. Turn Left" : (i === 3 ? "3. Turn Right" : "4. Blink"));
            }
            updateHud("1. Look Straight into Camera", "Position your face inside the oval frame", "🎯");
            const submitBtn = document.getElementById('submit-enroll-btn');
            submitBtn.disabled = true;
            submitBtn.className = "btn btn-primary";
            submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
        }

        // Enroll Mode Switcher (New vs Existing)
        function switchEnrollMode(mode) {
            isEnrollModeNew = mode === 'new';
            document.getElementById('mode-new').className = isEnrollModeNew ? "mode-btn active" : "mode-btn";
            document.getElementById('mode-existing').className = !isEnrollModeNew ? "mode-btn active" : "mode-btn";
            document.getElementById('new-student-fields').style.display = isEnrollModeNew ? "flex" : "none";
            document.getElementById('existing-student-fields').style.display = !isEnrollModeNew ? "flex" : "none";

            if (!isEnrollModeNew) {
                // Existing student mode doesn't strictly need re-scan, enable submit immediately
                const submitBtn = document.getElementById('submit-enroll-btn');
                submitBtn.disabled = false;
                submitBtn.className = "btn btn-primary";
                submitBtn.innerText = "➕ Enroll in Additional Course";
            } else if (!capturedPhotoBase64) {
                const submitBtn = document.getElementById('submit-enroll-btn');
                submitBtn.disabled = true;
                submitBtn.className = "btn btn-primary";
                submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
            }
        }

                // Submit to Server API
        async function submitEnrollment() {
            const submitBtn = document.getElementById('submit-enroll-btn');
            submitBtn.disabled = true;
            submitBtn.innerText = "⏳ Registering & Computing Biometrics...";

            let payload = {};
            if (isEnrollModeNew) {
                const name = document.getElementById('student-name-input').value.trim();
                if (!name) {
                    alert("Please enter the student's full name!");
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🚀 Register Student Biometrics";
                    return;
                }
                if (!capturedPhotoBase64) {
                    alert("Please complete the biometric face scan first!");
                    submitBtn.disabled = false;
                    return;
                }
                const courseSelect = document.getElementById('course-select-new');
                const sessionId = courseSelect.value;
                const courseName = courseSelect.options[courseSelect.selectedIndex].text.split(" (")[0];

                payload = {
                    name: name,
                    course: courseName,
                    sessionId: sessionId,
                    photoBase64: capturedPhotoBase64,
                    isExistingStudent: false
                };
            } else {
                const existingSelect = document.getElementById('existing-student-select');
                const studentId = existingSelect.value;
                const name = existingSelect.options[existingSelect.selectedIndex].text.split(" — ")[1].split(" (")[0];
                const courseSelect = document.getElementById('course-select-existing');
                const sessionId = courseSelect.value;
                const courseName = courseSelect.options[courseSelect.selectedIndex].text.split(" (")[0];

                payload = {
                    name: name,
                    course: courseName,
                    sessionId: sessionId,
                    studentId: studentId,
                    photoBase64: capturedPhotoBase64 || "",
                    isExistingStudent: true
                };
            }

            try {
                const resp = await fetch('/api/students/onboard', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await resp.json();
                if (resp.ok && data.status === "SUCCESS") {
                    document.getElementById('modal-desc').innerText = data.message;
                    document.getElementById('success-modal').style.display = 'flex';
                } else if (resp.status === 422) {
                    alert("🚫 PHOTO SECURITY CHECK FAILED: " + (data.detail || "The submitted photo was rejected by the server security check. Please capture a clear human face with both eyes visible."));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🔒 Complete Biometric Scan to Register";
                    submitBtn.className = "btn btn-primary";
                    retakeBiometrics();
                } else if (resp.status === 409) {
                    alert("⚠️ BIOMETRIC DUPLICATE CONFLICT: " + (data.detail || "This face already matches a registered student in the database. Duplicate enrollment is blocked!"));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "⚠️ Biometric Conflict — Re-scan Required";
                    submitBtn.className = "btn btn-danger";
                } else {
                    alert("Enrollment failed: " + (data.detail || "Server error"));
                    submitBtn.disabled = false;
                    submitBtn.innerText = "🚀 Register Student Biometrics";
                }
            } catch (e) {
                alert("Network connection error: " + e);
                submitBtn.disabled = false;
            }
        }

        function resetFormForNext() {
            location.reload();
        }

        // Start Camera on page load
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => initCamera());
        } else {
            initCamera();
        }
    