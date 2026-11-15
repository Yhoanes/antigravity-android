#[cfg(unix)]
use crate::env_util::is_executable;
use crate::env_util::{resolve_files_dir, resolve_prefix};
use std::env;
use std::path::Path;
use std::process::Command;

/// Sets standard AGY Bionic userspace environment variables in-process before exec'ing `$PREFIX/bin/<tool>`.
pub fn setup_bionic_env(prefix: &Path) {
    let files_dir = resolve_files_dir(prefix);
    let rbin = files_dir.join("runtime/bin");
    let app_data = files_dir.join(".gemini/antigravity-app");
    let tmp_dir = prefix.join("tmp");
    let cert_file = prefix.join("etc/tls/cert.pem");
    let openssl_cnf = prefix.join("etc/tls/openssl.cnf");

    env::set_var("PREFIX", prefix);
    env::set_var("HOME", &files_dir);
    env::set_var("ANTIGRAVITY_APP_DATA_DIR", &app_data);
    env::set_var("TMPDIR", &tmp_dir);
    env::set_var(
        "JDK_JAVA_OPTIONS",
        format!(
            "-Duser.home={} -Djava.io.tmpdir={}",
            files_dir.display(),
            tmp_dir.display()
        ),
    );
    if env::var_os("TERM").is_none() {
        env::set_var("TERM", "xterm-256color");
    }

    let go_dns = prefix.join("lib/libagy-go-dns.so");
    let termux_exec = prefix.join("lib/libtermux-exec.so");
    match (go_dns.is_file(), termux_exec.is_file()) {
        (true, true) => env::set_var(
            "LD_PRELOAD",
            format!("{}:{}", go_dns.display(), termux_exec.display()),
        ),
        (false, true) => env::set_var("LD_PRELOAD", &termux_exec),
        (true, false) => env::set_var("LD_PRELOAD", &go_dns),
        (false, false) => env::remove_var("LD_PRELOAD"),
    }

    env::set_var("LD_LIBRARY_PATH", prefix.join("lib"));
    let new_path = format!(
        "{}:{}:{}/bin:{}/.local/bin:/system/bin",
        prefix.join("bin").display(),
        rbin.display(),
        app_data.display(),
        files_dir.display()
    );
    env::set_var("PATH", new_path);

    let chrome = rbin.join("chrome");
    env::set_var("CHROME_PATH", &chrome);
    env::set_var("PUPPETEER_EXECUTABLE_PATH", &chrome);
    env::set_var("OPENSSL_CONF", &openssl_cnf);
    env::set_var("SSL_CERT_FILE", &cert_file);
    env::set_var("CURL_CA_BUNDLE", &cert_file);
    env::set_var("GIT_SSL_CAINFO", &cert_file);
}

pub fn exec_named(tool_name: &str, args: &[String]) -> i32 {
    let prefix = resolve_prefix();
    setup_bionic_env(&prefix);

    let target = prefix.join("bin").join(tool_name);
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        if is_executable(&target) {
            let err = Command::new(&target).args(args).exec();
            // ENOEXEC (8): script without #! shebang -> run via $PREFIX/bin/bash or /system/bin/sh
            if err.raw_os_error() == Some(libc::ENOEXEC) {
                let bash = prefix.join("bin/bash");
                let shell = if is_executable(&bash) {
                    bash
                } else {
                    std::path::PathBuf::from("/system/bin/sh")
                };
                let _ = Command::new(shell).arg(&target).args(args).exec();
            }
            eprintln!("agy-box shim: cannot exec {}: {}", target.display(), err);
            return 126;
        }
        let _ = Command::new("/system/bin/sh").args(args).exec();
        127
    }

    #[cfg(not(unix))]
    {
        if target.is_file() {
            match Command::new(&target).args(args).status() {
                Ok(st) => st.code().unwrap_or(1),
                Err(_) => 126,
            }
        } else {
            127
        }
    }
}
