#!/data/data/com.agy/files/usr/bin/python3
"""
agy-daemon - Ultra-lightweight background scheduler & autonomous watcher for Antigravity (AGY v2).
Zero emojis. Minimal memory footprint (<10MB). Runs smoothly with screen off under partial wakelock.

Triggers supported:
  --delay <duration>        Run task after delay (e.g. 4h, 30m, 10s)
  --interval <duration>     Run task periodically (e.g. 15m, 1h)
  --cron "<5-field cron>"   Standard cron schedule (e.g. "0 3 * * *")
  --github-pr <owner/repo>  Watch GitHub repository for new pull requests
  --github-issue <owner/repo> Watch GitHub repository for new issues
  --watch-file <path>       Trigger task whenever file or directory changes
  --battery-below <percent> Trigger task when battery falls below percentage
  --webhook-port <port>     Start a lightweight HTTP webhook receiver for incoming triggers
  --wake-app                Bring Antigravity workspace to foreground upon completion
"""

import sys
import os
import time
import json
import signal
import shutil
import argparse
import subprocess
import urllib.request
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler
import threading

CONFIG_DIR = os.path.expanduser("~/.gemini/antigravity-app/daemon")
TASKS_FILE = os.path.join(CONFIG_DIR, "tasks.json")
LOG_FILE = os.path.join(CONFIG_DIR, "daemon.log")
LOG_MAX_BYTES = 1024 * 1024
PID_FILE = os.path.join(CONFIG_DIR, "daemon.pid")
_LOG_LOCK = threading.Lock()
_RUNNING_LOCK = threading.Lock()
_RUNNING_TASKS = set()

def _files_dir():
    prefix = os.environ.get("PREFIX")
    if prefix:
        return os.path.dirname(os.path.abspath(prefix))
    home = os.environ.get("HOME")
    if home and home.endswith("/files"):
        return home
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def _app_pkg():
    parts = _files_dir().replace("\\", "/").split("/")
    for p in reversed(parts):
        if p.startswith("com."):
            return p
    return "com.agy"

def _find_rish():
    fdir = _files_dir()
    for cand in (
        os.path.join(fdir, "runtime", "bin", "rish"),
        os.path.join(fdir, "usr", "bin", "rish"),
        os.path.join(fdir, "bin", "rish"),
    ):
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
    return shutil.which("rish")

def log(msg):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    formatted = f"[{ts}] {msg}"
    with _LOG_LOCK:
        print(formatted, flush=True)
        try:
            os.makedirs(CONFIG_DIR, exist_ok=True)
            if os.path.isfile(LOG_FILE) and os.path.getsize(LOG_FILE) >= LOG_MAX_BYTES:
                os.replace(LOG_FILE, LOG_FILE + ".1")
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(formatted + "\n")
        except Exception:
            pass

def notify(title, message, tag=None):
    log(f"Notification: {title} - {message}")
    cmd = ["agy-notify", "post", "-t", title, "-m", message]
    if tag:
        cmd.extend(["--tag", tag])
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        log(f"Notification failed: {e}")

def load_tasks():
    if not os.path.isfile(TASKS_FILE):
        return []
    try:
        with open(TASKS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:
        log(f"Error loading tasks: {e}")
        return []

def save_tasks(tasks):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp_path = TASKS_FILE + ".tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(tasks, f, indent=2, ensure_ascii=False)
    os.replace(tmp_path, TASKS_FILE)

def wake_antigravity_app():
    log("Waking Antigravity activity to foreground...")
    pkg = _app_pkg()
    component = f"{pkg}/com.agy.ui.MainActivity"
    try:
        rish = _find_rish()
        if rish:
            subprocess.run([rish, "-c", f"am start -n {component}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        else:
            subprocess.run(["am", "start", "-n", component], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:
        log(f"Cannot wake app: {e}")

def execute_action(task):
    command = task.get("command", "")
    task_id = task.get("id", "unknown")
    name = task.get("name", task_id)
    wake_app = task.get("wake_app", False)

    log(f"Executing task [{name}] (id: {task_id}): {command}")
    notify("Antigravity Daemon", f"Running task: {name}", tag=f"task_{task_id}")

    start_t = time.time()
    success = False
    output = ""
    try:
        res = subprocess.run(
            command,
            shell=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=task.get("timeout_sec", 600)
        )
        success = (res.returncode == 0)
        output = res.stdout.strip()
    except subprocess.TimeoutExpired:
        output = "Task timed out."
        log(f"Task [{name}] timed out.")
    except Exception as e:
        output = f"Execution error: {e}"
        log(f"Task [{name}] failed: {e}")

    duration = int(time.time() - start_t)
    status_str = "Completed successfully" if success else "Failed"
    log(f"Task [{name}] {status_str} in {duration}s. Output summary: {output[:200]}")
    
    notify(
        f"Task {status_str}: {name}",
        f"Finished in {duration}s. {output[:120]}",
        tag=f"task_done_{task_id}"
    )

    if wake_app:
        wake_antigravity_app()

def dispatch_task(task):
    task_id = task.get("id", "unknown")
    with _RUNNING_LOCK:
        if task_id in _RUNNING_TASKS:
            log(f"Skipping overlapping trigger for task [{task.get('name', task_id)}] (already running).")
            return
        _RUNNING_TASKS.add(task_id)

    def _worker(t_copy):
        try:
            execute_action(t_copy)
        finally:
            with _RUNNING_LOCK:
                _RUNNING_TASKS.discard(task_id)

    worker = threading.Thread(target=_worker, args=(dict(task),), daemon=True)
    worker.start()

def check_github_repo(trigger_info, endpoint_type="pulls"):
    repo = trigger_info.get("repo")
    if not repo:
        return False
    token = os.environ.get("GITHUB_TOKEN") or trigger_info.get("token")
    url = f"https://api.github.com/repos/{repo}/{endpoint_type}?state=open"
    headers = {"User-Agent": "Antigravity-Daemon", "Accept": "application/vnd.github.v3+json"}
    if token:
        headers["Authorization"] = f"token {token}"
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        
        last_count_key = f"last_{endpoint_type}_count"
        last_count = trigger_info.get(last_count_key)
        current_count = len(data)
        trigger_info[last_count_key] = current_count
        trigger_info["last_check_ts"] = time.time()

        if last_count is not None and current_count > last_count:
            item = data[0] if data else {}
            title = item.get("title", f"New {endpoint_type}")
            log(f"GitHub trigger fired for {repo} ({endpoint_type}): '{title}'")
            return True
    except Exception as e:
        log(f"GitHub check error for {repo}: {e}")
    return False

def check_file_watcher(trigger_info):
    target_path = trigger_info.get("path")
    if not target_path or not os.path.exists(target_path):
        return False
    try:
        last_mtime = trigger_info.get("last_mtime", 0)
        curr_mtime = os.path.getmtime(target_path)
        if last_mtime == 0:
            trigger_info["last_mtime"] = curr_mtime
            return False
        if curr_mtime > last_mtime:
            trigger_info["last_mtime"] = curr_mtime
            log(f"File watch trigger fired for: {target_path}")
            return True
    except Exception as e:
        log(f"File watch error: {e}")
    return False

def check_battery_condition(trigger_info):
    target_level = trigger_info.get("below_level")
    if target_level is None:
        return False
    try:
        rish = _find_rish()
        if not rish:
            return False
        p = subprocess.run([rish, "-c", "dumpsys battery"], stdout=subprocess.PIPE, text=True, timeout=5)
        for line in p.stdout.splitlines():
            if "level:" in line:
                val = int(line.split(":", 1)[1].strip())
                return val <= target_level
    except Exception:
        pass
    return False

def _cron_field_matches(field, value):
    for part in str(field).split(","):
        part = part.strip()
        step = 1
        if "/" in part:
            part, step_str = part.split("/", 1)
            step = max(1, int(step_str))
        if part == "*":
            if value % step == 0:
                return True
        elif "-" in part:
            lo, hi = part.split("-", 1)
            if int(lo) <= value <= int(hi) and (value - int(lo)) % step == 0:
                return True
        elif int(part) == value:
            return True
    return False

def check_cron_schedule(trigger_info, now):
    expr = trigger_info.get("cron", "")
    fields = expr.split()
    if len(fields) != 5:
        return False
    lt = time.localtime(now)
    minute_key = time.strftime("%Y-%m-%d %H:%M", lt)
    if trigger_info.get("last_cron_minute") == minute_key:
        return False
    cron_dow = (lt.tm_wday + 1) % 7
    try:
        if (
            _cron_field_matches(fields[0], lt.tm_min)
            and _cron_field_matches(fields[1], lt.tm_hour)
            and _cron_field_matches(fields[2], lt.tm_mday)
            and _cron_field_matches(fields[3], lt.tm_mon)
            and (_cron_field_matches(fields[4], cron_dow) or (cron_dow == 0 and _cron_field_matches(fields[4], 7)))
        ):
            trigger_info["last_cron_minute"] = minute_key
            return True
    except Exception as e:
        log(f"Cron parse error for '{expr}': {e}")
    return False

def start_webhook_listener(port):
    class WebhookHandler(BaseHTTPRequestHandler):
        def do_POST(self):
            content_length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(content_length).decode("utf-8")
            log(f"Incoming webhook on port {port}: {body[:200]}")
            
            # Check for triggered tasks
            tasks = load_tasks()
            fired = False
            for t in tasks:
                if t.get("enabled", True) and t.get("trigger", {}).get("type") == "webhook":
                    dispatch_task(t)
                    fired = True

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "fired": fired}).encode("utf-8"))

        def log_message(self, format, *args):
            pass

    server = HTTPServer(("127.0.0.1", port), WebhookHandler)
    log(f"Local webhook receiver listening on http://127.0.0.1:{port}")
    server.serve_forever()

def daemon_loop():
    log("Antigravity background micro-daemon running.")
    webhook_threads = {}

    while True:
        try:
            tasks = load_tasks()
            now = time.time()
            changed = False

            for task in tasks:
                if not task.get("enabled", True):
                    continue

                trigger = task.get("trigger", {})
                ttype = trigger.get("type", "once")

                should_run = False

                if ttype == "once" or ttype == "delay":
                    run_at = trigger.get("run_at", 0)
                    if now >= run_at and not task.get("executed", False):
                        should_run = True
                        task["executed"] = True
                        task["enabled"] = False
                        changed = True

                elif ttype == "interval":
                    interval = trigger.get("interval_sec", 300)
                    last_run = trigger.get("last_run", 0)
                    if (now - last_run) >= interval:
                        should_run = True
                        trigger["last_run"] = now
                        changed = True

                elif ttype == "cron":
                    if check_cron_schedule(trigger, now):
                        should_run = True
                        changed = True

                elif ttype == "github_pr":
                    interval = trigger.get("interval_sec", 180)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        if check_github_repo(trigger, "pulls"):
                            should_run = True
                        changed = True

                elif ttype == "github_issue":
                    interval = trigger.get("interval_sec", 180)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        if check_github_repo(trigger, "issues"):
                            should_run = True
                        changed = True

                elif ttype == "watch_file":
                    interval = trigger.get("interval_sec", 15)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        trigger["last_check_ts"] = now
                        if check_file_watcher(trigger):
                            should_run = True
                        changed = True

                elif ttype == "battery":
                    interval = trigger.get("interval_sec", 300)
                    last_check = trigger.get("last_check_ts", 0)
                    if (now - last_check) >= interval:
                        trigger["last_check_ts"] = now
                        if check_battery_condition(trigger):
                            should_run = True
                            task["enabled"] = False
                        changed = True

                elif ttype == "webhook":
                    port = trigger.get("port", 45199)
                    if port not in webhook_threads:
                        t = threading.Thread(target=start_webhook_listener, args=(port,), daemon=True)
                        t.start()
                        webhook_threads[port] = t

                if should_run:
                    dispatch_task(task)

            if changed:
                save_tasks(tasks)

        except Exception as e:
            log(f"Loop error: {e}")

        time.sleep(10)

def parse_duration(s):
    s = str(s).strip()
    if s.endswith("h"):
        return int(float(s[:-1]) * 3600)
    elif s.endswith("m"):
        return int(float(s[:-1]) * 60)
    elif s.endswith("s"):
        return int(float(s[:-1]))
    return int(s)

def cmd_add(args):
    tasks = load_tasks()
    task_id = f"job_{int(time.time())}"
    trigger = {}

    if args.delay:
        delay_sec = parse_duration(args.delay)
        trigger = {
            "type": "delay",
            "run_at": time.time() + delay_sec,
            "delay_sec": delay_sec
        }
    elif args.interval:
        interval_sec = parse_duration(args.interval)
        trigger = {
            "type": "interval",
            "interval_sec": interval_sec,
            "last_run": time.time()
        }
    elif args.cron:
        if len(args.cron.split()) != 5:
            print("[agy-daemon] error: --cron expects a 5-field cron expression (e.g. '0 3 * * *')")
            sys.exit(1)
        trigger = {
            "type": "cron",
            "cron": args.cron.strip()
        }
    elif args.github_pr:
        trigger = {
            "type": "github_pr",
            "repo": args.github_pr,
            "interval_sec": parse_duration(args.interval or "180s")
        }
    elif args.github_issue:
        trigger = {
            "type": "github_issue",
            "repo": args.github_issue,
            "interval_sec": parse_duration(args.interval or "180s")
        }
    elif args.watch_file:
        trigger = {
            "type": "watch_file",
            "path": os.path.abspath(args.watch_file),
            "interval_sec": parse_duration(args.interval or "15s")
        }
    elif args.battery_below:
        trigger = {
            "type": "battery",
            "below_level": int(args.battery_below),
            "interval_sec": parse_duration(args.interval or "300s")
        }
    elif args.webhook_port:
        trigger = {
            "type": "webhook",
            "port": int(args.webhook_port)
        }
    else:
        print("[agy-daemon] error: specify trigger (--delay, --interval, --cron, --github-pr, --github-issue, --watch-file, --battery-below, or --webhook-port)")
        sys.exit(1)

    new_task = {
        "id": task_id,
        "name": args.name or task_id,
        "command": args.command,
        "wake_app": args.wake_app or False,
        "trigger": trigger,
        "enabled": True,
        "created_at": time.time()
    }
    tasks.append(new_task)
    save_tasks(tasks)
    print(f"[agy-daemon] Task added: {new_task['name']} (ID: {task_id})")

def cmd_remove(args):
    tasks = load_tasks()
    initial_len = len(tasks)
    tasks = [t for t in tasks if t.get("id") != args.id and t.get("name") != args.id]
    if len(tasks) < initial_len:
        save_tasks(tasks)
        print(f"[agy-daemon] Task removed: {args.id}")
    else:
        print(f"[agy-daemon] Task not found: {args.id}")

def cmd_clear(args):
    save_tasks([])
    print("[agy-daemon] All scheduled tasks cleared.")

def cmd_list(args):
    tasks = load_tasks()
    print(f"Total scheduled tasks: {len(tasks)}")
    for t in tasks:
        status = "Active" if t.get("enabled") else "Disabled"
        print(f"- [{t['id']}] {t.get('name')} | Status: {status} | Trigger: {t.get('trigger', {}).get('type')}")
        print(f"  Command: {t.get('command')}")
        if t.get("wake_app"):
            print("  Wake UI: Enabled")

def main():
    parser = argparse.ArgumentParser(description="Antigravity Micro-Daemon")
    subparsers = parser.add_subparsers(dest="subcommand")

    subparsers.add_parser("start", help="Start daemon service")
    subparsers.add_parser("stop", help="Stop daemon service")
    subparsers.add_parser("status", help="Get daemon status")
    subparsers.add_parser("list", help="List active jobs")
    subparsers.add_parser("clear", help="Clear all jobs")

    p_rm = subparsers.add_parser("remove", help="Remove task by id or name")
    p_rm.add_argument("id", help="Task ID or name")

    p_add = subparsers.add_parser("add", help="Schedule a new task")
    p_add.add_argument("--name", help="Human-readable task name")
    p_add.add_argument("--command", required=True, help="Shell command to execute")
    p_add.add_argument("--delay", help="Execute after duration, e.g. 4h, 30m, 60s")
    p_add.add_argument("--interval", help="Execute periodically, e.g. 15m, 1h")
    p_add.add_argument("--cron", help="Standard 5-field cron schedule, e.g. '0 3 * * *'")
    p_add.add_argument("--github-pr", help="Watch GitHub repo for new PRs, e.g. user/repo")
    p_add.add_argument("--github-issue", help="Watch GitHub repo for new issues, e.g. user/repo")
    p_add.add_argument("--watch-file", help="Watch file or directory path for changes")
    p_add.add_argument("--battery-below", help="Trigger when battery percentage falls below value")
    p_add.add_argument("--webhook-port", help="Start HTTP webhook receiver on port")
    p_add.add_argument("--wake-app", action="store_true", help="Bring Antigravity UI to front when task finishes")

    args = parser.parse_args()

    if args.subcommand == "start":
        os.makedirs(CONFIG_DIR, exist_ok=True)
        with open(PID_FILE, "w") as f:
            f.write(str(os.getpid()))
        daemon_loop()
    elif args.subcommand == "stop":
        if os.path.isfile(PID_FILE):
            try:
                with open(PID_FILE, "r") as f:
                    pid = int(f.read().strip())
                os.kill(pid, signal.SIGTERM)
                print(f"[agy-daemon] Stopped daemon (PID: {pid}).")
            except Exception as e:
                print(f"[agy-daemon] Daemon process not active ({e}).")
            try:
                os.remove(PID_FILE)
            except Exception:
                pass
        else:
            print("Daemon not currently running.")
    elif args.subcommand == "add":
        cmd_add(args)
    elif args.subcommand == "remove":
        cmd_remove(args)
    elif args.subcommand == "clear":
        cmd_clear(args)
    elif args.subcommand == "list":
        cmd_list(args)
    elif args.subcommand == "status":
        if os.path.isfile(PID_FILE):
            print(f"Daemon PID file exists: {PID_FILE}")
        else:
            print("Daemon not currently running.")
    else:
        parser.print_help()

if __name__ == "__main__":
    main()

