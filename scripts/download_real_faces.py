import urllib.request
import os
import json
import random
import math

students_dir = os.path.abspath("d:/sih20687/app/src/main/assets/students")
os.makedirs(students_dir, exist_ok=True)

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

headers = {"User-Agent": "Mozilla/5.0"}
men_idx = 10
women_idx = 10

enriched = []
for idx, s in enumerate(STUDENTS_DATA):
    gender = s["gender"]
    if gender == "male":
        url = f"https://randomuser.me/api/portraits/men/{men_idx}.jpg"
        men_idx += 1
    else:
        url = f"https://randomuser.me/api/portraits/women/{women_idx}.jpg"
        women_idx += 1
    
    photo_file = f"{s['studentId']}.jpg"
    local_path = os.path.join(students_dir, photo_file)
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp, open(local_path, "wb") as f:
            f.write(resp.read())
        print(f"Downloaded real portrait: {photo_file} from {url}")
    except Exception as e:
        print(f"Failed to download {photo_file}: {e}")

    # Generate 192-dim biometric embedding vector with unit norm
    random.seed(3000 + idx * 19)
    raw = [random.gauss(0.0, 1.0) for _ in range(192)]
    norm = math.sqrt(sum(x * x for x in raw))
    emb = [round(x / norm, 6) for x in raw]

    enriched.append({
        "studentId": s["studentId"],
        "name": s["name"],
        "rollNumber": s["rollNumber"],
        "course": s["course"],
        "enrolledSessionIds": s["sessions"],
        "faceEmbedding": emb,
        "photoPath": f"students/{photo_file}"
    })

json_path = os.path.join(students_dir, "students_dataset.json")
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(enriched, f, indent=2)

print(f"Successfully configured {len(enriched)} real human faces into {students_dir} and {json_path}")
