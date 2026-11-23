# Captured Keeps — Device Testing Checklist

> Everything that **cannot be tested on the laptop** and needs real hardware.
> Laptop-testable logic (money, states, validation, print bytes, layouts) is covered by unit tests in `android/core`.
> Update this file as features are built: add items when something new needs a device, tick them when verified.

Legend: ⏳ not built yet · 🔨 built, waiting for device · ✅ verified on device · ❌ failed (see notes)

---

## A. Any Android phone (can be done before the tablet arrives)

| # | Test | Status | Notes |
|---|---|---|---|
| A1 | App installs and opens (debug APK) | 🔨 | `android/app/build/outputs/apk/debug/app-debug.apk` |
| A2 | Brand theme looks right: colors, fonts, text sizes readable | 🔨 | Placeholder wordmark screen |
| A3 | Camera permission prompt + explanation; denied → clear recovery screen | ⏳ | |
| A4 | Live camera preview, countdown, capture N shots | ⏳ | Front camera |
| A5 | Review / retake within package limit | ⏳ | |
| A6 | Strip drawn on device (Android Canvas) matches laptop sample | ⏳ | Fonts differ slightly; photo processing identical |
| A7 | Virtual printer screen shows the exact 576-dot image | ⏳ | |
| A8 | Idle video loops; tap to start; missing/broken video → branded fallback screen | ⏳ | MP4 |
| A9 | Inactivity timeout returns to idle | ⏳ | Default 60 s |
| A10 | Records saved to on-device database survive app kill + phone restart | 🔨 | Laptop-tested (file reopen); needs screens that save data to try on device |
| A11 | Force-close mid-capture → session cancelled, paid customer can resume without paying again | ⏳ | Logic unit-tested; needs real kill/restart |
| A12 | Force-close mid-print → attendant asked "Did it print?", no auto-reprint | ⏳ | Logic unit-tested |
| A13 | Low storage warning (< 2 GB) and session block (< 500 MB) | ⏳ | Fill storage with a dummy file |
| A14 | Screen pinning keeps customers inside the app; admin exit works | ⏳ | |
| A15 | Attendant PIN unlock / owner login | ⏳ | |
| A16 | Airplane mode: full free-event flow still works | ⏳ | |
| A17 | CSV export opens in a spreadsheet app | ⏳ | |

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
