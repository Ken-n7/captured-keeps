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

## 2026-11-23 — First commit + business rules (build order step 3a)

- Extended `.gitignore` (editors, logs, signing keys, `.env`). Ran tests + build, then made the first commit `8258a92` on `main` (no AI attribution).
- Business rules from PROJECT_NOTES §4 as pure Kotlin in `core/domain` (tested on the laptop, no device):
  - `Centavos`: integer money, parses staff input ("150.50", "₱1,500"), rejects negatives, >2 decimals, typo-sized amounts.
  - `Event`: validation, Draft → Live ⇄ Paused → Completed / Draft → Cancelled, go-live check (blockers vs warnings), one live event per booth, "Scheduled" label, hide (archive), read-only when finished, edits produce audit entries (field, old → new).
  - `Session`: start rules (live event, offered package, enabled layout, paid needs confirmed payment), package/layout snapshot, capture → review → retake (limited) → print → complete, fail/cancel with reason.
  - `PrintJob`: queued → sending → done / failed → retry; unknown after crash resolved by attendant; reprint = new job with reason + operator, never charges.
  - `recoverAfterRestart`: cancels interrupted sessions (payment becomes reusable), marks mid-send prints "unknown", never auto-resends.
  - `Ledger` (append-only): sale (customer QR / cash via staff, tendered + change, QR reference format), authorize (one payment → one session), void (before capture only), refund (owner only, capped, partial allowed), summary (gross, refunds, net, per path, cash held per staff, sales missing a reference).
  - `storageStatus` (warn < 2 GB, block < 500 MB), `SyncState.canDeleteLocally`.
- Tests: 26 passing (16 new domain tests).
- Next (3b): save these records on the device (Room database).
- Created `DEVICE_TESTING.md`: checklist of everything that needs a real phone, the Tab A11, the printer, or a pilot event to verify.

## 2026-11-23 — On-device database (build order step 3b)

- Committed step 3a as `a2ed855` (no AI attribution).
- New `android/data` module: Room 2.8.3 + bundled SQLite (same SQLite on laptop and tablet), plain JVM so it's tested on the laptop.
  - Tables: staff, events, packages, sessions (with package snapshot), print_jobs, ledger (sales/voids/refunds), audit.
  - Database-level guards: ledger and audit are append-only (UPDATE/DELETE blocked by triggers); a unique index stops one payment unlocking two active sessions even if app code had a bug.
  - `Store`: the app's single read/write entry point — audited event edits in one transaction, ledger append/load, session start re-checks the payment inside a transaction, print jobs, restart recovery.
  - Schema exported to `android/data/schemas/` (needed to write migrations later without losing data).
- Tests: 32 passing (6 new database tests: round-trips, audit, append-only, one-payment-one-session, restart recovery, survives close/reopen). Debug APK still builds.
- Not yet: sync columns (added with a migration when cloud sync is built), custom strip layouts saved in the database (presets live in code for now), PIN storage (with the login screens).

## 2026-11-23 — Android strip + virtual printer (build order step 4)

- Committed step 3b as `f709cab`.
- `AndroidStrip`: draws the same `StripPlan` with Android Canvas, same photo processing, then dithers to printer dots. Side-by-side with the laptop preview: identical size and layout, only fonts differ slightly.
- `VirtualPrinterScreen` (app now opens on it): strip shown dot-for-dot, layout preset chips, dithering choice, brightness/contrast/gamma sliders, auto levels, reset; shows dots, paper length (mm) and bytes to send.
- Stand-in photos moved to `core` (`SamplePhotos`) so the app can show strips before the camera exists.
- Screenshot tests with Paparazzi (real Android rendering on the laptop, no emulator): 3 screen snapshots committed in `app/src/test/snapshots/`, plus full-size Android strips written to `app/build/samples/`.
  - Used Paparazzi 2.0.0-alpha02: newer alphas need Java 21 and pull in Android build tools 9 (clashes with our 8.13).
- Tests: 36 passing (core 26, data 6, app 4). Debug APK builds.

## 2026-11-23 — Kiosk flow + first run on a real phone (build order step 5)

- Committed step 4 as `10962ac`.
- `core`: staff PINs stored only as salted PBKDF2 hashes (`Pin`).
- `data`:
  - `KioskFlow`: the customer journey as one tested state machine — idle → package → payment (staff PIN; customer QR or cash via staff, change, reference no.) → capture → review/retake → print → done.
    - Every step saved before the screen moves on; actions serialized so double taps can't run twice.
    - 5 wrong PINs → 60 s lockout. Paid sessions never time out and customers can't cancel them.
    - Blocks new customers when no event is live or storage is under 500 MB.
  - Payment + session saved in one transaction (both or neither).
  - Staff table gained a PIN hash column (schema v1 regenerated — nothing installed anywhere yet, so no migration needed).
  - Demo data seeded on first launch (owner PIN 1234, "Demo Event" with Classic ₱150 / Duo ₱250) until admin screens exist.
- `app`: kiosk screens (Compose), CameraX front camera with countdown + flash, camera-permission screen, inactivity timeouts, screen kept on, landscape lock, virtual printer prints after 2 s; long-press idle screen → virtual printer/tuning.
- Phone connected (Realme 3 Pro, Android 11): installed and ran the full paid flow end to end; pulled the database to the laptop to confirm records.
  - Force-stopped mid-capture → restart recovery cancelled the session correctly, but the customer's payment had no way to be used → **added "Resume unused payment"** (staff PIN, no new sale). Verified on the phone: still 2 sales, resumed session printed as No. 0002.
  - Fixed: staff column didn't scroll on a short screen (confirm button hidden).
- Tests: 52 passing (core 27, data 13, app 12 incl. 11 screenshots). `DEVICE_TESTING.md` updated with phone results.
- Not yet: idle video, event welcome text on idle, QR image on payment screen, PIN-gated staff menu, "Did it print?" prompt after a crash, real printer.

## 2026-11-23 — Owner/admin screens (build order step 6)

- Committed step 5 as `83572d4`.
- `core`: owner passwords (8+ chars, hashed like PINs); `Ledger.forEvent`; CSV export (RFC 4180 quoting, spreadsheet-formula guard, names instead of ids).
- `data`:
  - Staff table gained `passwordHash`; `needsSetup`, `ownerByPassword`.
  - `Gate`: one wrong-PIN/password lockout shared by payment and login. `login()` gives owner tools **only** via the owner password — the owner's PIN alone opens attendant tools.
  - `moveEvent` (one live event, audited), `printsToConfirm`/`resolvePrint`/`failedPrints`/`retryPrint` (virtual printer for now).
  - Demo seeding removed — first launch asks for the owner account instead.
  - Schema v1 regenerated again (phone test data wiped with an uninstall). **From the first real event on, schema changes need migrations.**
- `app`: setup, login, staff menu (attendant: totals, check prints; owner: + events, staff, print tuning, lock/unlock booth), totals with cash held per staff and missing-reference warning, CSV export via Android's save dialog, event editor, staff editor. Staff screens close after 3 min untouched.
- On the phone: created owner → booth blocked (no live event) → created "Test Party" + package → go live → added attendant Ana → cash sale ₱200/₱150 confirmed with Ana's PIN → totals "Ana: ₱150.00" → CSV saved and checked → Ana sees attendant tools only → killed app mid-print → "Did it print?" → No → Print again → completed. Test CSV removed from the phone afterwards.
- Tests: 62 passing (core 29, data 17, app 16 screenshots + 1 strip render).
- Not yet: idle video, payment QR image, layout editor/saved tuning, void/refund screens, disable staff, event dates, real printer, owner password reset.
