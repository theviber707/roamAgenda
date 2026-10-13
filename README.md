<!--
SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Roam Agenda

A minimal, native Android application built with **Kotlin** and **Jetpack Compose (Material 3)** for managing Org Mode and Org-Roam agenda tasks synced to your Android device via Syncthing.

---

## 📲 Installation

Roam Agenda is available through **The Viber** F-Droid repository:

1. **Repository URL**:
   ```text
   https://theviber707.github.io/fdroid-repo/repo
   ```
2. **Signing Key Fingerprint (SHA-256)**:
   ```text
   02 34 BA 18 CD 1E 75 21 83 A6 C0 B6 F5 36 2D F8 E5 7C 0D BD 35 59 31 E4 DA 47 7A AB 19 26 74 3C
   ```
   *(Or scan the QR code on the [Repository Page](https://theviber707.github.io/fdroid-repo/))*.

Standalone APKs are also provided on each [GitHub Release](https://github.com/theviber707/roamAgenda/releases).

---

## 🎯 Context & Motivation

When managing notes in Emacs using Org Mode and Org-Roam, desktop agenda workflows often use selective filtering. Roam Agenda implements this specific agenda setup for mobile:

1. **Agenda Tag Filter**: Recursively scans `.org` files, filtering only those tagged with `:agenda:` in their `#+filetags:` header (e.g. `#+filetags: :agenda:` or `#+filetags: :project:agenda:work:`).
2. **Prioritized Tasks**: Extracts only headlines marked as `TODO` or `NEXT` that carry an explicit priority cookie (`[#A]`, `[#B]`, or `[#C]`).
3. **In-Place Disk Updates**: When you check off a task, Roam Agenda directly updates the `.org` file on disk via standard `java.io.File` paths—bypassing Storage Access Framework (SAF) URI overhead through `MANAGE_EXTERNAL_STORAGE` ("All Files Access").
4. **Org Mode Repeater & Habit Support**: Supports recurring tasks (e.g., `SCHEDULED: <2026-10-19 Mon ++1w>`), advancing the scheduled timestamp, updating `:LAST_REPEAT:` in `:PROPERTIES:`, and recording state changes in `:LOGBOOK:` while keeping the headline as `TODO`.

---

## ✨ Features

- **Material 3 Design**: Clean interface with dynamic color theming, dark theme support, and edge-to-edge styling.
- **Priority-Coded Sections**: Tasks grouped by Priority (`[#A]`, `[#B]`, `[#C]`) with distinct color accents (red tint for #A, blue for #B, green for #C).
- **Collapsible Priority Sections**: Tap any priority header row to collapse or expand its task list, with smooth animated arrow indicators and task count badges.
- **Task Metadata**:
  - Origin note title badge (from `#+title:` or filename).
  - Keyword indicator (`TODO` vs `NEXT`).
  - Scheduled date badges (`📅 Today`, `⚠️ Overdue`, or `📅 Due: <date>`).
  - Repeater indicator (e.g. `🔁 ++1w`, `🔁 +1d`).
- **Interactive Steps & Checklists**: Mark off checklist items (`- [ ]`) directly on the card with live progress bar and Org headline progress cookie updates (`[1/3]`).
- **Org Mode Time Clocking (`org-clock`)**:
  - Clock in / out directly on any task card with active timer.
  - Persistent active clock banner with live duration and clock cancel support.
  - Automatically records `CLOCK: [start]--[end] => H:MM` in `:LOGBOOK:`.
  - Enforces Org Mode single active clock invariant and auto-clocks out on completion.
- **Due vs. All Filter**: Toggle between actionable tasks (due today, overdue, or unscheduled) and all tasks (including future scheduled dates) via the top bar Calendar icon.
- **Full Org Mode Recurrence**:
  - Supports cumulative (`+`), catch-up (`++`), and restart (`.+`) repeaters.
  - Automatically updates `:LAST_REPEAT: [YYYY-MM-DD Day HH:MM]` under `:PROPERTIES:`.
  - Appends `- State "DONE" from "TODO" [timestamp]` to `:LOGBOOK:`.
- **Lossless Undo**: Snackbar with "Undo" restores the exact prior file contents on disk.
- **Internationalization (i18n)**: Fully localized in American English (`en-US`), Brazilian Portuguese (`pt-BR`), Mexican Spanish (`es-MX`), and Russian (`ru`), supporting Android 13+ per-app language preferences.
- **Configurable Storage Directory**: Persistent root path configuration via AndroidX DataStore (defaults to `/sdcard/Org/roam`).
- **Seamless Permission Handling**: Detects "All Files Access" state on launch and automatically resumes scanning when returning from System Settings.

---

## 🏗️ Architecture & Tech Stack

- **Target SDK**: 35 (Android 15), **Min SDK**: 26 (Android 8.0)
- **UI Toolkit**: Jetpack Compose with Material 3 and Compose BOM (`2024.12.01`)
- **Architecture**: Kotlin Coroutines + StateFlow / AndroidViewModel (`Dispatchers.IO`)
- **Persistence**: AndroidX DataStore Preferences
- **Parsing Engine**: Fast pure-Kotlin regex scanner and in-place file rewriter
- **Build System**: Gradle 8.14.4 + Android Gradle Plugin 8.7.3 + Kotlin 2.0.21
- **Dev Environment**: Nix Flake with reproducible Android SDK, JDK 21, and emulator support

---

## 🚀 Getting Started

### Prerequisites

You can use standard Android SDK / command-line tools or the included **Nix** dev shell.

#### With Nix (Recommended)
```bash
# Enter development shell (or use direnv: direnv allow)
nix develop
```

---

### Building & Testing

```bash
# Run unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

---

### Running on Emulator

1. **Launch Emulator**:
   ```bash
   ./scripts/run-avd.sh
   # or
   emulator -avd test-device -no-boot-anim -gpu host -qemu -enable-kvm
   ```

2. **Install and Launch App**:
   ```bash
   ./gradlew installDebug
   # or
   adb install -r app/build/outputs/apk/debug/app-debug.apk

   adb shell am start -n com.roamagenda/.MainActivity
   ```

3. **Grant Storage Permission**:
   When prompted on first launch, tap **Grant All Files Access** to toggle it in Settings, or grant it immediately via `adb`:
   ```bash
   adb shell appops set com.roamagenda MANAGE_EXTERNAL_STORAGE allow
   ```

---

## 📝 Sample Note Format

Roam Agenda expects notes in your configured directory (e.g. `/sdcard/Org/roam/`) tagged with `:agenda:`:

```org
#+title: Work & Projects
#+filetags: :agenda:work:

* TODO [#A] Deploy Roam Agenda to test device
* TODO [#B] Review team pull request
* NEXT [#C] Organize project documentation

* TODO [#C] Blood Meridian
SCHEDULED: <2026-10-12 Mon ++1w>
:PROPERTIES:
:LAST_REPEAT: [2026-10-05 Mon 11:45]
:END:
:LOGBOOK:
- State "DONE"       from "TODO"       [2026-10-05 Mon 11:45]
:END:

* TODO Unprioritized note (ignored by agenda)
```

- Checking a standard task (`TODO` / `NEXT`) rewrites the keyword in-place to `DONE`.
- Checking a repeating task (`++1w`) advances `SCHEDULED: <2026-10-19 Mon ++1w>`, updates `:LAST_REPEAT:`, appends to `:LOGBOOK:`, and leaves the headline as `TODO`.

---

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0 or later** ([GPL-3.0-or-later](LICENSES/GPL-3.0-or-later.txt)).
This project is compliant with the **REUSE Specification 3.3**.
