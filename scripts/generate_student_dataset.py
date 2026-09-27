import json
import os
import math
import random
from PIL import Image, ImageDraw

STUDENTS_DATA = [
    {"studentId": "NCCT1001", "name": "Nishant Maurya", "rollNumber": "101", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "male"},
    {"studentId": "NCCT1002", "name": "Aarav Sharma", "rollNumber": "102", "course": "Digital Literacy", "sessions": ["DL-01", "CM-02"], "gender": "male"},
    {"studentId": "NCCT1003", "name": "Priya Patel", "rollNumber": "103", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "female"},
    {"studentId": "NCCT1004", "name": "Rohan Verma", "rollNumber": "104", "course": "Entrepreneurship Development", "sessions": ["EN-03"], "gender": "male"},
    {"studentId": "NCCT1005", "name": "Ananya Iyer", "rollNumber": "105", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "female"},
    {"studentId": "NCCT1006", "name": "Vikram Malhotra", "rollNumber": "106", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "male"},
    {"studentId": "NCCT1007", "name": "Sneha Reddy", "rollNumber": "107", "course": "Entrepreneurship Development", "sessions": ["EN-03"], "gender": "female"},
    {"studentId": "NCCT1008", "name": "Aditya Joshi", "rollNumber": "108", "course": "Agri-Cooperative Banking", "sessions": ["AB-04", "DL-01"], "gender": "male"},
    {"studentId": "NCCT1009", "name": "Tanvi Deshmukh", "rollNumber": "109", "course": "Agri-Cooperative Banking", "sessions": ["AB-04"], "gender": "female"},
    {"studentId": "NCCT1010", "name": "Rahul Nair", "rollNumber": "110", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "male"},
    {"studentId": "NCCT1011", "name": "Meera Kulkarni", "rollNumber": "111", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "female"},
    {"studentId": "NCCT1012", "name": "Siddharth Mehra", "rollNumber": "112", "course": "Entrepreneurship Development", "sessions": ["EN-03"], "gender": "male"},
    {"studentId": "NCCT1013", "name": "Pooja Gupta", "rollNumber": "113", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "female"},
    {"studentId": "NCCT1014", "name": "Arjun Singhania", "rollNumber": "114", "course": "Rural Credit & Finance", "sessions": ["RC-05", "CM-02"], "gender": "male"},
    {"studentId": "NCCT1015", "name": "Divya Menon", "rollNumber": "115", "course": "Rural Credit & Finance", "sessions": ["RC-05"], "gender": "female"},
    {"studentId": "NCCT1016", "name": "Karan Chawla", "rollNumber": "116", "course": "Agri-Cooperative Banking", "sessions": ["AB-04"], "gender": "male"},
    {"studentId": "NCCT1017", "name": "Ishita Banerjee", "rollNumber": "117", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "female"},
    {"studentId": "NCCT1018", "name": "Harshvardhan Rao", "rollNumber": "118", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "male"},
    {"studentId": "NCCT1019", "name": "Ritu Sengupta", "rollNumber": "119", "course": "Entrepreneurship Development", "sessions": ["EN-03"], "gender": "female"},
    {"studentId": "NCCT1020", "name": "Abhishek Mishra", "rollNumber": "120", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "male"},
    {"studentId": "NCCT1021", "name": "Neha Nambiar", "rollNumber": "121", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "female"},
    {"studentId": "NCCT1022", "name": "Deepak Choudhary", "rollNumber": "122", "course": "Rural Credit & Finance", "sessions": ["RC-05"], "gender": "male"},
    {"studentId": "NCCT1023", "name": "Kavita Pillai", "rollNumber": "123", "course": "Agri-Cooperative Banking", "sessions": ["AB-04"], "gender": "female"},
    {"studentId": "NCCT1024", "name": "Gaurav Bhatt", "rollNumber": "124", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "male"},
    {"studentId": "NCCT1025", "name": "Shreya Saxena", "rollNumber": "125", "course": "Entrepreneurship Development", "sessions": ["EN-03"], "gender": "female"},
    {"studentId": "NCCT1026", "name": "Manish Tiwari", "rollNumber": "126", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "male"},
    {"studentId": "NCCT1027", "name": "Sangeeta Rawat", "rollNumber": "127", "course": "Rural Credit & Finance", "sessions": ["RC-05"], "gender": "female"},
    {"studentId": "NCCT1028", "name": "Varun Kapoor", "rollNumber": "128", "course": "Agri-Cooperative Banking", "sessions": ["AB-04"], "gender": "male"},
    {"studentId": "NCCT1029", "name": "Kriti Aggarwal", "rollNumber": "129", "course": "Digital Literacy", "sessions": ["DL-01"], "gender": "female"},
    {"studentId": "NCCT1030", "name": "Yashwant Yadav", "rollNumber": "130", "course": "Cooperative Management", "sessions": ["CM-02"], "gender": "male"}
]

def generate_biometric_embedding(student_id: str, seed: int):
    random.seed(seed)
    # Generate 192-dimensional embedding with individual biometric signature
    raw = [random.gauss(0.0, 1.0) for _ in range(192)]
    # L2 normalize
    norm = math.sqrt(sum(x * x for x in raw))
    return [round(x / norm, 6) for x in raw]

def create_face_portrait(student: dict, output_path: str):
    # Render a clean, portrait image with facial geometry
    img = Image.new("RGB", (256, 256), color=(240, 243, 246))
    draw = ImageDraw.Draw(img)

    # Hair / Head background
    hair_color = (30, 25, 20) if student["gender"] == "male" else (20, 15, 15)
    draw.ellipse([48, 20, 208, 210], fill=hair_color)

    # Face Skin tone
    skin_tones = [(240, 200, 170), (225, 185, 150), (210, 170, 135), (245, 210, 185)]
    skin = skin_tones[hash(student["studentId"]) % len(skin_tones)]
    draw.ellipse([58, 48, 198, 208], fill=skin)

    # Eyes
    eye_color = (40, 25, 15)
    draw.ellipse([88, 100, 112, 118], fill=(255, 255, 255))
    draw.ellipse([94, 104, 106, 114], fill=eye_color)
    draw.ellipse([144, 100, 168, 118], fill=(255, 255, 255))
    draw.ellipse([150, 104, 162, 114], fill=eye_color)

    # Eyebrows
    draw.line([84, 94, 116, 92], fill=hair_color, width=3)
    draw.line([140, 92, 172, 94], fill=hair_color, width=3)

    # Nose
    draw.line([128, 112, 124, 142], fill=(180, 135, 105), width=2)
    draw.line([124, 142, 132, 142], fill=(180, 135, 105), width=2)

    # Smile / Lips
    draw.arc([108, 152, 148, 172], start=10, end=170, fill=(180, 70, 70), width=3)

    # Collar / Clothes
    shirt_colors = [(37, 99, 235), (16, 185, 129), (217, 119, 6), (147, 51, 234), (79, 70, 229)]
    shirt = shirt_colors[hash(student["studentId"]) % len(shirt_colors)]
    draw.polygon([(40, 256), (90, 215), (166, 215), (216, 256)], fill=shirt)

    img.save(output_path, "JPEG", quality=90)

def main():
    target_dir = os.path.abspath("d:/sih20687/app/src/main/assets/students")
    os.makedirs(target_dir, exist_ok=True)

    enriched_students = []
    for idx, s in enumerate(STUDENTS_DATA):
        emb = generate_biometric_embedding(s["studentId"], 1000 + idx * 7)
        photo_name = f"{s['studentId']}.jpg"
        photo_path = os.path.join(target_dir, photo_name)
        create_face_portrait(s, photo_path)

        enriched_students.append({
            "studentId": s["studentId"],
            "name": s["name"],
            "rollNumber": s["rollNumber"],
            "course": s["course"],
            "enrolledSessionIds": s["sessions"],
            "faceEmbedding": emb,
            "photoPath": f"students/{photo_name}"
        })

    json_path = os.path.join(target_dir, "students_dataset.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(enriched_students, f, indent=2)

    print(f"Generated {len(enriched_students)} student profiles with faces and embeddings into {target_dir}")

if __name__ == "__main__":
    main()
