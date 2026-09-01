# Readest++ — complete feature & interface inventory

A design brief for redesigning the interface. Every screen, control, and state
that exists today, plus the constraints a redesign has to respect.

**What the app is:** an offline Android book reader whose point is *narration* —
it reads books aloud with high-quality TTS voices, pre-renders that audio to disk
so playback costs no CPU and needs no network, and lets you look up or translate
any word by long-pressing it.

- Package `com.readest.multitts` (debug: `.debug`) · minSdk 24 · targetSdk 34
- Single `MainActivity` + WebView reader + bottom sheets. No fragments-as-screens,
  no navigation graph.
- Two build flavours: **standard** (uses a TTS engine installed on the phone) and
  **bundled** (ships neural voices inside the APK). Each has its own release track.
- Bilingual throughout: English + 简体中文 side by side in nearly every label.

---

## 1. Screens

There are only **two** full screens, plus 8 bottom sheets and 4 dialogs.

### 1.1 Library (home)

| Element | Behaviour |
|---|---|
| Hero header | Gradient panel: logo tile "R", app name, tagline |
| Engine chip | `MultiTTS ✓` / `Voices ✓` / `Get MultiTTS` — tap opens the engine dialog |
| Capability chips | `⚡ Offline audio · 离线音频` (tap → offline audio manager), `TXT · EPUB · MOBI · PDF` (static) |
| **Settings chip** | `⚙ Settings · 设置` → the settings hub |
| Version chip | `v1.18.0 · tap to check for update` → checks GitHub |
| Primary CTA | `＋ Import a book · 导入书籍`, full-width pill |
| Shelf header | `MY SHELF 我的书架 · <count>` + sort chip (`Last read 最近阅读 ▾`) |
| Book list | Vertical list of book cards |
| Empty state | "书架还是空的 / Import a TXT / EPUB / MOBI / PDF to start reading" |

**Book card:** format badge (EPUB indigo / TXT teal / PDF red / MOBI amber),
title (up to 2 lines, CJK-safe), `Chapter n / total · <relative time>`, a progress
bar, and a delete (trash) button.

**Sort orders:** last read · title A–Z (locale-aware collation) · recently added.

### 1.2 Reader

A WebView rendering one chapter at a time, wrapped in a top toolbar and a
floating mini-player.

**Top toolbar:** back · book title + `EPUB · <chapter title> (n/total)` ·
contents · bookmark · `Aa` display settings · speaker (narration) · **gear
(settings)**.

**Reading surface:**
- Two page modes: **paginated** (horizontal slide animation, edge-tap and swipe
  to turn, footer page counter, edge hint when a chapter boundary is reached) and
  **continuous scroll**.
- 5 reader themes: Dark · OLED (true black) · Light · Sepia · Mint.
- Font size 14–32 px, adjustable line height.
- Tap the middle to hide/show both toolbars (immersive reading).
- The sentence being narrated is highlighted live and auto-scrolled into view.
- Tap any sentence to start narration from there.
- Long-press any word → word actions sheet (word is highlighted while open).

**Floating mini-player** (bottom card, hides with the toolbars):
- Current sentence preview + audio-source badge (`⚡ Cached · 0% CPU` vs live synth)
- Chapter scrubber: drag to any sentence; a **secondary track shows how much of
  the chapter is already cached** — the single most information-dense control in
  the app
- Speed chip (tap cycles 1.0 → 1.2 → 1.5 → 2.0 → 0.8)
- Previous sentence · play/pause FAB · next sentence
- Expand button → full narration panel

---

## 2. Settings hub (new)

Reached from the ⚙ chip on the home page and the gear in the reader toolbar.
Grouped rows, each showing its **current value** as a subtitle, each opening the
panel that owns that setting.

```
Settings · 设置                          [MultiTTS ✓]
Readest++ v1.18.0

NARRATION · 朗读
  🔊  Voice & playback     microsoft_en-US-EricNeural (English) · 2.1x
  ★   Speech engine        MultiTTS Engine
  ⚡  Offline audio        11.2 GB of offline audio

LOOK-UPS · 查词
  📚  Dictionaries         Collins King Mixed · 牛津高阶英汉双解（第七版）
  🌐  Translation language English

READING · 阅读
  🎨  Display & themes     Light · 19px · scroll
  🗂   Shelf order          Last read 最近阅读

ABOUT · 关于
  ⬆️  Check for updates    v1.18.0
  🔗  Releases on GitHub   Release notes and older builds
```

---

## 3. Narration

### 3.1 Voice & playback panel

- **Language** picker (filters the voice list)
- **Speech engine** picker — every TTS engine installed on the phone, MultiTTS
  flagged with ★
- **Voice** picker → its own searchable sheet: source-filter chips, live search,
  voice rows showing name / locale / offline badge, voice count
- **Speed** 0.5–3.0× and **pitch** sliders
- A **per-book voice memory** — each book remembers the voice it was last read in
- Link out to the MultiTTS guide / download dialog

### 3.2 Offline audio cache (the app's centrepiece)

Pre-synthesizes every sentence to a `.wav` on disk so playback is pure file
reads: no network, no TTS CPU, works in airplane mode, and survives the engine
being uninstalled.

- Scope: **current chapter** or **entire book**
- Live progress bar + status line, with a **resume hint** — caching checkpoints,
  so an interrupted run picks up where it stopped rather than starting over
- Start · pause · stop · start-over · manage
- "Keep screen on while caching" toggle
- Running total (`11.2 GB cached` on the author's device — this feature gets used)
- Cache keys include voice + rate + pitch, so changing the voice doesn't play
  stale audio

**Offline audio manager sheet:** per-book rows (size, file count), delete one
book, delete everything, and **export a book's narration as `.m4a` files into
`Music/Readest++`** — a progress bar and a cancel button, since exporting a long
book takes a while.

### 3.3 Background playback

- Foreground service with a media notification: previous · play/pause · next · stop
- Full `MediaSession`: lock screen controls, Bluetooth/headset buttons, car head units
- Auto-advances sentence → chapter → next chapter
- Optional chime between chapters
- **Sleep timer:** off / 15 / 30 / 45 / 60 minutes

---

## 4. Look-ups (long-press a word)

The word actions sheet has two modes behind a segmented toggle:

**Dictionary** — offline MOBI/PRC (Kindle-format) dictionaries the user supplies.
One chip per installed dictionary; tapping a chip shows that dictionary's entry.
Renders the real entry HTML: IPA/D.J./K.K. pronunciations, part of speech,
numbered senses, examples, idioms, Chinese glosses.

**Translate** — Google translation inline, with a row of target-language chips
(14 languages) and a fallback that hands off to the Google Translate app.

Both modes also offer: **🔊 speak the word**, **copy**, **read from here**, and a
shortcut to manage dictionaries.

**Dictionary manager:** add a `.mobi`/`.prc` file, see each dictionary's entry
count and size, enable/disable, rename, delete. Importing shows visible progress
because indexing a 240,000-entry dictionary takes about a minute; the index is
built once and reused.

---

## 5. Books

- **Formats:** TXT · EPUB · MOBI/PRC · PDF
- Automatic **character-encoding detection** (GBK, Big5, UTF-8/16 …) — Chinese
  TXT files from anywhere open correctly
- Automatic **language detection** per chapter, used to pick the voice
- Chapter splitting, HTML → text extraction, sentence segmentation (CJK-aware)
- **Contents sheet** with two tabs: chapters and bookmarks
- **Bookmarks:** tap the icon to bookmark the current spot, long-press for the list
- Reading position is remembered *twice* — where the eye is and where narration is

---

## 6. Updates

- In-app "check for update" against GitHub Releases
- **Two release tracks**, and a build only ever offers updates from its own track:
  `standard` (`v1.18.0`) and `bundled` (`v1.17.0-bundled`, ships voices)
- Download → install with a progress dialog, or open the releases page

---

## 7. Design constraints a redesign must respect

1. **Bilingual labels.** Almost every string is `English · 中文`. Layouts must
   survive both, and CJK text needs 2-line titles.
2. **Two theme systems, deliberately separate.** The *app* chrome is a fixed
   Royal-Blue light palette; the *reader page* has its own 5 themes (Dark, OLED,
   Light, Sepia, Mint) defined in `reader.css` and applied to the WebView. A
   redesign that adds app-wide themes has to decide whether these two merge —
   currently a reader in OLED mode still has a white toolbar.
3. **Bottom sheets are the whole navigation model.** There are no secondary
   activities. Anything new should be a sheet or a row in the hub.
4. **The mini-player is the app's signature control** — a scrubber that shows
   cached coverage on a second track. Don't flatten it into a generic player bar.
5. **State-carrying subtitles.** Rows and chips show live values (cache size,
   voice name, entry counts). The UI is expected to answer questions without
   being opened.
6. **Long-running work is always visible** — caching, exporting, dictionary
   indexing and updating all have progress + cancel. Nothing spins silently.
7. **Offline first.** Only translation and update-checking need a network. The
   design should never imply an account, a cloud library, or a sync state.
8. **One `MainActivity`, ViewBinding, Material 3 components, no Compose.**

## 8. Current palette (`values/colors.xml`)

| Role | Hex |
|---|---|
| accent / primary | `#2563EB` |
| accent dark | `#1D4ED8` |
| accent soft / tint | `#DBEAFE` / `#EFF4FF` |
| app background | `#FAFAFA` |
| surface | `#FFFFFF` |
| surface border | `#E4E7EE` |
| text primary / secondary / tertiary | `#18181B` / `#64748B` / `#94A3B8` |
| green (offline/cached) | `#059669` |
| danger | `#EF4444` |
| format badges | EPUB `#4F46E5` · TXT `#0D9488` · PDF `#DC2626` · MOBI `#D97706` |

Reader themes: Light `#FAFAFA`, Dark `#0F172A`, OLED `#000000`,
Sepia `#F4EBD9`, Mint `#EBF3EA`.

## 9. Screen inventory for mockups

1. Library / home (populated + empty)
2. Reader — paginated, each of the 5 themes
3. Reader — toolbars hidden
4. Settings hub
5. Voice & playback panel
6. Voice picker
7. Offline audio manager
8. Word actions — dictionary mode
9. Word actions — translate mode
10. Dictionary manager (populated + empty)
11. Contents — chapters tab / bookmarks tab
12. Display settings
13. Media notification + lock screen
14. Update available dialog
