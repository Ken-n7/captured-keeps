# Captured Keeps — Device Testing Checklist

> Everything that **cannot be tested on the laptop** and needs real hardware.
> Laptop-testable logic (money, states, validation, print bytes, layouts) is covered by unit tests in `android/core`.
> Update this file as features are built: add items when something new needs a device, tick them when verified.

Legend: ⏳ not built yet · 🔨 built, waiting for device · ✅ verified on device · ❌ failed (see notes)

---

## A. Any Android phone (can be done before the tablet arrives)

| # | Test | Status | Notes |
|---|---|---|---|
| A1 | App installs and opens (debug APK) | ✅ | Realme 3 Pro, Android 11, 2026-11-23 |
| A2 | Brand theme looks right: colors, fonts, text sizes readable | ✅ | Realme 3 Pro, Android 11, 2026-11-23. Screens designed for the tablet; on the phone, landscape is tight but usable |
| A3 | Camera permission prompt + explanation; denied → clear recovery screen | 🔨 | Prompt shown and "allow" works (Realme 3 Pro, Android 11, 2026-11-23). Denied → recovery screen not tried yet |
| A4 | Live camera preview, countdown, capture N shots | ✅ | Realme 3 Pro, Android 11, 2026-11-23. Preview, 3-2-1, flash, 3 shots saved |
| A5 | Review / retake within package limit | 🔨 | Review screen ✅; retake not tried on device yet |
| A6 | Strip drawn on device (Android Canvas) matches laptop sample | ✅ | Strip built from real camera photos on Realme 3 Pro, Android 11, 2026-11-23 |
| A7 | Virtual printer screen shows the exact 576-dot image | 🔨 | 1 dot = 1 screen pixel; check sliders feel responsive (renders on main thread) |
| A8 | Idle video loops; tap to start; missing/broken video → branded fallback screen | ✅ | Test MP4 plays silently, loops, welcome text over it; no video → branded screen (Realme 3 Pro, 2026-11-23). Broken-file fallback not tried on device |
| A9 | Inactivity timeout returns to idle | 🔨 | 60 s on package/review screens, 120 s payment, 20 s done; paid sessions never time out |
| A10 | Records saved to on-device database survive app kill + phone restart | ✅ | Survived force-stop (Realme 3 Pro, Android 11, 2026-11-23); phone reboot not tried yet |
| A11 | Force-close mid-capture → session cancelled, paid customer can resume without paying again | ✅ | Force-stop mid-capture → session cancelled (APP_RESTART); staff resumed the payment with PIN, no second sale (Realme 3 Pro, Android 11, 2026-11-23) |
| A12 | Force-close mid-print → attendant asked "Did it print?", no auto-reprint | ✅ | Killed during print → staff "Check prints" asked; "No" → "Print again" → done on attempt 2 (Realme 3 Pro, 2026-11-23) |
| A13 | Low storage warning (< 2 GB) and session block (< 500 MB) | ⏳ | Fill storage with a dummy file |
| A14 | Screen pinning keeps customers inside the app; admin exit works | 🔨 | Owner menu "Lock booth"/"Unlock booth" (Android screen pinning) — not tried yet |
| A15 | Attendant PIN unlock / owner login | ✅ | First-run owner setup, owner password → owner tools, attendant PIN → attendant tools only (Realme 3 Pro, 2026-11-23) |
| A16 | Airplane mode: full free-event flow still works | ⏳ | |
| A17 | CSV export opens in a spreadsheet app | ✅ | Saved via Android save dialog to Downloads; contents checked on the laptop (Realme 3 Pro, 2026-11-23). Not yet opened in a spreadsheet app |
| A18 | Event created on device → go live → paid cash session → totals show cash held by the attendant | ✅ | Realme 3 Pro, 2026-11-23 |
| A19 | App update keeps existing records (database migration) | ✅ | v1 → v2 over real test data: owner login, staff and sales kept (Realme 3 Pro, 2026-11-23). First attempt crashed and rolled back safely — fixed |
| A20 | Refund (owner password) and void; disabled staff PIN refused | ✅ | Partial refund ₱50 with reason; Ana disabled → "Wrong PIN or password" (Realme 3 Pro, 2026-11-23). Void tested on laptop only |
| A21 | Pick idle video and payment QR through the file browser | 🔨 | Browser opens (Realme 3 Pro, 2026-11-23), but this phone's file app wouldn't list the test file; display verified by placing files in app storage. **Owner: try picking your real QR screenshot and a video** |
| A22 | Payment QR shows on the payment screen; Android Back never leaves the booth | ✅ | Realme 3 Pro, 2026-11-23 |
| A23 | Event start/end date picker | ✅ | Realme 3 Pro, 2026-11-23 |

## B. Galaxy Tab A11 (when it arrives)

| # | Test | Status | Notes |
|---|---|---|---|
| B1 | Repeat all of section A on the tablet | ⏳ | |
| B2 | Front camera quality + angle at booth height | ⏳ | Test with the ring light |
| B3 | Layout fits tablet screen in chosen orientation; touch targets easy to hit | ⏳ | |
| B4 | Performance: capture → strip ready time; idle video smooth | ⏳ | |
| B5 | Battery / heat over a few hours plugged in | ⏳ | |
| B6 | USB-C OTG works with the printer cable (and charging at the same time, if needed) | ⏳ | May need a powered OTG hub |

## C. Thermal printer — "POS Cash Till Register 30 Pro", 80mm (when it arrives)

| # | Test | Status | Notes |
|---|---|---|---|
| C1 | Confirm printer specs: 576 dots wide? ESC/POS `GS v 0` raster supported? Has cutter? | ⏳ | Print the self-test page (hold FEED while powering on) |
| C2 | Bluetooth: pair, connect, print a strip | ⏳ | |
| C3 | USB: connect, permission prompt, print a strip | ⏳ | |
| C4 | Band height: full strip prints with no garbled/skipped lines | 🔨 | Knob in `EscPos.raster(bandHeight)`, default 128 |
| C5 | Print tuning on real paper: brightness, contrast, gamma, Floyd–Steinberg vs Atkinson | 🔨 | Laptop preview is lighter than real thermal output |
| C6 | Time per strip over Bluetooth vs USB | ⏳ | Expect ~5–10 s Bluetooth |
| C7 | Paper out / cover open detected (`DLE EOT` status) or at least a failed job | ⏳ | Depends on what the printer reports |
| C8 | Printer turned off mid-print → job failed → retry works, no double charge | ⏳ | |
| C9 | Printer reconnects → queued jobs print automatically | ⏳ | |
| C10 | Cut command works (or tear bar position right if no cutter) | 🔨 | `feedLines` + `cut` options |
| C11 | Long-life thermal paper vs standard: darkness, fading check after weeks | ⏳ | Supports "Lifelong keeps" promise |
| C12 | Payment acknowledgment slip prints "NOT AN OFFICIAL RECEIPT/INVOICE" | ⏳ | |

## D. Pilot event (all hardware together)

From spec §19 — run before charging real customers.

| # | Test | Status | Notes |
|---|---|---|---|
| D1 | Full free event with internet **off** | ⏳ | |
| D2 | Paid event: customer-QR path, staff confirm + reference no. | ⏳ | |
| D3 | Paid event: cash-via-staff path, change shown, cash-held total correct | ⏳ | |
| D4 | End-of-day totals match wallet history and cash in hand | ⏳ | |
| D5 | Paper runs out mid-event → recovery | ⏳ | |
| D6 | Tablet restart mid-event → safe state, no lost records | ⏳ | |
| D7 | Live event edit (welcome text, price) → running session unaffected | ⏳ | |
| D8 | Real venue lighting + crowd: customers use it without help | ⏳ | |
