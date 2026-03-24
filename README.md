<p align="center">
  <img width="250" alt="lyrisync logo" src="app/src/main/ic_launcher-playstore.png">
</p>

# LyriSync Android
Live translate each sentence, stream definitions of each phrase, and filter definitions (with an Anki Deck) for a streaming Spotify song!

<p align="center">
  <img width="288" height="640" alt="image" src="https://github.com/user-attachments/assets/6a724fd0-61a9-4030-ac02-9c444f89c113" />
  <img width="288" height="640" alt="image" src="https://github.com/user-attachments/assets/fe376142-7e0b-44e6-b280-758823ccae1f" />
</p>

## Features
- Maxmatch search (try to match all words in a sentence, if miss, try a lesser amount) to get the maximum possible accuracy that the database can affrod to provide instead of exact matching (and missing) hiragana / katakana words. Also suffixes to kanji influence the meaning, so this approach is required.
For this reason, db is pushed to RAM to allow fast querying
- Single page UI (activity_main.xml)

## Planned features / bugs lol:
- background using colors from album art?
- send word to anki w time in song n all metadata
- Add a pop up to inform user to open spotify when null error (spotify kills self)
- link an Anki deck and only show words in jisho tab that arent in anki deck
- streaming jisho in bg
- nicer resync (manual -> sync) animation and not a cut
- Loading circle for whenever loading: https://m3.material.io/components/progress-indicators/overview
-x underlining parts of sentences
-x auto mode, kanji only mode
  -x kanji only is just kanji in jisho
  -x auto is using many words in one token
-x settings page to change to furigana, kata or hirigana under.
-x ui doesnt go to neutral state if going from song with lyric to no lyric. it just keeps old page. must update
-x big box around one line of words to group those
-x add a "non Japanese threshold" where if > than amount of not japanese in a song, treat as non translation

## License
Project uses GNU General Public License v3.0
