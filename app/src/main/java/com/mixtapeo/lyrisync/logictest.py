import sqlite3
import re

# Connect to your local database file
conn = sqlite3.connect(r'C:\Users\aniru\AndroidStudioProjects\LyriSync\app\src\main\assets\databases\jmdict.db')
cursor = conn.cursor()

# The Regex filters from your Android app
jp_regex = re.compile(r'[\u3040-\u30ff\u4e00-\u9faf]')
single_kana_regex = re.compile(r'^[\u3040-\u30ff]$')

def get_definition(query):
    cursor.execute("SELECT kanji, reading FROM dictionary WHERE kanji = ? OR reading = ? LIMIT 1", (query, query))
    return cursor.fetchone()

def extract_words(text):
    found_words = []
    i = 0
    max_length = 10

    while i < len(text):
        match_found = False
        current_max = min(len(text) - i, max_length)

        for length in range(current_max, 0, -1):
            substring = text[i:i+length]

            # Fast Fail 1: No Japanese characters
            if not jp_regex.search(substring):
                continue

            # Fast Fail 2: Single Kana particle
            if length == 1 and single_kana_regex.match(substring):
                continue

            # DB Lookup
            entry = get_definition(substring)
            if entry:
                found_words.append({
                    "matched_text": substring,
                    "db_kanji": entry[0],
                    "db_reading": entry[1]
                })
                i += length
                match_found = True
                break

        if not match_found:
            i += 1

    return found_words

# --- TEST YOUR FAILING LINES HERE ---
test_lines = [
    "あっという間に君を追い込む",
    "小さな泡きらり" # Put your failing lyric here!
]

print("--- LYRISYNC PARSER TEST ---")
for line in test_lines:
    print(f"\nLyric: {line}")
    results = extract_words(line)
    if not results:
        print("  ❌ No words detected!")
    for res in results:
        print(f"  ✅ Found: {res['matched_text']} (DB entry: {res['db_kanji']} / {res['db_reading']})")

conn.close()