#!/usr/bin/env python3
import argparse
import json
import os
import random
import re
import shlex
import shutil
import signal
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
DATA_DIR = ROOT / "data"
MAIN_PY = ROOT / "main.py"
UI_DIR = ROOT / "ui"
RUNNING_JOBS = {}
JOBS_LOCK = threading.Lock()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def utc_from_epoch_ms(epoch_ms):
    if not epoch_ms:
        return None
    try:
        return datetime.fromtimestamp(epoch_ms / 1000, timezone.utc).isoformat()
    except Exception:
        return None


def read_json(path: Path):
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def tail_lines(path: Path, limit: int):
    if not path.exists():
        return []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return lines[-limit:]


def load_job_state(job_id: str):
    return read_json(DATA_DIR / job_id / "state.json")


def append_runtime_log(job_id: str, message: str):
    log_path = DATA_DIR / job_id / "runtime.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as f:
        f.write(f"{now_iso()} {message}\n")


def max_urls_path(job_id: str) -> Path:
    return DATA_DIR / job_id / "max_urls.txt"


def persist_max_urls(job_id: str, max_urls):
    if max_urls is None:
        return
    p = max_urls_path(job_id)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(str(int(max_urls)), encoding="utf-8")


def read_persisted_max_urls(job_id: str):
    p = max_urls_path(job_id)
    if not p.exists():
        return None
    try:
        v = int(p.read_text(encoding="utf-8").strip())
        return v if v > 0 else None
    except Exception:
        return None


def parse_job_id_from_output(output: str):
    m = re.search(r"job_id=([a-f0-9-]{36})", output, re.IGNORECASE)
    return m.group(1) if m else None


def kill_job_process(proc: subprocess.Popen, wait_timeout: float = 10):
    """Stop the crawl OS process tree. main.py execs java (same PID), so terminate targets the JVM; killpg covers any extra children."""
    if not proc or proc.poll() is not None:
        return
    pid = proc.pid
    pgid = None
    if hasattr(os, "killpg"):
        try:
            pgid = os.getpgid(pid)
        except OSError:
            pass
    try:
        if pgid is not None:
            os.killpg(pgid, signal.SIGTERM)
        else:
            proc.terminate()
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.terminate()
        except ProcessLookupError:
            return
    try:
        proc.wait(timeout=wait_timeout)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        if pgid is not None:
            os.killpg(pgid, signal.SIGKILL)
        else:
            proc.kill()
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def start_job_process(command_args, maybe_job_id=None):
    cmd = ["python3", str(MAIN_PY)] + command_args
    popen_kw = dict(
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    if os.name != "nt":
        popen_kw["start_new_session"] = True
    proc = subprocess.Popen(cmd, **popen_kw)
    holder = {"job_id": maybe_job_id}
    lines = []

    def pump():
        while True:
            line = proc.stdout.readline()
            if not line:
                break
            stripped = line.rstrip("\n")
            lines.append(stripped)
            if holder["job_id"] is None:
                parsed = parse_job_id_from_output(stripped)
                if parsed:
                    holder["job_id"] = parsed
            active_job_id = holder["job_id"] or maybe_job_id
            if active_job_id:
                out_path_local = DATA_DIR / active_job_id / "runtime.log"
                out_path_local.parent.mkdir(parents=True, exist_ok=True)
                with out_path_local.open("a", encoding="utf-8") as logf:
                    logf.write(stripped + "\n")
        proc.wait()
        active_job_id = holder["job_id"] or maybe_job_id
        if active_job_id:
            with JOBS_LOCK:
                record = RUNNING_JOBS.get(active_job_id)
                if record and record.get("proc") is proc:
                    record["endedAt"] = now_iso()
                    record["exitCode"] = proc.returncode

    t = threading.Thread(target=pump, daemon=True)
    t.start()
    return proc, holder, lines


def build_status_payload(job_id: str):
    state = load_job_state(job_id)
    if not state:
        return None
    with JOBS_LOCK:
        running = RUNNING_JOBS.get(job_id)
        is_running = bool(running and running.get("proc") and running["proc"].poll() is None)
        inmem_max = running.get("maxUrls") if running else None
    max_urls = inmem_max if inmem_max is not None else read_persisted_max_urls(job_id)
    reached_cap = bool(max_urls is not None and len(state.get("visited") or []) >= max_urls)
    if is_running:
        status = "RUNNING"
    elif state.get("finished") or reached_cap:
        status = "FINISHED"
    elif state.get("stopRequested"):
        status = "STOPPED"
    else:
        status = "IDLE"
    return {
        "jobId": state.get("jobId", job_id),
        "status": status,
        "originUrl": state.get("originUrl"),
        "maxDepth": state.get("maxDepth"),
        "hitRatePerSec": state.get("workerCount"),
        "urlsVisited": len(state.get("visited") or []),
        "pagesFetched": state.get("pagesFetched", 0),
        "queueSize": len(state.get("frontier") or []),
        "startTime": utc_from_epoch_ms(state.get("createdAtEpochMs")),
        "lastUpdateTime": utc_from_epoch_ms(state.get("updatedAtEpochMs")),
        "finished": bool(state.get("finished") or reached_cap),
        "stopRequested": bool(state.get("stopRequested")),
    }


def global_stats():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    total_urls = 0
    words = 0
    total_jobs = 0
    active = 0
    with JOBS_LOCK:
        active = sum(1 for r in RUNNING_JOBS.values() if r.get("proc") and r["proc"].poll() is None)
    for job_dir in DATA_DIR.iterdir():
        if not job_dir.is_dir():
            continue
        state = read_json(job_dir / "state.json")
        if state:
            total_jobs += 1
            total_urls += len(state.get("visited") or [])
        p_data = job_dir / "p.data"
        if p_data.exists():
            with p_data.open("r", encoding="utf-8", errors="replace") as f:
                for line in f:
                    if line.strip():
                        words += 1
    return {
        "urlsVisited": total_urls,
        "wordsInDb": words,
        "activeCrawlers": active,
        "totalCreatedCrawlers": total_jobs,
    }


def search_index(query: str):
    needle = (query or "").strip().lower()
    if not re.fullmatch(r"[a-z]+", needle):
        return []
    scores = {}
    for job_dir in DATA_DIR.iterdir():
        if not job_dir.is_dir():
            continue
        p_data = job_dir / "p.data"
        if not p_data.exists():
            continue
        with p_data.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) != 5:
                    continue
                word, url, origin, depth_raw, freq_raw = parts
                lw = word.lower()
                if not re.fullmatch(r"[a-z]+", lw):
                    continue
                if lw != needle:
                    continue
                try:
                    depth = int(depth_raw)
                    freq = int(freq_raw)
                except ValueError:
                    continue
                key = (url, origin, depth)
                rec = scores.setdefault(
                    key,
                    {
                        "url": url,
                        "originUrl": origin,
                        "depth": depth,
                        "frequency": 0,
                        "exactMatch": True,
                    },
                )
                rec["frequency"] += freq
    results = []
    for rec in scores.values():
        rec["score"] = rec["frequency"] * 10 + 1000 - rec["depth"] * 5
        results.append(rec)
    return results


def count_non_empty_lines(path: Path):
    if not path.exists():
        return 0
    n = 0
    with path.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.strip():
                n += 1
    return n


def list_crawlers_payload():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    crawlers = []
    for job_dir in DATA_DIR.iterdir():
        if not job_dir.is_dir():
            continue
        job_id = job_dir.name
        state = load_job_state(job_id)
        if not state:
            continue
        base = build_status_payload(job_id)
        if not base:
            continue
        base["wordCount"] = count_non_empty_lines(job_dir / "p.data")
        base["pagesCount"] = count_non_empty_lines(job_dir / "pages.jsonl")
        with JOBS_LOCK:
            rec = RUNNING_JOBS.get(job_id) or {}
        base["maxUrls"] = rec.get("maxUrls")
        if base["maxUrls"] is None:
            base["maxUrls"] = read_persisted_max_urls(job_id)
        crawlers.append(base)
    crawlers.sort(key=lambda c: c.get("lastUpdateTime") or "", reverse=True)
    return {"crawlers": crawlers}


def to_int(value, default):
    try:
        if value is None or value == "":
            return default
        return int(value)
    except (TypeError, ValueError):
        return default


def to_optional_int(value):
    if value is None or value == "":
        return None
    try:
        parsed = int(value)
        return parsed if parsed > 0 else None
    except (TypeError, ValueError):
        return None


def enforce_max_urls_loop():
    while True:
        time.sleep(1.5)
        with JOBS_LOCK:
            snapshot = [(jid, rec.get("proc"), rec.get("maxUrls")) for jid, rec in RUNNING_JOBS.items()]
        for job_id, proc, max_urls in snapshot:
            if not proc or proc.poll() is not None or max_urls is None:
                continue
            state = load_job_state(job_id)
            if not state:
                continue
            visited_count = len(state.get("visited") or [])
            if visited_count >= max_urls:
                try:
                    kill_job_process(proc)
                finally:
                    # Mark job as finished in persisted state so UI shows FINISHED.
                    try:
                        state_path = DATA_DIR / job_id / "state.json"
                        if state_path.exists():
                            state["finished"] = True
                            state["stopRequested"] = True
                            state["updatedAtEpochMs"] = int(time.time() * 1000)
                            state_path.write_text(json.dumps(state), encoding="utf-8")
                    except Exception:
                        pass
                    append_runtime_log(job_id, f"auto_finish=maxUrls reached ({max_urls})")


def random_index_word():
    candidate = None
    seen = 0
    for job_dir in DATA_DIR.iterdir():
        if not job_dir.is_dir():
            continue
        p_data = job_dir / "p.data"
        if not p_data.exists():
            continue
        with p_data.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = line.strip().split()
                if len(parts) != 5:
                    continue
                word = parts[0].lower()
                if not re.fullmatch(r"[a-z]+", word):
                    continue
                seen += 1
                if random.randint(1, seen) == 1:
                    candidate = word
    return candidate


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "CrawlerLocalApi/1.0"

    def log_message(self, format, *args):
        pass

    def _json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.end_headers()
        self.wfile.write(body)

    def _html(self, code, content):
        raw = content.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _read_body(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        raw = self.rfile.read(length).decode("utf-8")
        return json.loads(raw) if raw else {}

    def do_OPTIONS(self):
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/ui", "/ui/"):
            index = (UI_DIR / "crawler.html").read_text(encoding="utf-8")
            self._html(200, index)
            return
        if path.startswith("/ui/"):
            rel = path.removeprefix("/ui/").lstrip("/")
            if not rel or ".." in Path(rel).parts:
                self._json(404, {"error": "UI file not found"})
                return
            file_path = (UI_DIR / rel).resolve()
            try:
                file_path.relative_to(UI_DIR.resolve())
            except ValueError:
                self._json(404, {"error": "UI file not found"})
                return
            if not file_path.is_file():
                self._json(
                    404,
                    {
                        "error": "UI file not found",
                        "detail": "Serve static files from the ui/ folder next to local_api.py.",
                        "tried": str(file_path),
                        "uiRoot": str(UI_DIR.resolve()),
                    },
                )
                return
            content = file_path.read_bytes()
            mime = "text/plain"
            if file_path.suffix == ".html":
                mime = "text/html; charset=utf-8"
            elif file_path.suffix == ".css":
                mime = "text/css; charset=utf-8"
            elif file_path.suffix == ".js":
                mime = "application/javascript; charset=utf-8"
            elif file_path.suffix == ".png":
                mime = "image/png"
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
            return

        if path == "/api/stats/global":
            self._json(200, global_stats())
            return
        if path == "/api/crawlers":
            self._json(200, list_crawlers_payload())
            return

        if path == "/api/search" or path == "/search":
            q = parse_qs(parsed.query)
            if path == "/search":
                query = (q.get("query") or q.get("q") or [""])[0].strip()
                sort = (q.get("sortBy") or q.get("sort") or ["relevance"])[0]
            else:
                query = (q.get("q") or [""])[0].strip()
                sort = (q.get("sort") or ["relevance"])[0]
            page = max(int((q.get("page") or ["1"])[0]), 1)
            size = max(min(int((q.get("size") or ["10"])[0]), 100), 1)
            results = search_index(query)
            if sort == "frequency":
                results.sort(key=lambda x: x["frequency"], reverse=True)
            elif sort == "depth":
                results.sort(key=lambda x: x["depth"])
            else:
                results.sort(key=lambda x: x["score"], reverse=True)
            start = (page - 1) * size
            paged = results[start : start + size]
            self._json(200, {"query": query, "total": len(results), "results": paged})
            return

        if path == "/api/search/random-word":
            word = random_index_word()
            if not word:
                self._json(404, {"error": "No indexed words found"})
                return
            self._json(200, {"word": word})
            return

        m = re.match(r"^/api/crawlers/([a-f0-9-]{36})/status$", path, re.IGNORECASE)
        if m:
            payload = build_status_payload(m.group(1))
            if not payload:
                self._json(404, {"error": "Crawler not found"})
                return
            self._json(200, payload)
            return

        m = re.match(r"^/api/crawlers/([a-f0-9-]{36})/queue$", path, re.IGNORECASE)
        if m:
            state = load_job_state(m.group(1))
            if not state:
                self._json(404, {"error": "Crawler not found"})
                return
            self._json(200, {"items": state.get("frontier") or []})
            return

        m = re.match(r"^/api/crawlers/([a-f0-9-]{36})/logs$", path, re.IGNORECASE)
        if m:
            lines = tail_lines(DATA_DIR / m.group(1) / "runtime.log", 200)
            self._json(200, {"lines": lines})
            return

        self._json(404, {"error": "Not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/crawlers":
            body = self._read_body()
            seed_url = (body.get("seedUrl") or "").strip()
            depth = to_int(body.get("depthLimit"), 2)
            workers = to_int(body.get("hitRatePerSec"), 8)
            queue_capacity = to_int(body.get("queueCapacity"), 1000)
            max_urls = to_optional_int(body.get("maxUrls"))
            if not seed_url:
                self._json(400, {"error": "seedUrl is required"})
                return
            if depth < 0 or depth > 6:
                self._json(400, {"error": "depthLimit must be between 0 and 6"})
                return
            if workers < 1 or workers > 32:
                self._json(400, {"error": "hitRatePerSec(workers) must be between 1 and 32"})
                return
            if queue_capacity < 50 or queue_capacity > 20000:
                self._json(400, {"error": "queueCapacity must be between 50 and 20000"})
                return
            if max_urls is not None and (max_urls < 1 or max_urls > 1000000):
                self._json(400, {"error": "maxUrls must be between 1 and 1000000"})
                return
            proc, holder, lines = start_job_process(
                ["index", seed_url, str(depth), str(workers), str(queue_capacity)]
            )
            deadline = time.time() + 8
            while time.time() < deadline and holder["job_id"] is None and proc.poll() is None:
                time.sleep(0.05)
            if holder["job_id"] is None:
                self._json(500, {"error": "Failed to create crawl job", "output": lines[-20:]})
                return
            with JOBS_LOCK:
                RUNNING_JOBS[holder["job_id"]] = {
                    "proc": proc,
                    "startedAt": now_iso(),
                    "maxUrls": max_urls,
                    "command": shlex.join(
                        ["python3", str(MAIN_PY), "index", seed_url, str(depth), str(workers), str(queue_capacity)]
                    ),
                }
            persist_max_urls(holder["job_id"], max_urls)
            append_runtime_log(
                holder["job_id"],
                f"job_started seed={seed_url} depth={depth} workers={workers} queue={queue_capacity} maxUrls={max_urls}",
            )
            self._json(
                200,
                {
                    "jobId": holder["job_id"],
                    "initialState": "RUNNING",
                    "startTimestamp": now_iso(),
                    "maxUrls": max_urls,
                },
            )
            return

        m = re.match(r"^/api/crawlers/([a-f0-9-]{36})/(pause|stop|resume|cancel|delete)$", path, re.IGNORECASE)
        if m:
            job_id = m.group(1)
            action = m.group(2).lower()

            if action == "delete":
                with JOBS_LOCK:
                    rec = RUNNING_JOBS.get(job_id)
                    proc = rec.get("proc") if rec else None
                if proc and proc.poll() is None:
                    self._json(409, {"error": "Crawler is running. Stop or cancel before delete."})
                    return
                job_dir = DATA_DIR / job_id
                if not job_dir.exists():
                    self._json(404, {"error": "Crawler not found"})
                    return
                shutil.rmtree(job_dir, ignore_errors=True)
                with JOBS_LOCK:
                    RUNNING_JOBS.pop(job_id, None)
                self._json(200, {"jobId": job_id, "action": "delete", "status": "DELETED"})
                return

            if action == "cancel":
                with JOBS_LOCK:
                    rec = RUNNING_JOBS.get(job_id)
                    proc = rec.get("proc") if rec else None
                if proc and proc.poll() is None:
                    kill_job_process(proc)
                append_runtime_log(job_id, "manual_cancel_requested -> deleting job files")
                job_dir = DATA_DIR / job_id
                if job_dir.exists():
                    shutil.rmtree(job_dir, ignore_errors=True)
                with JOBS_LOCK:
                    RUNNING_JOBS.pop(job_id, None)
                self._json(200, {"jobId": job_id, "action": "cancel", "status": "DELETED"})
                return

            if action in ("pause", "stop"):
                with JOBS_LOCK:
                    rec = RUNNING_JOBS.get(job_id)
                    proc = rec.get("proc") if rec else None
                if proc and proc.poll() is None:
                    kill_job_process(proc)
                append_runtime_log(job_id, f"manual_{action}_requested")
                self._json(200, {"jobId": job_id, "action": action, "status": "STOPPED"})
                return

            if action == "resume":
                state = load_job_state(job_id)
                if not state:
                    self._json(404, {"error": "Crawler not found"})
                    return
                with JOBS_LOCK:
                    rec = RUNNING_JOBS.get(job_id)
                    if rec and rec.get("proc") and rec["proc"].poll() is None:
                        self._json(409, {"error": "Crawler is already running"})
                        return
                proc, _, _ = start_job_process(["resume", job_id], maybe_job_id=job_id)
                with JOBS_LOCK:
                    RUNNING_JOBS[job_id] = {
                        "proc": proc,
                        "startedAt": now_iso(),
                        "maxUrls": (rec.get("maxUrls") if rec else None) or read_persisted_max_urls(job_id),
                        "command": shlex.join(["python3", str(MAIN_PY), "resume", job_id]),
                    }
                append_runtime_log(job_id, "manual_resume_requested")
                self._json(200, {"jobId": job_id, "action": "resume", "status": "RUNNING"})
                return

        self._json(404, {"error": "Not found"})


def main():
    parser = argparse.ArgumentParser(description="Local API for crawler UI")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3600)
    args = parser.parse_args()

    if not UI_DIR.is_dir():
        print(
            f"Missing UI folder: {UI_DIR.resolve()}\n"
            "Use the full crawler project directory (must contain ui/, main.py, pom.xml).",
            file=sys.stderr,
        )
        sys.exit(1)

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=enforce_max_urls_loop, daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), ApiHandler)
    print(f"Local API: http://{args.host}:{args.port}")
    print(f"UI: http://{args.host}:{args.port}/ui/crawler.html")
    print(f"Search compat: http://{args.host}:{args.port}/search?query=cat&sortBy=relevance")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        with JOBS_LOCK:
            for rec in RUNNING_JOBS.values():
                proc = rec.get("proc")
                if proc and proc.poll() is None:
                    kill_job_process(proc)


if __name__ == "__main__":
    main()
