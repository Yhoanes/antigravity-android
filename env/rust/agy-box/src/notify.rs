use crate::env_util::{find_rish, resolve_files_dir, resolve_prefix};
use serde_json::json;
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const USAGE: &str = "Usage: agy-notify [post|cancel|list] [options]\n\
Commands:\n  \
  post    -t <title> [-s <subtitle>] [-m <message>] [--tag <tag>]\n  \
  cancel  <tag>\n  \
  list    Show active notifications";

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', "'\\''"))
}

fn run_rish_bounded(
    rish_bin: &Path,
    script: &str,
    stdin_payload: Option<&[u8]>,
    capture_stdout: bool,
    timeout_ms: u64,
) -> Option<String> {
    let mut cmd = Command::new(rish_bin);
    cmd.arg("-c").arg(script);
    if stdin_payload.is_some() {
        cmd.stdin(Stdio::piped());
    } else {
        cmd.stdin(Stdio::null());
    }
    if capture_stdout {
        cmd.stdout(Stdio::piped());
    } else {
        cmd.stdout(Stdio::null());
    }
    cmd.stderr(Stdio::null());

    let mut child = cmd.spawn().ok()?;
    if let Some(bytes) = stdin_payload {
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(bytes);
        }
    }

    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                if capture_stdout {
                    let mut out = String::new();
                    if let Some(mut r) = child.stdout.take() {
                        let _ = r.read_to_string(&mut out);
                    }
                    return Some(out);
                }
                return Some(String::new());
            }
            Ok(None) => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            Err(_) => return None,
        }
    }
}

fn enqueue_host_notify(files_dir: &Path, payload: &serde_json::Value) -> bool {
    let active_snap = files_dir.join(".notify_active.json");
    if !active_snap.is_file() {
        return false;
    }
    let queue_path = files_dir.join(".notify_queue");
    let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&queue_path) else {
        return false;
    };
    let line = format!("{}\n", payload);
    f.write_all(line.as_bytes()).is_ok() && f.flush().is_ok()
}

pub fn run(args: &[String]) -> i32 {
    let prefix = resolve_prefix();
    let files_dir = resolve_files_dir(&prefix);
    let rish = find_rish(&files_dir);

    let (cmd, rest) = if args.is_empty() {
        ("post", &args[..])
    } else if args[0].starts_with('-') {
        ("post", &args[..])
    } else {
        (args[0].as_str(), &args[1..])
    };

    let now_sec = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let mut title = "Antigravity".to_string();
    let mut subtitle = String::new();
    let mut message = String::new();
    let mut tag = format!("agy_{now_sec}");

    let mut i = 0usize;
    while i < rest.len() {
        match rest[i].as_str() {
            "-t" | "--title" if i + 1 < rest.len() => {
                title = rest[i + 1].clone();
                i += 2;
            }
            "-s" | "--subtitle" if i + 1 < rest.len() => {
                subtitle = rest[i + 1].clone();
                i += 2;
            }
            "-m" | "--message" if i + 1 < rest.len() => {
                message = rest[i + 1].clone();
                i += 2;
            }
            "--tag" if i + 1 < rest.len() => {
                tag = rest[i + 1].clone();
                i += 2;
            }
            "-h" | "--help" => {
                println!("{USAGE}");
                return 0;
            }
            other => {
                if cmd == "cancel" {
                    tag = other.to_string();
                } else if message.is_empty() {
                    message = other.to_string();
                }
                i += 1;
            }
        }
    }

    match cmd {
        "post" => {
            if message.is_empty() && !title.is_empty() {
                message = title;
                title = "Antigravity".to_string();
            }
            let body_text = if subtitle.is_empty() {
                message.clone()
            } else {
                format!("[{subtitle}] {message}")
            };

            let req = json!({
                "action": "post",
                "title": title,
                "message": body_text,
                "tag": tag,
            });
            if enqueue_host_notify(&files_dir, &req) {
                return 0;
            }

            if let Some(rish_bin) = &rish {
                let src_logo = prefix.join("share/antigravity/logo.png");
                let logo_cache = "/data/local/tmp/agy_logo.png";
                if src_logo.is_file() {
                    let script = format!(
                        "if [ ! -s '{logo_cache}' ]; then cat > '{logo_cache}' 2>/dev/null && chmod 644 '{logo_cache}' 2>/dev/null || true; fi; \
                         ICON_OPT=''; [ -s '{logo_cache}' ] && ICON_OPT='-I file://{logo_cache}'; \
                         cmd notification post -S bigtext $ICON_OPT -t {} {} {}",
                        shell_quote(&title),
                        shell_quote(&tag),
                        shell_quote(&body_text)
                    );
                    if let Ok(logo_bytes) = fs::read(&src_logo) {
                        if run_rish_bounded(rish_bin, &script, Some(&logo_bytes), false, 450).is_some() {
                            return 0;
                        }
                    }
                } else {
                    let script = format!(
                        "cmd notification post -S bigtext -t {} {} {}",
                        shell_quote(&title),
                        shell_quote(&tag),
                        shell_quote(&body_text)
                    );
                    if run_rish_bounded(rish_bin, &script, None, false, 450).is_some() {
                        return 0;
                    }
                }
            }

            if let Ok(status) = Command::new("/system/bin/cmd")
                .args(["notification", "post", "-S", "bigtext", "-t", &title, &tag, &body_text])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
            {
                if status.success() {
                    return 0;
                }
            }

            println!("[agy-notify] notification queued: {title} - {message}");
            0
        }
        "cancel" => {
            let req = json!({
                "action": "cancel",
                "tag": tag,
            });
            if enqueue_host_notify(&files_dir, &req) {
                return 0;
            }
            if let Some(rish_bin) = &rish {
                let script = format!("cmd notification cancel {}", shell_quote(&tag));
                let _ = run_rish_bounded(rish_bin, &script, None, false, 450);
            }
            let _ = Command::new("/system/bin/cmd")
                .args(["notification", "cancel", &tag])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status();
            0
        }
        "list" => {
            let snap = files_dir.join(".notify_active.json");
            if let Ok(raw) = fs::read_to_string(&snap) {
                let trimmed = raw.trim();
                if !trimmed.is_empty() {
                    println!("{trimmed}");
                    return 0;
                }
            }
            if let Some(rish_bin) = &rish {
                let script = "dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 40";
                if let Some(out) = run_rish_bounded(rish_bin, script, None, true, 450) {
                    if !out.trim().is_empty() {
                        print!("{out}");
                        return 0;
                    }
                }
            }
            println!("[]");
            0
        }
        "help" => {
            println!("{USAGE}");
            0
        }
        other => {
            eprintln!("agy-notify: unknown command '{other}'\n{USAGE}");
            2
        }
    }
}
