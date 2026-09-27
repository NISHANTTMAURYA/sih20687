# NCCT AI Biometric Model Assets
This directory holds pre-trained on-device models:
- mobile_face_net.tflite: MobileFaceNet / ArcFace 112x112 -> 128-dim normalized embedding.
- minifasnet_v2.tflite: MiniFASNetV2 anti-spoofing presentation attack detection.
- phone_detector.tflite: MobileNet SSD / YOLO-Nano screen & handheld phone detection.

The application automatically loads these models when present in this folder, and uses mathematical edge-texture/Laplacian biometric fallbacks if the binary files are absent.
