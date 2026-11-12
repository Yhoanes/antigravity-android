use memchr::memmem;
use rayon::prelude::*;
use regex::bytes::Regex;
use rustls::{ClientConfig, RootCertStore};
use std::env;
use std::fs;
use std::io::BufReader;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use walkdir::WalkDir;

/// Build "/data/" pattern dynamically so no contiguous foreign prefix literal ever sits in .rodata.
pub fn foreign_prefix_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        let pat = format!(
            "{}{}{}",
            r"/data/(?:data|user/[0-9]+)/",
            r"com\.[^/]*",
            r"/files/usr"
        );
        Regex::new(&pat).expect("valid foreign prefix regex")
    })
}

pub fn resolve_prefix() -> PathBuf {
    if let Ok(p) = env::var("PREFIX") {
        if !p.trim().is_empty() {
            return PathBuf::from(p);
        }
    }
    if let Ok(exe) = env::current_exe() {
        // Check if exe is inside .../usr/bin/agy-box or .../runtime/bin/<shim>
        if let Some(bin_dir) = exe.parent() {
            if let Some(parent) = bin_dir.parent() {
                if parent.file_name().and_then(|s| s.to_str()) == Some("usr") {
                    return parent.to_path_buf();
                }
                if parent.file_name().and_then(|s| s.to_str()) == Some("runtime") {
                    if let Some(files_dir) = parent.parent() {
                        return files_dir.join("usr");
                    }
                }
            }
        }
    }
    if let Ok(home) = env::var("HOME") {
        let hp = PathBuf::from(&home);
        if home.ends_with("/files") {
            return hp.join("usr");
        }
    }
    PathBuf::from("/data/data/com.agy/files/usr")
}

pub fn resolve_files_dir(prefix: &Path) -> PathBuf {
    if let Some(parent) = prefix.parent() {
        return parent.to_path_buf();
    }
    if let Ok(home) = env::var("HOME") {
        if !home.is_empty() {
            return PathBuf::from(home);
        }
    }
    PathBuf::from("/data/data/com.agy/files")
}

pub fn detect_app_pkg(files_dir: &Path) -> String {
    for comp in files_dir.components().rev() {
        let s = comp.as_os_str().to_string_lossy();
        if s.starts_with("com.") {
            return s.into_owned();
        }
    }
    "com.agy".to_string()
}

pub fn find_rish(files_dir: &Path) -> Option<PathBuf> {
    let candidates = [
        files_dir.join("runtime/bin/rish"),
        files_dir.join("usr/bin/rish"),
        files_dir.join("bin/rish"),
    ];
    for c in &candidates {
        if is_executable(c) {
            return Some(c.clone());
        }
    }
    if let Ok(path_var) = env::var("PATH") {
        for dir in env::split_paths(&path_var) {
            let p = dir.join("rish");
            if is_executable(&p) {
                return Some(p);
            }
        }
    }
    None
}

pub fn is_executable(p: &Path) -> bool {
    if !p.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(p) {
            return (meta.permissions().mode() & 0o111) != 0;
        }
        false
    }
    #[cfg(not(unix))]
    {
        true
    }
}

pub fn set_mode_exec(p: &Path) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if let Ok(meta) = fs::metadata(p) {
            let mut perms = meta.permissions();
            perms.set_mode(0o755);
            let _ = fs::set_permissions(p, perms);
        }
    }
    #[cfg(not(unix))]
    {
        let _ = p;
    }
}

pub fn build_http_agent(cert_path: Option<&Path>, timeout_secs: u64) -> ureq::Agent {
    let mut root_store = RootCertStore::empty();
    root_store.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

    let mut extra_pem_paths: Vec<PathBuf> = Vec::new();
    if let Some(cp) = cert_path {
        extra_pem_paths.push(cp.to_path_buf());
    }
    if let Ok(env_cert) = env::var("SSL_CERT_FILE") {
        extra_pem_paths.push(PathBuf::from(env_cert));
    }

    for pem_path in extra_pem_paths {
        if let Ok(file) = fs::File::open(&pem_path) {
            let mut reader = BufReader::new(file);
            for cert in rustls_pemfile::certs(&mut reader).flatten() {
                let _ = root_store.add(cert);
            }
        }
    }

    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let tls_cfg = ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .expect("tls versions")
        .with_root_certificates(root_store)
        .with_no_client_auth();

    ureq::AgentBuilder::new()
        .tls_config(Arc::new(tls_cfg))
        .timeout_connect(Duration::from_secs(20))
        .timeout(Duration::from_secs(timeout_secs))
        .user_agent("agy-box/0.2.0 (Android; aarch64)")
        .build()
}

/// Checks if a buffer is a text file (not ELF and contains no NUL byte in the first 8 KB).
pub fn is_text_bytes(buf: &[u8]) -> bool {
    if buf.is_empty() {
        return false;
    }
    if buf.len() >= 4 && &buf[0..4] == b"\x7fELF" {
        return false;
    }
    let check_len = buf.len().min(8192);
    memchr::memchr(0, &buf[..check_len]).is_none()
}

/// Rewrites foreign `/data/(data|user/N)/com.<pkg>/files/usr` prefixes in a byte buffer if it is text.
/// Returns `Some(rewritten_bytes)` ONLY when actual changes occurred.
pub fn rewrite_prefix_in_bytes(buf: &[u8], target_prefix: &str) -> Option<Vec<u8>> {
    if !is_text_bytes(buf) {
        return None;
    }
    if memmem::find(buf, b"/data/").is_none() {
        return None;
    }
    let re = foreign_prefix_regex();
    if !re.is_match(buf) {
        return None;
    }
    let out = re.replace_all(buf, target_prefix.as_bytes()).into_owned();
    if out == buf {
        None
    } else {
        Some(out)
    }
}

/// Rewrites foreign symlink target if it points into `/data/(data|user/N)/com.<pkg>/files/usr...`.
pub fn rewrite_symlink_target(link_target: &str, target_prefix: &str) -> Option<String> {
    let re = foreign_prefix_regex();
    let m = re.find(link_target.as_bytes())?;
    if m.start() != 0 {
        return None;
    }
    let suffix = &link_target[m.end()..];
    if !suffix.is_empty() && !suffix.starts_with('/') {
        return None;
    }
    let new_target = format!("{}{}", target_prefix, suffix);
    if new_target == link_target {
        None
    } else {
        Some(new_target)
    }
}

/// Parallel multi-threaded prefix & symlink rewriter over one or more directory/file roots.
pub fn rewrite_prefix_paths(targets: &[PathBuf], prefix: &Path) -> usize {
    let prefix_str = prefix.to_string_lossy().into_owned();
    let mut regular_files: Vec<PathBuf> = Vec::new();
    let mut symlinks: Vec<PathBuf> = Vec::new();

    for root in targets {
        if !root.exists() && fs::symlink_metadata(root).is_err() {
            continue;
        }
        let walker = WalkDir::new(root).follow_links(false).into_iter().filter_entry(|e| {
            e.file_name().to_string_lossy() != "ghidra_12.1.4_PUBLIC"
        });
        for entry in walker.flatten() {
            let ft = entry.file_type();
            if ft.is_symlink() {
                symlinks.push(entry.into_path());
            } else if ft.is_file() {
                regular_files.push(entry.into_path());
            }
        }
    }

    let rewritten = AtomicUsize::new(0);
    let pid = std::process::id();

    regular_files.par_iter().enumerate().for_each(|(idx, path)| {
        let meta = match fs::metadata(path) {
            Ok(m) => m,
            Err(_) => return,
        };
        if meta.len() == 0 || meta.len() > 8 * 1024 * 1024 {
            return;
        }
        let data = match fs::read(path) {
            Ok(d) => d,
            Err(_) => return,
        };
        if let Some(new_bytes) = rewrite_prefix_in_bytes(&data, &prefix_str) {
            let tmp_name = format!(
                "{}.agyfix.{}.{}",
                path.file_name().unwrap_or_default().to_string_lossy(),
                pid,
                idx
            );
            let tmp_path = path.with_file_name(tmp_name);
            if fs::write(&tmp_path, &new_bytes).is_ok() {
                let _ = fs::set_permissions(&tmp_path, meta.permissions());
                if fs::rename(&tmp_path, path).is_ok() {
                    rewritten.fetch_add(1, Ordering::Relaxed);
                } else {
                    let _ = fs::remove_file(&tmp_path);
                }
            }
        }
    });

    #[cfg(unix)]
    {
        for link in &symlinks {
            if let Ok(cur_target) = fs::read_link(link) {
                let cur_str = cur_target.to_string_lossy();
                if let Some(new_target) = rewrite_symlink_target(&cur_str, &prefix_str) {
                    let tmp_link = link.with_file_name(format!(
                        "{}.agylnk.{}",
                        link.file_name().unwrap_or_default().to_string_lossy(),
                        pid
                    ));
                    let _ = fs::remove_file(&tmp_link);
                    if std::os::unix::fs::symlink(&new_target, &tmp_link).is_ok() {
                        if fs::rename(&tmp_link, link).is_ok() {
                            rewritten.fetch_add(1, Ordering::Relaxed);
                        } else {
                            let _ = fs::remove_file(&tmp_link);
                        }
                    }
                }
            }
        }
    }

    rewritten.load(Ordering::Relaxed)
}

const SKIP_BRIDGE_NAMES: &[&str] = &[
    "rish",
    "xdg-open",
    "open",
    "open_helper",
    "sensible-browser",
    "x-www-browser",
    "gnome-open",
    "termux-open",
    "termux-open-url",
    "google-chrome",
    "chrome",
    "chromium",
    "chromium-browser",
    "torch",
    "termux-torch",
    "termux-camera-torch",
    "webp_encoder",
    "webm_encoder",
    "webm_encoder.elf",
    "agy-node",
    "npm",
    "npx",
    "analyzeHeadless",
    "craft-anything-cli",
    "craft-ghidra-setup",
    "agy-video-mcp",
    "agy-video-setup",
];

const URL_OPENERS: &[&str] = &[
    "xdg-open",
    "open",
    "open_helper",
    "sensible-browser",
    "x-www-browser",
    "gnome-open",
    "termux-open",
    "termux-open-url",
];

/// Fast native equivalent of `sync_runtime_bridges` in `util.sh`.
/// Uses zero-overhead symlinks to `agy-box` when present on Unix, with automatic shell bridge fallback.
pub fn sync_runtime_bridges(prefix: &Path) {
    let files_dir = resolve_files_dir(prefix);
    let rbin = files_dir.join("runtime/bin");
    if !rbin.is_dir() {
        return;
    }

    let rbin_xdg = rbin.join("xdg-open");
    let prefix_bin = prefix.join("bin");
    if rbin_xdg.is_file() && prefix_bin.is_dir() {
        for opener in URL_OPENERS {
            let dst = prefix_bin.join(opener);
            let _ = fs::remove_file(&dst);
            if fs::copy(&rbin_xdg, &dst).is_ok() {
                set_mode_exec(&dst);
            }
        }
    }

    let entries = match fs::read_dir(&prefix_bin) {
        Ok(rd) => rd,
        Err(_) => return,
    };

    let agy_box_bin = prefix_bin.join("agy-box");
    let _has_agy_box = is_executable(&agy_box_bin);
    let prefix_str = prefix.to_string_lossy();
    let rbin_str = rbin.to_string_lossy();
    let mut count = 0usize;

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let name = entry.file_name().to_string_lossy().into_owned();
        count += 1;
        if SKIP_BRIDGE_NAMES.contains(&name.as_str()) {
            continue;
        }
        let bridge = rbin.join(&name);
        if bridge.exists() || fs::symlink_metadata(&bridge).is_ok() {
            continue;
        }

        // Prefer zero-shell-overhead native symlink to agy-box shim (except bash/sh which ShellBridgeInstaller manages)
        #[cfg(unix)]
        if _has_agy_box && name != "bash" && name != "sh" {
            if std::os::unix::fs::symlink(&agy_box_bin, &bridge).is_ok() {
                continue;
            }
        }

        let script = format!(
            "#!/system/bin/sh\n\
             # AGY bridge -> bionic $PREFIX/bin/{name}\n\
             PREFIX='{prefix_str}'\n\
             export PREFIX\n\
             export HOME=\"$(dirname \"$PREFIX\")\"\n\
             export ANTIGRAVITY_APP_DATA_DIR=\"$HOME/.gemini/antigravity-app\"\n\
             export TMPDIR=\"$PREFIX/tmp\"\n\
             export JDK_JAVA_OPTIONS=\"-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp\"\n\
             if [ -f \"$PREFIX/lib/libagy-go-dns.so\" ] && [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n\
               export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so:$PREFIX/lib/libtermux-exec.so\"\n\
             elif [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n\
               export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n\
             elif [ -f \"$PREFIX/lib/libagy-go-dns.so\" ]; then\n\
               export LD_PRELOAD=\"$PREFIX/lib/libagy-go-dns.so\"\n\
             else\n\
               unset LD_PRELOAD\n\
             fi\n\
             export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n\
             export PATH=\"$PREFIX/bin:{rbin_str}:$HOME/.gemini/antigravity-app/bin:$HOME/.local/bin:/system/bin\"\n\
             export CHROME_PATH=\"{rbin_str}/chrome\"\n\
             export PUPPETEER_EXECUTABLE_PATH=\"{rbin_str}/chrome\"\n\
             export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n\
             export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n\
             export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n\
             if [ -x \"$PREFIX/bin/{name}\" ]; then\n\
               exec \"$PREFIX/bin/{name}\" \"$@\"\n\
             else\n\
               exec /system/bin/sh \"$@\"\n\
             fi\n"
        );
        if fs::write(&bridge, script).is_ok() {
            set_mode_exec(&bridge);
        }
    }

    let marker = format!("12\n{}\n{}\n", prefix_str, count);
    let _ = fs::write(rbin.join(".agy-bridges"), marker);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_rewrite_prefix_bytes_and_symlink() {
        let fake_foreign = format!("{}{}{}", "/data/data/", "com.termux", "/files/usr/bin/bash");
        let rewritten = rewrite_prefix_in_bytes(fake_foreign.as_bytes(), "/data/data/com.agy/files/usr")
            .expect("should rewrite");
        assert_eq!(
            String::from_utf8(rewritten).unwrap(),
            "/data/data/com.agy/files/usr/bin/bash"
        );

        // ELF binary must never be rewritten
        let mut elf = b"\x7fELF".to_vec();
        elf.extend_from_slice(fake_foreign.as_bytes());
        assert!(rewrite_prefix_in_bytes(&elf, "/data/data/com.agy/files/usr").is_none());

        // Symlink rewrite
        let sym = rewrite_symlink_target(&fake_foreign, "/data/data/com.agy/files/usr").unwrap();
        assert_eq!(sym, "/data/data/com.agy/files/usr/bin/bash");
    }
}
