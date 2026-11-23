#!/usr/bin/env python3
"""
Builds the Captured Keeps walkthrough video: phone screen recordings + Kokoro narration + burned-in captions + music.

    KOKORO_DIR=<folder with kokoro-v1.0.onnx, voices-v1.0.bin and venv/> \
    REC_DIR=<folder with the scene .mp4 recordings> MUSIC=<music file> OUT_DIR=<output folder> \
    python3 presentation/build.py

Recordings are 2056x1080 scrcpy captures of the app on a 16:9 virtual display; CROP cuts the 1920x1080 app area.
Outputs (video, captions, work files) go to OUT_DIR, which is outside git.
"""
import json, os, subprocess, sys, wave
from PIL import Image, ImageDraw, ImageFont

KOKORO_DIR = os.environ["KOKORO_DIR"]
REC_DIR = os.environ["REC_DIR"]
MUSIC = os.environ["MUSIC"]
OUT = os.environ["OUT_DIR"]
VOICE = "am_michael"
CROP = "crop=1920:1080:106:0"
W, H, FPS = 1920, 1080, 30
LEAD, TAIL = 0.6, 0.9          # seconds of picture before / after the narration in each scene
GAP = 0.35                     # silence between narration lines

IVORY, CHARCOAL, TAUPE = (247, 243, 236), (43, 43, 43), (139, 125, 107)
SERIF_BOLD = "/usr/share/fonts/truetype/noto/NotoSerif-Bold.ttf"
SANS = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"

# (clip or card, narration lines, options). trim = (start, end) seconds kept from the recording; max_speed caps fast-forward.
SCENES = [
    ("card:title", [
        "This is Captured Keeps, a receipt photo studio booth.",
        "Guests take photos at an Android tablet, and get a black and white photo strip from an eighty millimeter thermal printer.",
        "Here's how every part of the app looks, and how it works. The printer is still on its way, so today, prints go to a virtual printer on screen.",
    ], {}),
    ("s01_setup", [
        "The first time the app opens, it asks for the owner account.",
        "The password opens the owner tools. The PIN confirms payments at the booth.",
    ], {"max_speed": 3}),
    ("s02_menu", [
        "Staff open their menu with a long press on the booth screen.",
        "With the owner password, every tool is there: totals, print checks, events, staff, the payment QR, the printer, strip layouts, and locking the booth.",
    ], {"max_speed": 2.5}),
    ("s03_qr", ["Payment QR holds the business e-wallet code that guests scan to pay."], {"max_speed": 3}),
    ("s04_layouts", [
        "Strip layouts control what every print looks like.",
        "Choose the number of photos, their shape, the header and footer, the margins, and which details to print.",
        "The preview is the exact image the printer receives, dot for dot. Print darkness is tuned per layout, and every save becomes a new version.",
    ], {"max_speed": 2.5}),
    ("s05_printer", [
        "The printer connects over Bluetooth or a USB cable.",
        "Calibration settings are ready for when the printer arrives, along with a paper and cover status check, and a test page.",
    ], {"max_speed": 3}),
    ("s06_staff", [
        "Attendants get their own PIN.",
        "Staff who leave are disabled, not deleted, so their names stay on past sales.",
    ], {"max_speed": 3}),
    ("s07_event", [
        "Events hold everything for a booking: the name, venue and date, the welcome message, the strip layouts, and the packages, with their prices, prints and retakes.",
    ], {"max_speed": 5, "trim": (0, -6)}),
    ("s07b_save", [
        "Before an event goes live, the app checks that it's ready, and warns about anything missing.",
    ], {"max_speed": 2, "trim": (0.5, None)}),
    ("s08_golive", ["Only one event can be live at a time. Going live opens the booth to guests."], {"max_speed": 2}),
    ("s09_idle", ["The booth plays the event's idle video, with its welcome message, until a guest taps the screen."], {"max_speed": 1, "trim": (1, 9.5)}),
    ("s10_pay", [
        "The guest picks a package.",
        "For paid events, staff confirm the payment. Either the guest scanned the QR, or paid in cash and the attendant paid the QR for them. The app works out the change.",
        "The wallet reference is saved for matching later, and the attendant confirms with their PIN.",
    ], {"max_speed": 2.5}),
    ("s11_camera", [
        "Then it's photo time: a three second countdown, a flash, and three shots with the front camera. In testing, it's pointed at the ceiling.",
        "Guests see their strip, and can retake it, up to the retakes in their package.",
    ], {"max_speed": 1}),
    ("s12_print", ["Then it prints. For now, the virtual printer stands in. And that's their keep."], {"max_speed": 2.2}),
    ("s13_totals", [
        "Back in the staff menu, totals show each event's sales, split by payment type, and how much cash each attendant is holding at the end of the day.",
        "Every payment is listed. Unused payments can be voided, and the owner can refund, always with a reason. Nothing is ever edited or deleted. Corrections are added as new records.",
        "Each payment can also print an acknowledgment slip, clearly marked as not an official receipt. Sales can be exported to a spreadsheet, too.",
    ], {"max_speed": 2}),
    ("s14_recovery", [
        "If the app ever stops in the middle of a print, it never reprints by itself. Staff are asked whether it came out.",
    ], {"max_speed": 2.5}),
    ("s14b_recovery", ["If it didn't, one tap prints it again, and the guest is never charged twice."], {"max_speed": 2.5}),
    ("card:outro", [
        "Captured Keeps works fully offline, keeps every record safe on the tablet, and adapts to any screen.",
        "Next up: the thermal printer, the tablet, and the first pilot event.",
        "Little moments. Lifelong keeps.",
    ], {}),
]

CREDIT = 'Music: "Wallpaper" by Kevin MacLeod (incompetech.com) · CC BY 4.0 · Voice: Kokoro TTS'


def run(*cmd):
    subprocess.run(cmd, check=True)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True, check=True).stdout
    return float(out)


def tts(work):
    """All narration in one Kokoro run (model load is the slow part). Writes one WAV per line."""
    jobs = [{"text": t, "out": f"{work}/n{i:02d}_{j}.wav"} for i, (_, lines, _) in enumerate(SCENES) for j, t in enumerate(lines)]
    todo = [j for j in jobs if not os.path.exists(j["out"])]
    if todo:
        script = f"""
import json, sys, soundfile as sf
from kokoro_onnx import Kokoro
k = Kokoro({KOKORO_DIR + '/kokoro-v1.0.onnx'!r}, {KOKORO_DIR + '/voices-v1.0.bin'!r})
for j in json.load(sys.stdin):
    a, sr = k.create(j["text"], voice={VOICE!r}, speed=1.0, lang="en-us")
    sf.write(j["out"], a, sr, subtype="PCM_16")
"""
        subprocess.run([f"{KOKORO_DIR}/venv/bin/python", "-I", "-c", script], input=json.dumps(todo), text=True, check=True)
    return jobs


def wav_len(path):
    with wave.open(path) as w:
        return w.getnframes() / w.getframerate()


def card(kind, path):
    img = Image.new("RGB", (W, H), IVORY)
    d = ImageDraw.Draw(img)
    big, mid, small = ImageFont.truetype(SERIF_BOLD, 120), ImageFont.truetype(SANS, 34), ImageFont.truetype(SANS, 26)

    def center(text, font, y, fill):
        w = d.textlength(text, font=font)
        d.text(((W - w) / 2, y), text, font=font, fill=fill)

    d.rectangle([W / 2 - 520, 330, W / 2 + 520, 334], fill=CHARCOAL)
    center("CAPTURED KEEPS", big, 365, CHARCOAL)
    center("R E C E I P T   P H O T O   S T U D I O", mid, 525, TAUPE)
    d.rectangle([W / 2 - 520, 590, W / 2 + 520, 594], fill=CHARCOAL)
    if kind == "title":
        center("App walkthrough · v0.9.0 pilot", mid, 660, CHARCOAL)
    else:
        center("Little moments. Lifelong keeps.", ImageFont.truetype(SERIF_BOLD, 48), 650, CHARCOAL)
        center(CREDIT, small, 760, TAUPE)
    img.save(path)


def segment(i, clip, opts, narr_len, work):
    """One scene as a 1920x1080/30fps silent clip, at least as long as its narration."""
    out = f"{work}/seg{i:02d}.mp4"
    need = LEAD + narr_len + TAIL
    if clip.startswith("card:"):
        png = f"{work}/{clip[5:]}.png"
        card(clip[5:], png)
        run("ffmpeg", "-loglevel", "error", "-y", "-loop", "1", "-i", png, "-t", f"{need:.3f}", "-r", str(FPS),
            "-vf", f"fade=in:0:15,fade=out:st={need - 0.5:.3f}:d=0.5,format=yuv420p", "-c:v", "libx264", "-crf", "18", out)
        return out, need
    src = f"{REC_DIR}/{clip}.mp4"
    start, end = opts.get("trim", (0, None))
    total = duration(src)
    end = total + end if end is not None and end < 0 else (end or total)
    raw = end - start
    speed = min(opts.get("max_speed", 1), max(1.0, raw / max(need - 0.5, 0.1)))
    shown = raw / speed
    pad = max(0.0, need - shown)
    length = shown + pad
    vf = (f"trim=start={start:.3f}:end={end:.3f},setpts=(PTS-STARTPTS)/{speed:.4f},{CROP},fps={FPS},"
          f"tpad=stop_mode=clone:stop_duration={pad:.3f},fade=in:0:8,fade=out:st={length - 0.3:.3f}:d=0.3,format=yuv420p")
    run("ffmpeg", "-loglevel", "error", "-y", "-i", src, "-an", "-vf", vf, "-r", str(FPS), "-c:v", "libx264", "-crf", "18", out)
    return out, length


def ass_time(t):
    h, rem = divmod(t, 3600); m, s = divmod(rem, 60)
    return f"{int(h)}:{int(m):02d}:{s:05.2f}"


def main():
    work = f"{OUT}/work"
    os.makedirs(work, exist_ok=True)
    jobs = iter(tts(work))
    segs, events, audio, t = [], [], [], 0.0
    for i, (clip, lines, opts) in enumerate(SCENES):
        wavs = [next(jobs)["out"] for _ in lines]
        lens = [wav_len(w) for w in wavs]
        narr = sum(lens) + GAP * (len(lens) - 1)
        seg, length = segment(i, clip, opts, narr, work)
        segs.append(seg)
        at = t + LEAD
        for text, w, n in zip(lines, wavs, lens):
            audio.append((w, at))
            events.append((at, at + n, text))
            at += n + GAP
        t += length
        print(f"{clip:16s} {length:6.1f}s  (narration {narr:.1f}s)", flush=True)

    with open(f"{work}/concat.txt", "w") as f:
        f.writelines(f"file '{s}'\n" for s in segs)
    run("ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{work}/concat.txt", "-c", "copy", f"{work}/picture.mp4")

    # Narration: every line placed at its time; music looped underneath, quieter while the voice speaks.
    inputs, chains = [], []
    for k, (w, at) in enumerate(audio):
        inputs += ["-i", w]
        chains.append(f"[{k}:a]aformat=sample_rates=48000:channel_layouts=stereo,adelay={int(at * 1000)}:all=1[v{k}]")
    n = len(audio)
    voice = "".join(f"[v{k}]" for k in range(n)) + f"amix=inputs={n}:normalize=0,volume=2.5,apad=whole_dur={t:.3f}[voice]"  # Kokoro speaks quietly
    music = (f"[{n}:a]aformat=sample_rates=48000:channel_layouts=stereo,volume=0.16,atrim=0:{t:.3f},"
             f"afade=t=in:d=2,afade=t=out:st={t - 4:.3f}:d=4[music]")
    duck = "[voice]asplit[vo][vk];[music][vk]sidechaincompress=threshold=0.02:ratio=6:attack=80:release=600[bed];[bed][vo]amix=inputs=2:normalize=0,loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[mix]"
    run("ffmpeg", "-loglevel", "error", "-y", *inputs, "-stream_loop", "-1", "-i", MUSIC,
        "-filter_complex", ";".join(chains + [voice, music, duck]), "-map", "[mix]", "-c:a", "pcm_s16le", f"{work}/audio.wav")

    # Captions: burned in (ASS for styling) and also kept as .srt.
    with open(f"{work}/captions.ass", "w") as f:
        f.write("[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 0\n\n"
                "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV\n"
                "Style: Cap,Noto Sans,44,&H00FFFFFF,&H00000000,&H9A2B2B2B,0,3,14,0,2,240,240,56\n\n"
                "[Events]\nFormat: Layer, Start, End, Style, Text\n")
        for a, b, text in events:
            f.write(f"Dialogue: 0,{ass_time(a)},{ass_time(b)},Cap,{text}\n")
    with open(f"{OUT}/captured-keeps-walkthrough.srt", "w") as f:
        for k, (a, b, text) in enumerate(events, 1):
            ts = lambda x: f"{int(x // 3600):02d}:{int(x % 3600 // 60):02d}:{int(x % 60):02d},{int(x * 1000 % 1000):03d}"
            f.write(f"{k}\n{ts(a)} --> {ts(b)}\n{text}\n\n")

    out = f"{OUT}/captured-keeps-walkthrough.mp4"
    run("ffmpeg", "-loglevel", "error", "-y", "-i", f"{work}/picture.mp4", "-i", f"{work}/audio.wav",
        "-vf", f"ass={work}/captions.ass", "-c:v", "libx264", "-crf", "20", "-preset", "medium", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-shortest", out)
    print(f"\n{out}  {duration(out):.1f}s")


if __name__ == "__main__":
    sys.exit(main())
