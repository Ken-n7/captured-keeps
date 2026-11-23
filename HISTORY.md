# Captured Keeps — Development History

> Timeline of everything done on the project, newest at the bottom.
> Decisions and their reasons live in `PROJECT_NOTES.md`; this file records *what was done and when*.

---

## 2026-11-23 — Spec review and planning

- Reviewed `Captured_Keeps_Complete_App_Specification.docx` (v1.0, 22 Nov 2026).
- Raised clarifying questions, objections, and suggestions on the spec.
- Confirmed hardware: Galaxy Tab A11 (front camera), 80mm ESC/POS thermal printer "POS Cash Till Register 30 Pro" (Bluetooth + USB). Hardware still in delivery.
- Chose Kotlin + Jetpack Compose over Flutter.
- Simplified scope:
  - Roles → Owner + Attendant.
  - Event states → Draft, Live, Paused, Completed, Cancelled (+ hidden flag).
  - Separate session / payment / print / sync states instead of one combined status.
  - Append-only financial records, integer centavos.
  - Offline-first; dropped "Online Only" profile.
  - Back-office features (inventory, expenses, staff, bookings, CRM, reports) moved to a future admin web app.
- Defined payment flow: all paid sessions go through QR into the business wallet (customer self-pay, or staff pays QR for cash customers); staff PIN confirm + reference no.; payment slip printed as "Acknowledgment — not an official receipt/invoice".
- Defined v1 MVP and build order.
- Defined customizable strip layout (settings + presets, not a drag editor).
- Created `PROJECT_NOTES.md` (living decision document).
- Produced a sample 576px thermal strip (generated test images) to preview layout and dithering.
- Created `HISTORY.md` (this file).
- Checked dev laptop: 5 GB RAM, 15 GB free disk → emulator too heavy alongside builds. Planned JVM tests + screenshot tests + spare-phone testing instead (PROJECT_NOTES §5.2).
- Building not started — waiting for owner's go-ahead.

## 2026-11-23 — Build started: project setup + print engine (build order steps 1–2)

- Owner gave the go-ahead to start building.
- `git init` at repo root; `.gitignore` excludes build output and private sample photos (`samples/photos/`).
- Created `android/` Gradle project (reuses versions already in the laptop's Gradle cache — no big downloads):
  Gradle 8.14, Android Gradle Plugin 8.13.1, Kotlin 2.2.20, Compose BOM 2024.12.01, compileSdk/targetSdk 35, minSdk 26.
- Modules:
  - `core` — pure Kotlin, no Android; tested on the laptop JVM.
  - `app` — Android shell: brand theme (ivory/charcoal/beige/taupe + status colors, large tablet type) and a wordmark placeholder screen.
- Print engine in `core/print`:
  - `GrayImage` (center-crop + box-average resize, ARGB → grayscale) and `BitImage` (1-bit dots).
  - `PrintTuning` + `tuned()`: auto-levels, gamma, contrast, brightness, with range validation.
  - Dithering: Floyd–Steinberg, Atkinson, threshold.
  - `EscPos`: GS v 0 raster encoder, sent in bands (band height = calibration knob for the real printer), reset/feed/cut, rejects images wider than 576 dots.
  - `StripLayout`: customizable layout data (1–4 shots, landscape/square/portrait, header/event/date/time/number toggles, footer, margins, gap, tuning) with validation; presets Classic 3, Square 4, Single portrait.
  - `plan()`: pure layout math → positioned rules/text/photos, drawn by any platform.
- Tests: 9 unit tests (validation, crop, dithering, tuning, ESC/POS bytes, banding, layout overlap) + sample strip renderer → all passing.
- Sample strips: `./gradlew :core:test` writes `android/core/build/samples/*.png` using photos from `samples/photos/` (synthetic images if empty).
- Debug APK builds (`./gradlew :app:assembleDebug`).
- Not yet: logo image + PNG overlay on strips (waiting for real files), Android Canvas strip renderer, virtual printer screen.
