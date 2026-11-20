#!/usr/bin/env python3
"""Photo3D render server: receives photos/videos from the Android app over the LAN,
builds a 3D model with Apple Object Capture and serves GLB / USDZ / OBJ.
Stdlib only. Run: python3 server.py [--port 8765]"""

import argparse, json, os, re, shutil, signal, socket, subprocess, sys, threading, time, uuid, zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from queue import Queue
from urllib.parse import urlparse

BASE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(BASE, "data")
TOOL = os.path.join(BASE, "photogrammetry", ".build", "release", "photogrammetry")
CONVERTER = os.path.join(BASE, "convert_glb.py")
BLENDER_CANDIDATES = ["/Applications/Blender.app/Contents/MacOS/Blender", shutil.which("blender") or ""]
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"
FFPROBE = shutil.which("ffprobe") or "/opt/homebrew/bin/ffprobe"

IMAGE_EXT = {".jpg", ".jpeg", ".heic", ".heif", ".png"}
CONVERT_EXT = {".webp", ".tif", ".tiff", ".bmp", ".dng"}
VIDEO_EXT = {".mp4", ".mov", ".m4v", ".3gp", ".mkv", ".webm", ".avi"}
DETAILS = {"preview", "reduced", "medium", "full", "raw"}
MIN_IMAGES = 10
VERSION = "1.0"

jobs = {}
jobs_lock = threading.Lock()
work_queue = Queue()


def log(*a):
    print(time.strftime("[%H:%M:%S]"), *a, flush=True)


def blender_path():
    for p in BLENDER_CANDIDATES:
        if p and os.path.exists(p):
            return p
    return None


def safe_name(name):
    name = os.path.basename(name or "")
    name = re.sub(r"[^A-Za-z0-9._-]", "_", name).strip("._")
    return name[:120] or "file"


class Job:
    FIELDS = ["id", "name", "created", "state", "stage", "progress", "message", "eta",
              "images", "videos", "frames", "warnings", "options", "error", "finished", "started"]

    def __init__(self, name):
        self.id = uuid.uuid4().hex[:12]
        self.name = name or time.strftime("Scan %d.%m. %H:%M")
        self.created = time.time()
        self.state = "uploading"   # uploading, queued, processing, done, failed, cancelled
        self.stage = "Warte auf Dateien"
        self.progress = 0.0
        self.message = ""
        self.eta = None
        self.images = 0
        self.videos = 0
        self.frames = 0
        self.warnings = []
        self.options = {}
        self.error = None
        self.started = None
        self.finished = None
        self.proc = None
        self.cancel_requested = False

    @property
    def dir(self):
        return os.path.join(DATA, self.id)

    @property
    def input_dir(self):
        return os.path.join(self.dir, "input")

    @property
    def images_dir(self):
        return os.path.join(self.dir, "images")

    @property
    def output_dir(self):
        return os.path.join(self.dir, "output")

    def outputs(self):
        o = self.output_dir
        return {
            "glb": os.path.exists(os.path.join(o, "model.glb")),
            "usdz": os.path.exists(os.path.join(o, "model.usdz")),
            "obj": os.path.isdir(os.path.join(o, "obj")) and any(f.endswith(".obj") for f in os.listdir(os.path.join(o, "obj"))),
        }

    def to_dict(self):
        d = {k: getattr(self, k) for k in self.FIELDS}
        d["outputs"] = self.outputs()
        d["thumb"] = os.path.exists(os.path.join(self.dir, "thumb.jpg"))
        sizes = {}
        for key, fn in (("glb", "model.glb"), ("usdz", "model.usdz")):
            p = os.path.join(self.output_dir, fn)
            if os.path.exists(p):
                sizes[key] = os.path.getsize(p)
        d["sizes"] = sizes
        return d

    def save(self):
        os.makedirs(self.dir, exist_ok=True)
        tmp = os.path.join(self.dir, "job.json.tmp")
        with open(tmp, "w") as f:
            json.dump({k: getattr(self, k) for k in self.FIELDS}, f)
        os.replace(tmp, os.path.join(self.dir, "job.json"))

    @classmethod
    def load(cls, path):
        with open(path) as f:
            d = json.load(f)
        j = cls(d.get("name"))
        for k in cls.FIELDS:
            if k in d:
                setattr(j, k, d[k])
        return j

    def update(self, **kw):
        for k, v in kw.items():
            setattr(self, k, v)
        self.save()

    def warn(self, msg):
        self.warnings = (self.warnings + [msg])[-20:]


# ---------------------------------------------------------------- processing

def run(cmd, job=None, **kw):
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, **kw)
    if job:
        job.proc = p
    out = p.communicate()[0]
    if job:
        job.proc = None
    return p.returncode, out


def video_duration(path):
    code, out = run([FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path])
    try:
        return float(out.strip().splitlines()[-1])
    except Exception:
        return 0.0


def prepare_images(job):
    os.makedirs(job.images_dir, exist_ok=True)
    files = sorted(os.listdir(job.input_dir))
    videos = [f for f in files if os.path.splitext(f)[1].lower() in VIDEO_EXT]
    frames_per_video = int(job.options.get("framesPerVideo", 120))
    count = 0
    for i, f in enumerate(files):
        src = os.path.join(job.input_dir, f)
        ext = os.path.splitext(f)[1].lower()
        if ext in IMAGE_EXT:
            dst = os.path.join(job.images_dir, f"{i:04d}_{f}")
            try:
                os.link(src, dst)
            except OSError:
                shutil.copy2(src, dst)
            count += 1
        elif ext in CONVERT_EXT:
            dst = os.path.join(job.images_dir, f"{i:04d}_{os.path.splitext(f)[0]}.jpg")
            code, _ = run(["sips", "-s", "format", "jpeg", src, "--out", dst], job)
            if code == 0:
                count += 1
            else:
                job.warn(f"{f}: konnte nicht konvertiert werden")
    for vi, f in enumerate(videos):
        if job.cancel_requested:
            return 0
        src = os.path.join(job.input_dir, f)
        dur = video_duration(src)
        if dur > 0:
            fps = min(max(frames_per_video / dur, 1.0), 10.0)   # min. 1 Bild/s für genug Überlappung
            fps = min(fps, max(400.0 / dur, 0.2))                # max. ~400 Bilder pro Video
        else:
            fps = 2.0
        job.update(stage=f"Extrahiere Frames aus Video {vi + 1}/{len(videos)}",
                   progress=0.02 + 0.08 * vi / max(len(videos), 1))
        pattern = os.path.join(job.images_dir, f"v{vi:02d}_%05d.jpg")
        code, out = run([FFMPEG, "-hide_banner", "-loglevel", "error", "-i", src,
                         "-vf", f"fps={fps:.4f},scale='min(iw,4032)':-2", "-q:v", "2", pattern], job)
        if code != 0:
            job.warn(f"{f}: Frame-Extraktion fehlgeschlagen")
            log("ffmpeg error", out[-500:])
    total = len([f for f in os.listdir(job.images_dir) if not f.startswith(".")])
    job.frames = total - count
    return total


def make_thumb(job):
    thumb = os.path.join(job.dir, "thumb.jpg")
    if os.path.exists(thumb):
        return
    imgs = sorted(f for f in os.listdir(job.images_dir) if not f.startswith("."))
    if not imgs:
        return
    src = os.path.join(job.images_dir, imgs[len(imgs) // 2])
    run(["sips", "-s", "format", "jpeg", "-Z", "480", src, "--out", thumb])


FAIL_HINT = ("Object Capture konnte aus den Bildern kein Modell bilden. Meist zu wenig Überlappung oder Struktur: "
             "mehr Fotos rundum, Video langsamer filmen, gleichmäßiges Licht oder „Hohe Empfindlichkeit“ probieren.")


def run_tool(job, cmd, attempt_label):
    """Runs the photogrammetry tool once. Returns (ok, error_message)."""
    log(f"[{job.id}] reconstruct{attempt_label}:", " ".join(cmd[3:]))
    if os.path.isdir(job.output_dir):
        shutil.rmtree(job.output_dir)
    job.update(stage="Rekonstruiere 3D-Modell" + attempt_label, progress=0.1, eta=None)
    errlog = open(os.path.join(job.dir, "photogrammetry.log"), "a")
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=errlog, text=True, bufsize=1)
    job.proc = p
    error = None
    last_save = 0
    for line in p.stdout:
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        kind = ev.get("event")
        if kind == "progress":
            job.progress = 0.1 + 0.85 * float(ev.get("fraction", 0))
        elif kind == "info":
            if ev.get("eta") is not None:
                job.eta = float(ev["eta"])
        elif kind == "warn":
            job.warn(ev.get("message", ""))
        elif kind == "error":
            error = ev.get("message", "Fehler")
            errlog.write(line + "\n")
        if time.time() - last_save > 2:
            job.save()
            last_save = time.time()
    p.wait()
    job.proc = None
    errlog.close()
    out = job.outputs()
    ok = p.returncode == 0 and (out["usdz"] or out["obj"])
    return ok, error


def reconstruct(job, image_count):
    opts = job.options
    detail = opts.get("detail", "medium")
    if detail not in DETAILS:
        detail = "medium"
    ordering = opts.get("ordering", "auto")
    if ordering == "auto":
        ordering = "sequential" if job.videos > 0 and job.images == 0 else "unordered"
    masking = opts.get("masking") is not False
    sensitivity = "high" if opts.get("highSensitivity") else "normal"
    # Object Capture kann "raw" nicht als USDZ ausgeben -> nur OBJ, USDZ/GLB macht danach Blender
    outputs = "obj" if detail == "raw" else "both"

    other = "unordered" if ordering == "sequential" else "sequential"
    attempts = [(ordering, masking), (other, masking)]
    if masking:
        attempts.append((ordering, False))
    for i, (ordr, mask) in enumerate(attempts):
        if job.cancel_requested:
            return False
        cmd = [TOOL, job.images_dir, job.output_dir, "--detail", detail, "--ordering", ordr,
               "--sensitivity", sensitivity, "--outputs", outputs]
        if not mask:
            cmd.append("--no-masking")
        label = "" if i == 0 else f" (Versuch {i + 1})"
        ok, error = run_tool(job, cmd, label)
        if job.cancel_requested:
            return False
        if ok:
            if i > 0:
                job.warn("Erster Versuch fehlgeschlagen – geklappt mit "
                         + ("sortierter" if ordr == "sequential" else "unsortierter") + " Bildreihenfolge"
                         + ("" if mask else " ohne Hintergrund-Entfernung"))
            return True
        log(f"[{job.id}] attempt {i + 1} failed: {error}")
    raise RuntimeError(FAIL_HINT)


def convert_glb(job):
    blender = blender_path()
    obj_dir = os.path.join(job.output_dir, "obj")
    if not blender:
        job.warn("Blender nicht gefunden – kein GLB erzeugt")
        return
    job.update(stage="Konvertiere nach GLB", progress=0.96, eta=None)
    cmd = [blender, "-b", "--factory-startup", "--python", CONVERTER, "--",
           obj_dir, os.path.join(job.output_dir, "model.glb")]
    if not job.outputs()["usdz"]:
        cmd.append(os.path.join(job.output_dir, "model.usdz"))
    code, out = run(cmd, job)
    if code != 0 or "GLB_OK" not in out:
        log(f"[{job.id}] blender failed:", out[-800:])
        job.warn("GLB-Konvertierung fehlgeschlagen")


def process(job):
    job.update(state="processing", stage="Bereite Bilder vor", progress=0.01, started=time.time(), error=None)
    if os.path.isdir(job.images_dir):
        shutil.rmtree(job.images_dir)
    if os.path.isdir(job.output_dir):
        shutil.rmtree(job.output_dir)
    n = prepare_images(job)
    if job.cancel_requested:
        raise InterruptedError()
    if n < MIN_IMAGES:
        raise RuntimeError(f"Zu wenige Bilder ({n}). Mindestens {MIN_IMAGES}, besser 30–100 aus allen Richtungen.")
    job.message = f"{n} Bilder"
    make_thumb(job)
    if not reconstruct(job, n):
        raise InterruptedError()
    convert_glb(job)
    if job.cancel_requested:
        raise InterruptedError()
    # images are only an intermediate; drop them to save disk
    shutil.rmtree(job.images_dir, ignore_errors=True)
    job.update(state="done", stage="Fertig", progress=1.0, eta=None, finished=time.time())


def worker():
    while True:
        job = work_queue.get()
        if job.state != "queued":
            continue
        try:
            process(job)
            log(f"[{job.id}] done in {time.time() - job.started:.0f}s")
        except InterruptedError:
            job.update(state="cancelled", stage="Abgebrochen", eta=None, finished=time.time())
        except Exception as e:
            log(f"[{job.id}] failed:", e)
            job.update(state="failed", stage="Fehlgeschlagen", error=str(e), eta=None, finished=time.time())
        finally:
            job.proc = None


def queue_position(job):
    with jobs_lock:
        queued = sorted((j for j in jobs.values() if j.state == "queued"), key=lambda j: j.started or j.created)
    return next((i + 1 for i, j in enumerate(queued) if j.id == job.id), 0)


# ---------------------------------------------------------------- http

class QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return  # Handy hat Verbindung geschlossen – normal
        super().handle_error(request, client_address)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "Photo3D/" + VERSION

    def log_message(self, fmt, *args):
        if not self.path.startswith("/api/jobs") or self.command != "GET":
            log(self.client_address[0], fmt % args)

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error(self, code, msg):
        if self.command == "PUT":
            self.close_connection = True
        self.send_json({"error": msg}, code)

    def read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return {}

    def send_file(self, path, ctype, download_name=None):
        size = os.path.getsize(path)
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d*)-(\d*)", rng or "")
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if download_name:
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(1 << 20, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def get_job(self, jid):
        with jobs_lock:
            return jobs.get(jid)

    def route(self):
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        return parts

    # ---- GET
    def do_GET(self):
        p = self.route()
        if p == [] or p == ["index.html"]:
            return self.index()
        if p == ["api", "info"]:
            return self.send_json({"name": "Photo3D", "version": VERSION, "host": computer_name(),
                                   "supported": os.path.exists(TOOL), "blender": bool(blender_path())})
        if p == ["api", "jobs"]:
            with jobs_lock:
                lst = sorted(jobs.values(), key=lambda j: j.created, reverse=True)
            return self.send_json([self.job_dict(j) for j in lst])
        if len(p) >= 3 and p[:2] == ["api", "jobs"]:
            job = self.get_job(p[2])
            if not job:
                return self.error(404, "Job nicht gefunden")
            if len(p) == 3:
                return self.send_json(self.job_dict(job))
            f = p[3]
            out = job.output_dir
            safe = safe_name(job.name) or "model"
            if f == "model.glb" and job.outputs()["glb"]:
                return self.send_file(os.path.join(out, "model.glb"), "model/gltf-binary", safe + ".glb")
            if f == "model.usdz" and job.outputs()["usdz"]:
                return self.send_file(os.path.join(out, "model.usdz"), "model/vnd.usdz+zip", safe + ".usdz")
            if f == "model.zip" and job.outputs()["obj"]:
                zp = os.path.join(out, "model_obj.zip")
                if not os.path.exists(zp):
                    with zipfile.ZipFile(zp + ".tmp", "w", zipfile.ZIP_DEFLATED) as z:
                        for fn in os.listdir(os.path.join(out, "obj")):
                            z.write(os.path.join(out, "obj", fn), fn)
                    os.replace(zp + ".tmp", zp)
                return self.send_file(zp, "application/zip", safe + "_obj.zip")
            if f == "thumb.jpg" and os.path.exists(os.path.join(job.dir, "thumb.jpg")):
                return self.send_file(os.path.join(job.dir, "thumb.jpg"), "image/jpeg")
        return self.error(404, "Nicht gefunden")

    def job_dict(self, job):
        d = job.to_dict()
        if job.state == "queued":
            d["queuePosition"] = queue_position(job)
        return d

    # ---- POST
    def do_POST(self):
        p = self.route()
        if p == ["api", "jobs"]:
            body = self.read_json()
            job = Job(str(body.get("name") or "").strip()[:80])
            os.makedirs(job.input_dir, exist_ok=True)
            job.save()
            with jobs_lock:
                jobs[job.id] = job
            log(f"[{job.id}] created '{job.name}' from {self.client_address[0]}")
            return self.send_json(job.to_dict(), 201)
        if len(p) == 4 and p[:2] == ["api", "jobs"]:
            job = self.get_job(p[2])
            if not job:
                return self.error(404, "Job nicht gefunden")
            body = self.read_json()
            if p[3] == "start":
                if job.state in ("queued", "processing"):
                    return self.error(409, "Läuft bereits")
                files = os.listdir(job.input_dir) if os.path.isdir(job.input_dir) else []
                job.images = sum(os.path.splitext(f)[1].lower() in IMAGE_EXT | CONVERT_EXT for f in files)
                job.videos = sum(os.path.splitext(f)[1].lower() in VIDEO_EXT for f in files)
                if job.images + job.videos == 0:
                    return self.error(400, "Keine Fotos oder Videos hochgeladen")
                job.options = {k: body[k] for k in ("detail", "ordering", "highSensitivity", "masking", "framesPerVideo") if k in body}
                job.cancel_requested = False
                job.warnings = []
                job.update(state="queued", stage="In Warteschlange", progress=0.0, error=None, started=time.time())
                work_queue.put(job)
                return self.send_json(self.job_dict(job))
            if p[3] == "cancel":
                job.cancel_requested = True
                if job.proc and job.proc.poll() is None:
                    job.proc.terminate()
                if job.state in ("queued", "uploading"):
                    job.update(state="cancelled", stage="Abgebrochen")
                return self.send_json(self.job_dict(job))
            if p[3] == "rename":
                job.update(name=str(body.get("name") or job.name)[:80])
                return self.send_json(self.job_dict(job))
        return self.error(404, "Nicht gefunden")

    # ---- PUT (raw upload)
    def do_PUT(self):
        p = self.route()
        if len(p) == 5 and p[:2] == ["api", "jobs"] and p[3] == "files":
            job = self.get_job(p[2])
            if not job:
                return self.error(404, "Job nicht gefunden")
            if job.state not in ("uploading", "failed", "cancelled"):
                return self.error(409, "Job nimmt keine Dateien mehr an")
            name = safe_name(p[4])
            ext = os.path.splitext(name)[1].lower()
            if ext not in IMAGE_EXT | CONVERT_EXT | VIDEO_EXT:
                self.drain()
                return self.error(415, f"Dateityp {ext} nicht unterstützt")
            os.makedirs(job.input_dir, exist_ok=True)
            dst = os.path.join(job.input_dir, name)
            tmp = dst + ".part"
            written = 0
            try:
                with open(tmp, "wb") as f:
                    for chunk in self.body_chunks():
                        f.write(chunk)
                        written += len(chunk)
                os.replace(tmp, dst)
            except Exception as e:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                self.close_connection = True
                return self.error(400, f"Upload abgebrochen: {e}")
            if job.state != "uploading":
                job.update(state="uploading", stage="Warte auf Dateien")
            return self.send_json({"ok": True, "name": name, "size": written})
        return self.error(404, "Nicht gefunden")

    def body_chunks(self):
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            while True:
                size_line = self.rfile.readline(1024).strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    while self.rfile.readline(1024).strip():
                        pass
                    return
                left = size
                while left > 0:
                    chunk = self.rfile.read(min(left, 1 << 20))
                    if not chunk:
                        raise IOError("Verbindung unterbrochen")
                    left -= len(chunk)
                    yield chunk
                self.rfile.readline(16)
        else:
            left = int(self.headers.get("Content-Length") or 0)
            while left > 0:
                chunk = self.rfile.read(min(left, 1 << 20))
                if not chunk:
                    raise IOError("Verbindung unterbrochen")
                left -= len(chunk)
                yield chunk

    def drain(self):
        try:
            for _ in self.body_chunks():
                pass
        except Exception:
            self.close_connection = True

    # ---- DELETE
    def do_DELETE(self):
        p = self.route()
        if len(p) == 3 and p[:2] == ["api", "jobs"]:
            job = self.get_job(p[2])
            if not job:
                return self.error(404, "Job nicht gefunden")
            job.cancel_requested = True
            if job.proc and job.proc.poll() is None:
                job.proc.terminate()
            with jobs_lock:
                jobs.pop(job.id, None)
            job.state = "deleted"
            shutil.rmtree(job.dir, ignore_errors=True)
            return self.send_json({"ok": True})
        return self.error(404, "Nicht gefunden")

    def index(self):
        with jobs_lock:
            lst = sorted(jobs.values(), key=lambda j: j.created, reverse=True)
        rows = "".join(
            f"<tr><td>{j.name}</td><td>{j.stage}</td><td>{int(j.progress * 100)}%</td><td>"
            + "".join(f' <a href="/api/jobs/{j.id}/{f}">{f.split(".")[1].upper()}</a>'
                      for f, k in (("model.glb", "glb"), ("model.usdz", "usdz"), ("model.zip", "obj")) if j.outputs()[k])
            + "</td></tr>" for j in lst)
        html = f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width">
<meta http-equiv=refresh content=5><title>Photo3D Server</title>
<style>body{{font:15px system-ui;margin:24px;color:#222}}td{{padding:4px 10px;border-bottom:1px solid #ddd}}</style>
<h2>Photo3D Server läuft</h2><p>Host: {computer_name()} · Port {self.server.server_address[1]}</p>
<table><tr><th>Name</th><th>Status</th><th></th><th>Dateien</th></tr>{rows}</table>"""
        body = html.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


# ---------------------------------------------------------------- main

def load_jobs():
    os.makedirs(DATA, exist_ok=True)
    for d in os.listdir(DATA):
        jp = os.path.join(DATA, d, "job.json")
        if not os.path.exists(jp):
            continue
        try:
            job = Job.load(jp)
        except Exception:
            continue
        if job.state == "processing":
            job.update(state="failed", stage="Unterbrochen", error="Server wurde während der Verarbeitung beendet. Neu starten möglich.")
        jobs[job.id] = job
    for job in sorted((j for j in jobs.values() if j.state == "queued"), key=lambda j: j.started or j.created):
        work_queue.put(job)


def computer_name():
    try:
        n = subprocess.run(["scutil", "--get", "ComputerName"], capture_output=True, text=True).stdout.strip()
    except OSError:
        n = ""
    return n or socket.gethostname().split(".")[0]


def lan_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(ips) or ["127.0.0.1"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--name", default=None, help="Name, der in der App angezeigt wird")
    args = ap.parse_args()

    if not os.path.exists(TOOL):
        sys.exit("photogrammetry-Tool fehlt. Erst ./start.sh ausführen (baut es).")
    load_jobs()
    threading.Thread(target=worker, daemon=True).start()

    try:
        srv = QuietServer(("0.0.0.0", args.port), Handler)
    except OSError as e:
        if e.errno == 48:  # EADDRINUSE
            sys.exit(f"Port {args.port} ist schon belegt – läuft der Photo3D-Server bereits (z. B. per Autostart)?\n"
                     f"Prüfen:   lsof -nP -iTCP:{args.port} -sTCP:LISTEN\n"
                     f"Anderer Port:   ./start.sh --port 8766   (in der App dann IP:8766 eingeben)")
        raise
    srv.daemon_threads = True
    name = args.name or computer_name()
    bonjour = None
    try:
        bonjour = subprocess.Popen(["dns-sd", "-R", name, "_photo3d._tcp", "local", str(args.port), "v=" + VERSION],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except FileNotFoundError:
        log("dns-sd nicht gefunden – keine automatische Erkennung")

    def stop(*_):
        if bonjour:
            bonjour.terminate()
        for j in list(jobs.values()):
            if j.proc and j.proc.poll() is None:
                j.proc.terminate()
        os._exit(0)

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    log(f"Photo3D Server '{name}' läuft auf:")
    for ip in lan_ips():
        log(f"   http://{ip}:{args.port}")
    log("Blender:", blender_path() or "nicht gefunden (kein GLB)")
    srv.serve_forever()


if __name__ == "__main__":
    main()
