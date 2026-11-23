# Captured Keeps — Project Notes (living document)

> Single source of truth for decisions made in discussion. Overrides the original
> `Captured_Keeps_Complete_App_Specification.docx` wherever they differ.
> **Nothing here is final** — decisions can change during discussion *and* during development.
> Update this file whenever a decision changes. Building starts only when the owner says so.
> Work done over time is logged in `HISTORY.md`. Hardware-only tests are tracked in `DEVICE_TESTING.md`.
>
> **Project rule:** no AI/Claude attribution anywhere — no `Co-Authored-By` trailers in commits, no "Generated with Claude" lines in PRs, docs, or code.

Last updated: 2026-11-23

---

## 1. Hardware (confirmed)

| Item | Choice | Notes |
|---|---|---|
| Tablet | Samsung Galaxy Tab A11 | Android 15+, USB-C host (OTG) |
| Camera | Tablet front camera (CameraX) | Lighting matters more than resolution — get a ring light |
| Printer | "POS Cash Till Register 30 Pro", 80mm thermal | ESC/POS, 576 dots wide @ 203 dpi. Support **both Bluetooth and USB** |
| Paper | 80mm thermal | Use BPA-free **long-life** thermal paper (7–25 yr) — standard fades in 1–5 yrs, conflicts with "Lifelong keeps" |

Status: **hardware still in delivery.** Build UI first with stand-ins (see §6).

Known constraint: Bluetooth image printing is slow (~5–10 s per strip). Show progress, never block the kiosk.

---

## 2. Business decisions (confirmed)

- **Who:** our own business first; later sellable/customizable to clients and events.
  - Keep branding, prices, layouts as **data/settings**, not hardcoded.
  - Do **not** build multi-tenant accounts, licensing, or client portal until there is a paying client.
- **Team:** owner + Claude. No deadline.
- **Tech:** Kotlin + Jetpack Compose (Flutter considered and rejected: printer plugins unreliable, kiosk lock needs native code anyway).
- **Roles (v1):** Owner (password) and Attendant (PIN). More roles only when needed.
- **Payment collection:** every paid session ends up as a QR payment into the business wallet (paper trail). Two paths:
  1. **QR customer:** customer scans the QR and pays themselves.
  2. **Cash customer:** customer hands cash to owner/attendant, who pays the QR from their own wallet on the customer's behalf.
  - Either way, staff confirms with their PIN after seeing the payment arrive, and enters the QR reference no.
  - App records: amount, path (`customer_qr` / `cash_via_staff`), `received_cash_by` (staff, cash path only), QR reference no., confirmed_by.
  - **Reconciliation:** every paid session should have a matching wallet transaction → app totals vs wallet history must match.
    A session with no reference no. is flagged for review.
  - Staff who paid QR with their own wallet keep the customer's cash — the app shows each staff member's "cash held" total
    so it's clear who holds what at end of day.
- **QR payments:** static QR Ph (personal GCash/Maya/bank QR) + attendant manual confirm. No provider API in v1.
  Personal wallets OK for now → migrate to business account later (watch personal wallet monthly limits).
  Auto-verified dynamic QR Ph (PayMongo/Xendit/Maya Business) only after business registration.
- **Payment slip wording:** prints `ACKNOWLEDGMENT — NOT AN OFFICIAL RECEIPT/INVOICE`.
  Real invoices come from the BIR booklet on request. (Confirm details with an accountant.)
  The *photo strip* looking like a receipt is the product — that's fine.

---

## 3. Architecture

### 3.1 Two apps, one repo (folders)

```
captured-keeps/
├── PROJECT_NOTES.md      ← this file (decisions)
├── HISTORY.md            ← development timeline
├── DEVICE_TESTING.md     ← what needs a real phone/tablet/printer to verify
├── android/              ← tablet app (built now)
│   ├── core/             ← business rules + print engine (pure Kotlin, laptop-tested)
│   ├── data/             ← on-device database (Room + bundled SQLite, laptop-tested)
│   └── app/              ← Android screens
└── web/                  ← admin web app (later, after cloud backup exists)
```

| Tablet app — must work offline | Admin web app — needs internet anyway |
|---|---|
| Kiosk, capture, print | Reports and charts |
| Event setup + go live | Inventory, expenses |
| Attendant PIN unlock (cash/QR) | Staff management |
| Session + cash log, CSV export | Bookings, CRM |
| | Later: client management when selling |

**Rule:** each kind of record is edited in exactly one place. v1: events edited on tablet.
Later events may move to web and be pushed down to tablets.

### 3.2 Offline-first (industry standard: Square, Shopify POS, Toast)

- The app always works locally. Cloud = backup + web dashboard, never required to run an event.
- No per-event "connectivity profile". Instead, individual features are tagged **needs internet**:
  QR auto-verify (later), digital photo delivery, cloud backup. When offline they show "unavailable"; everything else continues.

### 3.3 Append-only records (ledger)

- Financial records are **never edited or deleted**. Corrections are new rows referencing the original.
  ```
  #1  Sale    ₱150   cash  10:02
  #2  Refund  -₱150  refunds #1, reason "print jam"  10:15
  ```
- Totals = sum of rows. Audit trail for free.
- Every row has a device-generated UUID → sync is "upload rows the cloud doesn't have"; re-upload is a no-op. No conflicts.
- Money stored as **integer centavos** (`15000` = ₱150.00). Never floating point.

---

## 4. Data states, validation, fallbacks, error handling

Each booth visit has **three independent states** (not one combined status field):

### 4.1 Session state

```
started ──▶ capturing ──▶ reviewing ──▶ printing ──▶ completed
   │            │             │             │
   └────────────┴─────────────┴─────────────┴──▶ failed / cancelled
```

| Rule | Detail |
|---|---|
| Config snapshot | Session copies the package (price, shot count, retakes, layout version) at start. Edits to the event never change a session already started. |
| Retakes | Limited by package rule; validated before allowing another capture. |
| Inactivity timeout | No touch for N seconds (configurable, default 60) → cancelled → back to idle. If paid, the payment stays credited (see 4.4). |
| Crash / restart recovery | On app start, find sessions not in a final state: <br>• `capturing`/`reviewing` → `cancelled` (reason `app_restart`); if paid → credit kept for attendant to resume. <br>• `printing` → **never auto-reprint.** Mark print job `unknown`, alert attendant: "Did it print?" → Yes = completed / No = reprint. |
| Failure reason | `failed` always stores a reason code: `camera_error`, `print_error`, `storage_full`, `app_restart`, `other`. |

### 4.2 Payment state (paid events only)

| Value | Meaning |
|---|---|
| `free` | Free event session — revenue ₱0, no fake sales |
| `cash` | Attendant confirmed cash with PIN |
| `qr_confirmed` | Attendant confirmed QR payment seen in their wallet app, with PIN |
| `voided` | New row: cancelled before session ran (attendant/owner + reason) |
| `refunded` | New row: money returned (owner only + reason) |

Validation:
- Amounts are integers > 0; total must equal package price snapshot (+ add-ons).
- Cash: `tendered ≥ total`; change = tendered − total, shown before confirm.
- QR: optional reference number field; cannot confirm without attendant PIN.
- Refund amount ≤ amount paid minus prior refunds. Reason required (non-empty).
- Void only allowed if session hasn't started capturing; otherwise use refund.
- One payment per session (prevents double charge). Retry/reprint never creates a new payment.

### 4.3 Print job state

```
queued ──▶ sending ──▶ done
              │
              ├──▶ failed  (retry allowed)
              └──▶ unknown (app died mid-print → ask attendant)
```

| Situation | Handling |
|---|---|
| Printer not connected | Job stays `queued`. Kiosk shows "Your keepsake is being prepared — please see the attendant." Attendant alert. Auto-retry when printer reconnects. |
| Paper out / cover open | Query ESC/POS status (`DLE EOT`) if printer supports it; else detect via failed write. Alert: "Check paper". |
| Reprint | Always a **new** print job with reason + operator. Never charges again. Increments print count. |
| Final image | Saved to local storage **before** sending to printer, so a reprint never needs the camera again. |

### 4.4 Sync state (each record)

| Value | Meaning |
|---|---|
| `pending` | Saved locally, not yet uploaded |
| `synced` | Cloud has it |
| `error` | Upload failed; stores last error + attempt count. Retries with backoff; manual "retry" button for owner |
| `local_only` | Cloud sync disabled |

- Never delete a record that isn't `synced`.
- v1 has no cloud → everything `local_only` / `pending` until cloud backup phase.

### 4.5 Event state

```
Draft ──start──▶ Live ◀──resume── Paused
  │               │ └──pause──▶
  │               └──finish──▶ Completed
  └──cancel──▶ Cancelled
```

- **Scheduled** is not a state: shown automatically when Draft has a future start date.
- **Archived** is not a state: a `hidden` yes/no flag on Completed/Cancelled events.

Validation before going **Live**:
- Event name not empty.
- At least one enabled layout.
- Paid event: at least one package with price > 0.
- Idle media file exists and plays — otherwise warn, fallback screen will be used.
- Only **one** Live event per device.

Editing a Live event:
- Title, welcome text, idle media, branding → apply immediately (only when no session is active; otherwise after it ends).
- Price, package, layout → apply to **new sessions only** (sessions use their snapshot).
- Important edits logged: who, when, old value → new value.

### 4.6 Device-level fallbacks

| Problem | Fallback |
|---|---|
| Idle video missing/fails | Branded static screen + TAP TO START |
| Camera unavailable / permission denied | Friendly error screen "Please see the attendant", attendant alert, auto-return to idle after timeout. No charge taken if not paid yet. |
| Storage low | Warn owner at < 2 GB; block new sessions at < 500 MB (configurable) — never silently fail to save |
| Power loss | Room/SQLite with WAL: a record confirmed on screen is already on disk |
| Kiosk escape | Screen pinning in v1; Device Owner lock-task later |

---

## 5. MVP (v1) — draft, open to change

**Goal: we can run a free event and a cash/QR event with it.**

In v1:
- Owner login (password) + attendant PIN
- Event setup: name, free/paid, packages + prices, layouts, idle video, welcome text, footer
- Event states + validation (§4.5)
- Kiosk: idle video loop → tap to start → (attendant unlock if paid) → countdown → capture → review/retake → print → thank you → idle
- Thermal image processing (grayscale, contrast/gamma, dithering) tuned to 576px
- Customizable strip layout (§5.1)
- Print over Bluetooth and USB
- Session + payment + print log, all states in §4
- Crash recovery, fallbacks (§4.6)
- Simple on-tablet totals for today/event (sessions, prints, cash, QR)
- CSV export
- Screen pinning

Not in v1 (later, mostly in the web app):
- Cloud backup, admin web app, dashboard charts
- Inventory, expenses, staff management, bookings, CRM
- Digital photo delivery by QR
- Auto-verified QR Ph
- Device Owner kiosk lock
- Multi-booth, client/white-label management

### 5.1 Customizable strip layout (v1)

A layout is saved settings (data), not a drag-and-drop editor. Owner edits it in a form with a live preview.
Each session records which layout version it printed with.

| Setting | Options |
|---|---|
| Shots per strip | 1–4 |
| Photo shape | 4:3 landscape, 1:1 square, 3:4 portrait |
| Header | logo image and/or text wordmark, on/off |
| Event name line | on/off, custom text |
| Date / time / strip number | each on/off |
| Footer text | custom (default "Little moments. Lifelong keeps.") |
| Overlay / frame | optional black-and-white PNG on top of the photos (design it in Canva) |
| Spacing | gap between photos, margins |
| Print tuning | brightness, contrast, dither style (per printer + paper) |

Presets ship with the app (e.g. "Classic 3", "Square 4", "Single portrait"). An event picks one or more enabled layouts.
Logo = text wordmark placeholder until the real file exists.

---

### 5.2 Dev environment constraint

Laptop: Ryzen 3 7320U, **5 GB RAM** (~2 GB free in normal use), 15 GB free disk.
Gradle build (~1.5–2 GB) + emulator (~2–3 GB) won't fit together comfortably.

Plan:
- Build from command line (no Android Studio), Gradle heap capped (`-Xmx1536m`), one daemon.
- Logic, money, states, image processing → plain JVM unit tests (no device needed).
- Screens → rendered to PNG on the JVM with screenshot tests (Paparazzi), sent for review.
- Interactive testing → install the APK on any spare/personal Android phone over USB or wireless debugging, until the Tab A11 arrives.
- Emulator only as last resort: small AVD, 1.5 GB RAM, no Play Store, everything else closed.

---

## 6. Build order

1. ✅ Android project + brand design system (ivory/charcoal/taupe).
2. ✅ Thermal image processing (no printer needed — judge sample strips on screen).
3. ✅ States/validation (§4) as business rules, then ✅ local database.
4. ✅ Strip drawn on Android + **virtual printer** screen (shows the exact 576px black-and-white image instead of printing).
   Kiosk flow UI — no emulator on this laptop (§5.2), so screens are checked with screenshot tests, camera on a spare phone/tablet.
5. Admin: event setup, attendant PIN, totals, CSV export.
6. **When hardware arrives:** Bluetooth/USB printer output, real-camera tuning, pilot test.
7. Cloud backup → admin web app → everything else.

*(Order may still change as discussion continues.)*

---

## 7. Open questions

- [ ] Package and prices for first paid event.
- [x] Staff pays QR for cash customers → to keep every sale in the business wallet (paper trail).
- [x] Customers can scan and pay the QR themselves; staff-pays path is only for cash customers.
- [x] Tab A11 → also in delivery; use emulator + laptop webcam.
- [x] Logo → placeholder text wordmark for now.
- [x] Strip design → customizable layout (§5.1).

---

## 8. Decision log

| Date | Decision |
|---|---|
| 2026-11-23 | Kotlin + Compose (not Flutter) |
| 2026-11-23 | 80mm ESC/POS thermal, Bluetooth + USB |
| 2026-11-23 | Front camera of Galaxy Tab A11 |
| 2026-11-23 | Roles: Owner + Attendant only |
| 2026-11-23 | Kiosk never handles money; attendant PIN unlock |
| 2026-11-23 | Static QR Ph + manual confirm; personal wallets for now |
| 2026-11-23 | Payment slip = "Acknowledgment — not an official receipt/invoice" |
| 2026-11-23 | Three separate states (session / payment / sync) + print job state |
| 2026-11-23 | Append-only financial records; integer centavos |
| 2026-11-23 | Offline-first; no "Online Only" profile; features tagged "needs internet" |
| 2026-11-23 | Event states: Draft, Live, Paused, Completed, Cancelled (+ hidden flag) |
| 2026-11-23 | Back-office features → separate admin web app later |
| 2026-11-23 | Build UI first with stand-in camera + virtual printer (hardware in delivery) |
| 2026-11-23 | All paid sessions go through QR into business wallet: customer self-pay, or staff pays QR for cash customers |
| 2026-11-23 | Tablet also in delivery → emulator for now |
| 2026-11-23 | Logo: text wordmark placeholder |
| 2026-11-23 | Strip layout customizable via settings + presets (not a drag editor) |
