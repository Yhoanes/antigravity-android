use crate::env_util::{build_http_agent, detect_app_pkg, find_rish, resolve_files_dir, resolve_prefix};
use crate::notify;
use serde_json::{json, Value};
#[cfg(any(target_os = "linux", target_os = "android"))]
use std::collections::HashMap;
use std::collections::HashSet;
use std::env;
use std::fs::{self, OpenOptions};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const LOG_MAX_BYTES: u64 = 1024 * 1024;

static STOP_FLAG: AtomicBool = AtomicBool::new(false);

fn log_lock() -> &'static Mutex<()> {
    static L: OnceLock<Mutex<()>> = OnceLock::new();
    L.get_or_init(|| Mutex::new(()))
}

fn running_tasks() -> &'static Mutex<HashSet<String>> {
    static R: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
    R.get_or_init(|| Mutex::new(HashSet::new()))
}

struct DaemonPaths {
    config_dir: PathBuf,
    tasks_file: PathBuf,
    log_file: PathBuf,
    pid_file: PathBuf,
    prefix: PathBuf,
    files_dir: PathBuf,
}

impl DaemonPaths {
    fn resolve() -> Self {
        let prefix = resolve_prefix();
        let files_dir = resolve_files_dir(&prefix);
        let home = env::var("HOME")
            .ok()
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| files_dir.clone());
        let config_dir = home.join(".gemini/antigravity-app/daemon");
        Self {
            tasks_file: config_dir.join("tasks.json"),
            log_file: config_dir.join("daemon.log"),
            pid_file: config_dir.join("daemon.pid"),
            config_dir,
            prefix,
            files_dir,
        }
    }
}

fn now_secs_f64() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

#[derive(Clone, Copy)]
struct LocalDateTime {
    year: i32,
    month: u32,
    mday: u32,
    hour: u32,
    min: u32,
    sec: u32,
    cron_dow: u32, // 0 = Sunday .. 6 = Saturday
}

fn local_datetime(epoch_secs: i64) -> LocalDateTime {
    #[cfg(unix)]
    unsafe {
        let t = epoch_secs as libc::time_t;
        let mut tm: libc::tm = std::mem::zeroed();
        libc::localtime_r(&t, &mut tm);
        LocalDateTime {
            year: tm.tm_year + 1900,
            month: (tm.tm_mon + 1) as u32,
            mday: tm.tm_mday as u32,
            hour: tm.tm_hour as u32,
            min: tm.tm_min as u32,
            sec: tm.tm_sec as u32,
            cron_dow: tm.tm_wday as u32,
        }
    }
    #[cfg(not(unix))]
    {
        // Civil-from-days UTC fallback for Windows host unit tests
        let days = epoch_secs.div_euclid(86400);
        let secs_of_day = epoch_secs.rem_euclid(86400) as u32;
        let z = days + 719468;
        let era = if z >= 0 { z } else { z - 146096 } / 146097;
        let doe = (z - era * 146097) as u32;
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        let y = yoe as i64 + era * 400;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let d = doy - (153 * mp + 2) / 5 + 1;
        let m = if mp < 10 { mp + 3 } else { mp - 9 };
        let year = (y + if m <= 2 { 1 } else { 0 }) as i32;
        let wday = ((days + 4).rem_euclid(7)) as u32;
        LocalDateTime {
            year,
            month: m,
            mday: d,
            hour: secs_of_day / 3600,
            min: (secs_of_day % 3600) / 60,
            sec: secs_of_day % 60,
            cron_dow: wday,
        }
    }
}

fn format_timestamp(epoch_secs: i64) -> String {
    let dt = local_datetime(epoch_secs);
    format!(
        "{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
        dt.year, dt.month, dt.mday, dt.hour, dt.min, dt.sec
    )
}

fn daemon_log(paths: &DaemonPaths, msg: &str) {
    let ts = format_timestamp(now_secs_f64() as i64);
    let formatted = format!("[{ts}] {msg}");
    let _g = log_lock().lock().unwrap();
    println!("{formatted}");
    let _ = fs::create_dir_all(&paths.config_dir);
    if let Ok(meta) = fs::metadata(&paths.log_file) {
        if meta.len() >= LOG_MAX_BYTES {
            let rotated = paths.config_dir.join("daemon.log.1");
            let _ = fs::rename(&paths.log_file, rotated);
        }
    }
    if let Ok(mut f) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&paths.log_file)
    {
        let _ = writeln!(f, "{formatted}");
    }
}

fn send_notify(paths: &DaemonPaths, title: &str, message: &str, tag: Option<&str>) {
    daemon_log(paths, &format!("Notification: {title} - {message}"));
    let mut args = vec![
        "post".to_string(),
        "-t".to_string(),
        title.to_string(),
        "-m".to_string(),
        message.to_string(),
    ];
    if let Some(t) = tag {
        args.push("--tag".to_string());
        args.push(t.to_string());
    }
    let _ = notify::run(&args);
}

fn load_tasks(paths: &DaemonPaths) -> Vec<Value> {
    if !paths.tasks_file.is_file() {
        return Vec::new();
    }
    match fs::read_to_string(&paths.tasks_file) {
        Ok(raw) => serde_json::from_str::<Vec<Value>>(&raw).unwrap_or_default(),
        Err(e) => {
            daemon_log(paths, &format!("Error loading tasks: {e}"));
            Vec::new()
        }
    }
}

fn save_tasks(paths: &DaemonPaths, tasks: &[Value]) {
    let _ = fs::create_dir_all(&paths.config_dir);
    let tmp = paths.config_dir.join("tasks.json.tmp");
    if let Ok(pretty) = serde_json::to_string_pretty(tasks) {
        if fs::write(&tmp, pretty).is_ok() {
            let _ = fs::rename(&tmp, &paths.tasks_file);
        }
    }
}

fn wake_antigravity_app(paths: &DaemonPaths) {
    daemon_log(paths, "Waking Antigravity activity to foreground...");
    let pkg = detect_app_pkg(&paths.files_dir);
    let component = format!("{pkg}/com.agy.ui.MainActivity");
    if let Some(rish) = find_rish(&paths.files_dir) {
        let _ = Command::new(rish)
            .args(["-c", &format!("am start -n {component}")])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    } else {
        let _ = Command::new("/system/bin/am")
            .args(["start", "-n", &component])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }
}

fn run_shell_with_timeout(_prefix: &Path, command: &str, timeout_sec: u64) -> (bool, String) {
    #[cfg(unix)]
    let mut cmd = {
        let bash = _prefix.join("bin/bash");
        let sh = if bash.is_file() {
            bash
        } else {
            PathBuf::from("/system/bin/sh")
        };
        let mut c = Command::new(sh);
        c.arg("-c").arg(command);
        c
    };
    #[cfg(not(unix))]
    let mut cmd = {
        let mut c = Command::new("cmd");
        c.arg("/C").arg(command);
        c
    };

    let mut child = match cmd.stdout(Stdio::piped()).stderr(Stdio::piped()).spawn() {
        Ok(ch) => ch,
        Err(e) => return (false, format!("Execution error: {e}")),
    };

    let mut out_reader = child.stdout.take();
    let mut err_reader = child.stderr.take();
    let reader_handle = thread::spawn(move || {
        let mut combined = String::new();
        if let Some(mut r) = out_reader.take() {
            let _ = r.read_to_string(&mut combined);
        }
        if let Some(mut r) = err_reader.take() {
            let _ = r.read_to_string(&mut combined);
        }
        combined
    });

    let deadline = Instant::now() + Duration::from_secs(timeout_sec);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                let output = reader_handle.join().unwrap_or_default();
                return (status.success(), output.trim().to_string());
            }
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return (false, "Task timed out.".to_string());
                }
                thread::sleep(Duration::from_millis(100));
            }
            Err(e) => return (false, format!("Execution error: {e}")),
        }
    }
}

fn dispatch_task(paths: Arc<DaemonPaths>, task: Value) {
    let task_id = task
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string();
    let name = task
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or(&task_id)
        .to_string();

    {
        let mut running = running_tasks().lock().unwrap();
        if running.contains(&task_id) {
            daemon_log(
                &paths,
                &format!("Skipping overlapping trigger for task [{name}] (already running)."),
            );
            return;
        }
        running.insert(task_id.clone());
    }

    thread::spawn(move || {
        let command = task.get("command").and_then(|v| v.as_str()).unwrap_or("");
        let wake_app = task
            .get("wake_app")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let timeout_sec = task
            .get("timeout_sec")
            .and_then(|v| v.as_u64())
            .unwrap_or(600);

        daemon_log(
            &paths,
            &format!("Executing task [{name}] (id: {task_id}): {command}"),
        );
        {
            let p_notif = Arc::clone(&paths);
            let n_notif = name.clone();
            let id_notif = task_id.clone();
            thread::spawn(move || {
                send_notify(
                    &p_notif,
                    "Antigravity Daemon",
                    &format!("Running task: {n_notif}"),
                    Some(&format!("task_{id_notif}")),
                );
            });
        }

        let start = Instant::now();
        let (success, output) = run_shell_with_timeout(&paths.prefix, command, timeout_sec);
        let duration = start.elapsed().as_secs();
        let status_str = if success {
            "Completed successfully"
        } else {
            "Failed"
        };
        let summary200: String = output.chars().take(200).collect();
        let summary120: String = output.chars().take(120).collect();

        daemon_log(
            &paths,
            &format!("Task [{name}] {status_str} in {duration}s. Output summary: {summary200}"),
        );
        send_notify(
            &paths,
            &format!("Task {status_str}: {name}"),
            &format!("Finished in {duration}s. {summary120}"),
            Some(&format!("task_done_{task_id}")),
        );

        if wake_app {
            wake_antigravity_app(&paths);
        }

        running_tasks().lock().unwrap().remove(&task_id);
    });
}

pub fn cron_field_matches(field: &str, value: u32) -> bool {
    for raw_part in field.split(',') {
        let part = raw_part.trim();
        if part.is_empty() {
            continue;
        }
        let (range_part, step) = match part.split_once('/') {
            Some((p, s)) => (p, s.parse::<u32>().unwrap_or(1).max(1)),
            None => (part, 1),
        };
        if range_part == "*" {
            if value % step == 0 {
                return true;
            }
        } else if let Some((lo_s, hi_s)) = range_part.split_once('-') {
            if let (Ok(lo), Ok(hi)) = (lo_s.parse::<u32>(), hi_s.parse::<u32>()) {
                if value >= lo && value <= hi && (value - lo) % step == 0 {
                    return true;
                }
            }
        } else if let Ok(exact) = range_part.parse::<u32>() {
            if exact == value {
                return true;
            }
        }
    }
    false
}

fn check_cron_schedule(trigger: &mut Value, now: f64) -> bool {
    let expr = trigger
        .get("cron")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let fields: Vec<&str> = expr.split_whitespace().collect();
    if fields.len() != 5 {
        return false;
    }
    let dt = local_datetime(now as i64);
    let minute_key = format!(
        "{:04}-{:02}-{:02} {:02}:{:02}",
        dt.year, dt.month, dt.mday, dt.hour, dt.min
    );
    if trigger.get("last_cron_minute").and_then(|v| v.as_str()) == Some(&minute_key) {
        return false;
    }
    let dow_ok = cron_field_matches(fields[4], dt.cron_dow)
        || (dt.cron_dow == 0 && cron_field_matches(fields[4], 7));
    if cron_field_matches(fields[0], dt.min)
        && cron_field_matches(fields[1], dt.hour)
        && cron_field_matches(fields[2], dt.mday)
        && cron_field_matches(fields[3], dt.month)
        && dow_ok
    {
        trigger["last_cron_minute"] = Value::String(minute_key);
        return true;
    }
    false
}

fn check_github_repo(paths: &DaemonPaths, trigger: &mut Value, endpoint_type: &str) -> bool {
    let Some(repo) = trigger
        .get("repo")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
    else {
        return false;
    };
    let token = env::var("GITHUB_TOKEN").ok().or_else(|| {
        trigger
            .get("token")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    });
    let url = format!("https://api.github.com/repos/{repo}/{endpoint_type}?state=open");
    let cert = paths.prefix.join("etc/tls/cert.pem");
    let agent = build_http_agent(Some(&cert), 15);
    let mut req = agent
        .get(&url)
        .set("User-Agent", "Antigravity-Daemon")
        .set("Accept", "application/vnd.github.v3+json");
    if let Some(tok) = token {
        req = req.set("Authorization", &format!("token {tok}"));
    }

    match req.call() {
        Ok(resp) => {
            let mut body = String::new();
            if resp.into_reader().read_to_string(&mut body).is_ok() {
                if let Ok(arr) = serde_json::from_str::<Vec<Value>>(&body) {
                    let key = format!("last_{endpoint_type}_count");
                    let prev = trigger.get(&key).and_then(|v| v.as_u64());
                    let cur = arr.len() as u64;
                    trigger[&key] = json!(cur);
                    trigger["last_check_ts"] = json!(now_secs_f64());
                    if let Some(p) = prev {
                        if cur > p {
                            let title = arr
                                .first()
                                .and_then(|i| i.get("title"))
                                .and_then(|v| v.as_str())
                                .unwrap_or("New item");
                            daemon_log(
                                paths,
                                &format!("GitHub trigger fired for {repo} ({endpoint_type}): '{title}'"),
                            );
                            return true;
                        }
                    }
                }
            }
        }
        Err(e) => daemon_log(paths, &format!("GitHub check error for {repo}: {e}")),
    }
    false
}

fn check_file_mtime(paths: &DaemonPaths, trigger: &mut Value) -> (bool, bool) {
    let Some(target) = trigger
        .get("path")
        .and_then(|v| v.as_str())
        .map(PathBuf::from)
    else {
        return (false, false);
    };
    let Ok(meta) = fs::metadata(&target) else {
        return (false, false);
    };
    let Ok(mtime) = meta.modified() else {
        return (false, false);
    };
    let curr = mtime
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0);
    let last = trigger
        .get("last_mtime")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0);
    if last == 0.0 {
        trigger["last_mtime"] = json!(curr);
        return (false, true);
    }
    if curr > last {
        trigger["last_mtime"] = json!(curr);
        daemon_log(
            paths,
            &format!("File watch trigger fired for: {}", target.display()),
        );
        return (true, true);
    }
    (false, false)
}

fn check_battery_condition(paths: &DaemonPaths, trigger: &Value) -> bool {
    let Some(target_level) = trigger.get("below_level").and_then(|v| v.as_i64()) else {
        return false;
    };
    let Some(rish) = find_rish(&paths.files_dir) else {
        return false;
    };
    if let Ok(out) = Command::new(rish).args(["-c", "dumpsys battery"]).output() {
        let txt = String::from_utf8_lossy(&out.stdout);
        for line in txt.lines() {
            if let Some((_, rhs)) = line.split_once("level:") {
                if let Ok(val) = rhs.trim().parse::<i64>() {
                    return val <= target_level;
                }
            }
        }
    }
    false
}

fn start_webhook_listener(paths: Arc<DaemonPaths>, port: u16) {
    let addr = format!("127.0.0.1:{port}");
    let listener = match TcpListener::bind(&addr) {
        Ok(l) => l,
        Err(e) => {
            daemon_log(&paths, &format!("Webhook bind error on {addr}: {e}"));
            return;
        }
    };
    daemon_log(
        &paths,
        &format!("Local webhook receiver listening on http://{addr}"),
    );

    for stream in listener.incoming().flatten() {
        let mut reader = BufReader::new(&stream);
        let mut req_line = String::new();
        if reader.read_line(&mut req_line).is_err() {
            continue;
        }
        let is_post = req_line.starts_with("POST ");
        let mut content_len = 0usize;
        loop {
            let mut hdr = String::new();
            if reader.read_line(&mut hdr).is_err() || hdr == "\r\n" || hdr == "\n" || hdr.is_empty()
            {
                break;
            }
            if let Some((k, v)) = hdr.split_once(':') {
                if k.trim().eq_ignore_ascii_case("content-length") {
                    content_len = v.trim().parse::<usize>().unwrap_or(0).min(65536);
                }
            }
        }
        let mut body = vec![0u8; content_len];
        if content_len > 0 {
            let _ = reader.read_exact(&mut body);
        }
        if is_post {
            let preview: String = String::from_utf8_lossy(&body).chars().take(200).collect();
            daemon_log(
                &paths,
                &format!("Incoming webhook on port {port}: {preview}"),
            );
            let tasks = load_tasks(&paths);
            let mut fired = false;
            for t in tasks {
                let enabled = t.get("enabled").and_then(|v| v.as_bool()).unwrap_or(true);
                let ttype = t
                    .get("trigger")
                    .and_then(|tr| tr.get("type"))
                    .and_then(|v| v.as_str());
                if enabled && ttype == Some("webhook") {
                    dispatch_task(Arc::clone(&paths), t);
                    fired = true;
                }
            }
            let resp_json = json!({ "status": "ok", "fired": fired }).to_string();
            let http = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                resp_json.len(),
                resp_json
            );
            let _ = (&stream).write_all(http.as_bytes());
        } else {
            let _ = (&stream).write_all(b"HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n");
        }
    }
}

/// Zero-CPU Linux/Android `inotify` waiter: sleeps up to `max_sleep` OR wakes immediately if any watched file modifies!
#[cfg(any(target_os = "linux", target_os = "android"))]
struct InotifyWatcher {
    fd: i32,
    watched: HashMap<PathBuf, i32>,
}

#[cfg(any(target_os = "linux", target_os = "android"))]
impl InotifyWatcher {
    fn new() -> Option<Self> {
        let fd = unsafe { libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC) };
        if fd < 0 {
            None
        } else {
            Some(Self {
                fd,
                watched: HashMap::new(),
            })
        }
    }

    fn sync_paths(&mut self, paths: &[PathBuf]) {
        for p in paths {
            if !self.watched.contains_key(p) && p.exists() {
                if let Ok(cstr) = std::ffi::CString::new(p.to_string_lossy().as_bytes()) {
                    let mask = libc::IN_MODIFY
                        | libc::IN_CLOSE_WRITE
                        | libc::IN_CREATE
                        | libc::IN_DELETE
                        | libc::IN_MOVED_TO
                        | libc::IN_ATTRIB;
                    let wd = unsafe { libc::inotify_add_watch(self.fd, cstr.as_ptr(), mask) };
                    if wd >= 0 {
                        self.watched.insert(p.clone(), wd);
                    }
                }
            }
        }
    }

    fn wait_or_timeout(&self, timeout_ms: i32) -> bool {
        let mut pfd = libc::pollfd {
            fd: self.fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
        if rc > 0 && (pfd.revents & libc::POLLIN) != 0 {
            let mut drain = [0u8; 2048];
            while unsafe { libc::read(self.fd, drain.as_mut_ptr() as *mut _, drain.len()) } > 0 {}
            return true;
        }
        false
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
impl Drop for InotifyWatcher {
    fn drop(&mut self) {
        if self.fd >= 0 {
            unsafe {
                libc::close(self.fd);
            }
        }
    }
}

#[cfg(unix)]
extern "C" fn handle_sigterm(_sig: libc::c_int) {
    STOP_FLAG.store(true, Ordering::Relaxed);
}

fn daemon_loop(paths: Arc<DaemonPaths>) {
    #[cfg(unix)]
    unsafe {
        libc::signal(libc::SIGTERM, handle_sigterm as *const () as libc::sighandler_t);
        libc::signal(libc::SIGINT, handle_sigterm as *const () as libc::sighandler_t);
    }

    daemon_log(
        &paths,
        "Antigravity native Rust micro-daemon running (zero-Python, inotify active).",
    );
    let mut webhook_ports: HashSet<u16> = HashSet::new();

    #[cfg(any(target_os = "linux", target_os = "android"))]
    let mut inotify = InotifyWatcher::new();

    while !STOP_FLAG.load(Ordering::Relaxed) {
        let mut tasks = load_tasks(&paths);
        let now = now_secs_f64();
        let mut changed = false;
        let mut watch_paths: Vec<PathBuf> = vec![paths.config_dir.clone()];

        for task in &mut tasks {
            let enabled = task.get("enabled").and_then(|v| v.as_bool()).unwrap_or(true);
            if !enabled {
                continue;
            }

            let ttype = task
                .get("trigger")
                .and_then(|t| t.get("type"))
                .and_then(|v| v.as_str())
                .unwrap_or("once")
                .to_string();

            let mut should_run = false;
            let mut disable_task = false;
            let mut mark_executed = false;

            if let Some(trigger) = task.get_mut("trigger") {
                match ttype.as_str() {
                    "once" | "delay" => {
                        let run_at = trigger.get("run_at").and_then(|v| v.as_f64()).unwrap_or(0.0);
                        let executed = task
                            .get("executed")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(false);
                        if now >= run_at && !executed {
                            should_run = true;
                            mark_executed = true;
                            disable_task = true;
                            changed = true;
                        }
                    }
                    "interval" => {
                        let interval = trigger
                            .get("interval_sec")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(300.0);
                        let last_run = trigger
                            .get("last_run")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(0.0);
                        if (now - last_run) >= interval {
                            should_run = true;
                            trigger["last_run"] = json!(now);
                            changed = true;
                        }
                    }
                    "cron" => {
                        if check_cron_schedule(trigger, now) {
                            should_run = true;
                            changed = true;
                        }
                    }
                    "github_pr" => {
                        let interval = trigger
                            .get("interval_sec")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(180.0);
                        let last_check = trigger
                            .get("last_check_ts")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(0.0);
                        if (now - last_check) >= interval {
                            if check_github_repo(&paths, trigger, "pulls") {
                                should_run = true;
                            }
                            changed = true;
                        }
                    }
                    "github_issue" => {
                        let interval = trigger
                            .get("interval_sec")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(180.0);
                        let last_check = trigger
                            .get("last_check_ts")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(0.0);
                        if (now - last_check) >= interval {
                            if check_github_repo(&paths, trigger, "issues") {
                                should_run = true;
                            }
                            changed = true;
                        }
                    }
                    "watch_file" => {
                        if let Some(p) = trigger.get("path").and_then(|v| v.as_str()) {
                            watch_paths.push(PathBuf::from(p));
                        }
                        let (fired, mutated) = check_file_mtime(&paths, trigger);
                        if mutated {
                            trigger["last_check_ts"] = json!(now);
                            changed = true;
                        }
                        if fired {
                            should_run = true;
                        }
                    }
                    "battery" => {
                        let interval = trigger
                            .get("interval_sec")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(300.0);
                        let last_check = trigger
                            .get("last_check_ts")
                            .and_then(|v| v.as_f64())
                            .unwrap_or(0.0);
                        if (now - last_check) >= interval {
                            trigger["last_check_ts"] = json!(now);
                            if check_battery_condition(&paths, trigger) {
                                should_run = true;
                                disable_task = true;
                            }
                            changed = true;
                        }
                    }
                    "webhook" => {
                        let port = trigger
                            .get("port")
                            .and_then(|v| v.as_u64())
                            .unwrap_or(45199) as u16;
                        if webhook_ports.insert(port) {
                            let p_clone = Arc::clone(&paths);
                            thread::spawn(move || start_webhook_listener(p_clone, port));
                        }
                    }
                    _ => {}
                }
            }

            if mark_executed {
                task["executed"] = json!(true);
            }
            if disable_task {
                task["enabled"] = json!(false);
            }
            if should_run {
                dispatch_task(Arc::clone(&paths), task.clone());
            }
        }

        if changed {
            save_tasks(&paths, &tasks);
        }

        #[cfg(any(target_os = "linux", target_os = "android"))]
        if let Some(w) = inotify.as_mut() {
            w.sync_paths(&watch_paths);
            let _ = w.wait_or_timeout(10_000);
            continue;
        }

        #[cfg(not(any(target_os = "linux", target_os = "android")))]
        {
            let _ = watch_paths;
            thread::sleep(Duration::from_secs(10));
        }
    }

    let _ = fs::remove_file(&paths.pid_file);
    daemon_log(&paths, "Daemon stopped cleanly.");
}

pub fn parse_duration_secs(s: &str) -> Result<u64, String> {
    let s = s.trim();
    if let Some(num) = s.strip_suffix('h') {
        num.parse::<f64>()
            .map(|v| (v * 3600.0) as u64)
            .map_err(|_| format!("invalid duration: {s}"))
    } else if let Some(num) = s.strip_suffix('m') {
        num.parse::<f64>()
            .map(|v| (v * 60.0) as u64)
            .map_err(|_| format!("invalid duration: {s}"))
    } else if let Some(num) = s.strip_suffix('s') {
        num.parse::<f64>()
            .map(|v| v as u64)
            .map_err(|_| format!("invalid duration: {s}"))
    } else {
        s.parse::<u64>().map_err(|_| format!("invalid duration: {s}"))
    }
}

fn cmd_add(paths: &DaemonPaths, args: &[String]) -> i32 {
    let mut name: Option<String> = None;
    let mut command: Option<String> = None;
    let mut delay: Option<String> = None;
    let mut interval: Option<String> = None;
    let mut cron_expr: Option<String> = None;
    let mut github_pr: Option<String> = None;
    let mut github_issue: Option<String> = None;
    let mut watch_file: Option<String> = None;
    let mut battery_below: Option<i64> = None;
    let mut webhook_port: Option<u16> = None;
    let mut wake_app = false;

    let mut i = 0usize;
    while i < args.len() {
        match args[i].as_str() {
            "--name" if i + 1 < args.len() => {
                name = Some(args[i + 1].clone());
                i += 2;
            }
            "--command" if i + 1 < args.len() => {
                command = Some(args[i + 1].clone());
                i += 2;
            }
            "--delay" if i + 1 < args.len() => {
                delay = Some(args[i + 1].clone());
                i += 2;
            }
            "--interval" if i + 1 < args.len() => {
                interval = Some(args[i + 1].clone());
                i += 2;
            }
            "--cron" if i + 1 < args.len() => {
                cron_expr = Some(args[i + 1].clone());
                i += 2;
            }
            "--github-pr" if i + 1 < args.len() => {
                github_pr = Some(args[i + 1].clone());
                i += 2;
            }
            "--github-issue" if i + 1 < args.len() => {
                github_issue = Some(args[i + 1].clone());
                i += 2;
            }
            "--watch-file" if i + 1 < args.len() => {
                watch_file = Some(args[i + 1].clone());
                i += 2;
            }
            "--battery-below" if i + 1 < args.len() => {
                battery_below = args[i + 1].parse::<i64>().ok();
                i += 2;
            }
            "--webhook-port" if i + 1 < args.len() => {
                webhook_port = args[i + 1].parse::<u16>().ok();
                i += 2;
            }
            "--wake-app" => {
                wake_app = true;
                i += 1;
            }
            _ => i += 1,
        }
    }

    let Some(command_str) = command else {
        println!("[agy-daemon] error: --command is required");
        return 1;
    };

    let now = now_secs_f64();
    let trigger = if let Some(d) = delay {
        let Ok(d_sec) = parse_duration_secs(&d) else {
            println!("[agy-daemon] error: invalid --delay '{d}'");
            return 1;
        };
        json!({
            "type": "delay",
            "run_at": now + d_sec as f64,
            "delay_sec": d_sec
        })
    } else if let Some(repo) = github_pr {
        let iv = parse_duration_secs(interval.as_deref().unwrap_or("180s")).unwrap_or(180);
        json!({ "type": "github_pr", "repo": repo, "interval_sec": iv })
    } else if let Some(repo) = github_issue {
        let iv = parse_duration_secs(interval.as_deref().unwrap_or("180s")).unwrap_or(180);
        json!({ "type": "github_issue", "repo": repo, "interval_sec": iv })
    } else if let Some(wf) = watch_file {
        let iv = parse_duration_secs(interval.as_deref().unwrap_or("15s")).unwrap_or(15);
        let abs = fs::canonicalize(&wf).unwrap_or_else(|_| PathBuf::from(&wf));
        json!({ "type": "watch_file", "path": abs.to_string_lossy(), "interval_sec": iv })
    } else if let Some(lvl) = battery_below {
        let iv = parse_duration_secs(interval.as_deref().unwrap_or("300s")).unwrap_or(300);
        json!({ "type": "battery", "below_level": lvl, "interval_sec": iv })
    } else if let Some(port) = webhook_port {
        json!({ "type": "webhook", "port": port })
    } else if let Some(iv_s) = interval {
        let Ok(iv) = parse_duration_secs(&iv_s) else {
            println!("[agy-daemon] error: invalid --interval '{iv_s}'");
            return 1;
        };
        json!({ "type": "interval", "interval_sec": iv, "last_run": now })
    } else if let Some(c) = cron_expr {
        if c.split_whitespace().count() != 5 {
            println!("[agy-daemon] error: --cron expects a 5-field cron expression (e.g. '0 3 * * *')");
            return 1;
        }
        json!({ "type": "cron", "cron": c.trim() })
    } else {
        println!("[agy-daemon] error: specify trigger (--delay, --interval, --cron, --github-pr, --github-issue, --watch-file, --battery-below, or --webhook-port)");
        return 1;
    };

    let mut tasks = load_tasks(paths);
    let task_id = format!("job_{}", now as u64);
    let task_name = name.unwrap_or_else(|| task_id.clone());
    let new_task = json!({
        "id": task_id,
        "name": task_name,
        "command": command_str,
        "wake_app": wake_app,
        "trigger": trigger,
        "enabled": true,
        "created_at": now
    });
    tasks.push(new_task);
    save_tasks(paths, &tasks);
    println!("[agy-daemon] Task added: {task_name} (ID: {task_id})");
    0
}

fn print_help() {
    println!(
        "Antigravity Micro-Daemon (agy-box native Rust engine)\n\n\
         Usage: agy-daemon <subcommand> [options]\n\n\
         Subcommands:\n  \
           start                 Start daemon service\n  \
           stop                  Stop daemon service\n  \
           status                Get daemon status\n  \
           list                  List active jobs\n  \
           clear                 Clear all jobs\n  \
           remove <id|name>      Remove task by id or name\n  \
           add --command <cmd>   Schedule a new task (--delay, --interval, --cron,\n                        \
                                 --github-pr, --github-issue, --watch-file,\n                        \
                                 --battery-below, --webhook-port, --wake-app)"
    );
}

pub fn run(args: &[String]) -> i32 {
    let paths = Arc::new(DaemonPaths::resolve());
    let Some(sub) = args.first().map(|s| s.as_str()) else {
        print_help();
        return 0;
    };
    let rest = &args[1..];

    match sub {
        "start" => {
            let _ = fs::create_dir_all(&paths.config_dir);
            let _ = fs::write(&paths.pid_file, std::process::id().to_string());
            daemon_loop(paths);
            0
        }
        "stop" => {
            if paths.pid_file.is_file() {
                if let Ok(raw) = fs::read_to_string(&paths.pid_file) {
                    if let Ok(pid) = raw.trim().parse::<i32>() {
                        #[cfg(unix)]
                        unsafe {
                            if libc::kill(pid, libc::SIGTERM) == 0 {
                                println!("[agy-daemon] Stopped daemon (PID: {pid}).");
                            } else {
                                println!("[agy-daemon] Daemon process not active (PID: {pid}).");
                            }
                        }
                        #[cfg(not(unix))]
                        {
                            println!("[agy-daemon] Stopped daemon (PID: {pid}).");
                        }
                    }
                }
                let _ = fs::remove_file(&paths.pid_file);
            } else {
                println!("Daemon not currently running.");
            }
            0
        }
        "status" => {
            if paths.pid_file.is_file() {
                let alive = fs::read_to_string(&paths.pid_file)
                    .ok()
                    .and_then(|s| s.trim().parse::<i32>().ok())
                    .map(|pid| {
                        #[cfg(unix)]
                        unsafe {
                            libc::kill(pid, 0) == 0
                        }
                        #[cfg(not(unix))]
                        {
                            let _ = pid;
                            true
                        }
                    })
                    .unwrap_or(false);
                if alive {
                    println!("Daemon PID file exists (running): {}", paths.pid_file.display());
                } else {
                    println!("Daemon PID file exists (stale): {}", paths.pid_file.display());
                }
            } else {
                println!("Daemon not currently running.");
            }
            0
        }
        "add" => cmd_add(&paths, rest),
        "remove" => {
            let Some(id) = rest.first() else {
                println!("[agy-daemon] error: remove expects task id or name");
                return 1;
            };
            let tasks = load_tasks(&paths);
            let initial_len = tasks.len();
            let kept: Vec<Value> = tasks
                .into_iter()
                .filter(|t| {
                    t.get("id").and_then(|v| v.as_str()) != Some(id.as_str())
                        && t.get("name").and_then(|v| v.as_str()) != Some(id.as_str())
                })
                .collect();
            if kept.len() < initial_len {
                save_tasks(&paths, &kept);
                println!("[agy-daemon] Task removed: {id}");
            } else {
                println!("[agy-daemon] Task not found: {id}");
            }
            0
        }
        "clear" => {
            save_tasks(&paths, &[]);
            println!("[agy-daemon] All scheduled tasks cleared.");
            0
        }
        "list" => {
            let tasks = load_tasks(&paths);
            println!("Total scheduled tasks: {}", tasks.len());
            for t in tasks {
                let id = t.get("id").and_then(|v| v.as_str()).unwrap_or("");
                let name = t.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let enabled = t.get("enabled").and_then(|v| v.as_bool()).unwrap_or(false);
                let status = if enabled { "Active" } else { "Disabled" };
                let ttype = t
                    .get("trigger")
                    .and_then(|tr| tr.get("type"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("unknown");
                let cmd = t.get("command").and_then(|v| v.as_str()).unwrap_or("");
                println!("- [{id}] {name} | Status: {status} | Trigger: {ttype}");
                println!("  Command: {cmd}");
                if t.get("wake_app").and_then(|v| v.as_bool()).unwrap_or(false) {
                    println!("  Wake UI: Enabled");
                }
            }
            0
        }
        "-h" | "--help" | "help" => {
            print_help();
            0
        }
        _ => {
            print_help();
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cron_and_duration() {
        assert_eq!(parse_duration_secs("4h").unwrap(), 14400);
        assert_eq!(parse_duration_secs("30m").unwrap(), 1800);
        assert_eq!(parse_duration_secs("15s").unwrap(), 15);
        assert_eq!(parse_duration_secs("60").unwrap(), 60);

        assert!(cron_field_matches("*/5", 15));
        assert!(!cron_field_matches("*/5", 14));
        assert!(cron_field_matches("1-5", 3));
        assert!(!cron_field_matches("1-5", 6));
        assert!(cron_field_matches("0,30", 30));
    }
}
