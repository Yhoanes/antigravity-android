use crate::deb::extract_deb_and_rewrite_into_prefix;
use crate::env_util::{build_http_agent, is_executable, resolve_files_dir, resolve_prefix, sync_runtime_bridges};
use crate::vercmp::{deb_vercmp, ver_satisfies};
use flate2::read::GzDecoder;
use rayon::prelude::*;
use regex::Regex;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::env;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};

const PKG_VERSION: &str = "0.1.0";

#[derive(Debug, Clone)]
pub struct PkgConfig {
    pub prefix: PathBuf,
    pub pkg_root: PathBuf,
    pub pkg_db: PathBuf,
    pub pkg_cache: PathBuf,
    pub pkg_index: PathBuf,
    pub pkg_etc: PathBuf,
    pub pkg_cert: PathBuf,
    pub repos: Vec<String>,
    pub channel: String,
    pub component: String,
    pub arch: String,
    pub deb_strip: usize,
}

impl PkgConfig {
    pub fn load() -> Self {
        let prefix = resolve_prefix();
        let pkg_root = env::var("PKG_ROOT")
            .ok()
            .filter(|s| !s.is_empty())
            .map(PathBuf::from)
            .unwrap_or_else(|| prefix.join("var/lib/pkg"));
        let pkg_db = pkg_root.join("installed");
        let pkg_cache = pkg_root.join("cache");
        let pkg_index = pkg_root.join("index");
        let pkg_etc = prefix.join("etc/pkg");
        let pkg_cert = prefix.join("etc/tls/cert.pem");

        let mut repos = Vec::new();
        let mut channel = env::var("PKG_CHANNEL").unwrap_or_default();
        let mut component = env::var("PKG_COMPONENT").unwrap_or_default();
        let mut arch = env::var("PKG_ARCH").unwrap_or_default();
        let deb_strip = env::var("PKG_DEB_STRIP")
            .ok()
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(6);

        let conf_path = pkg_etc.join("repo.conf");
        if let Ok(raw) = fs::read_to_string(&conf_path) {
            parse_repo_conf(&raw, &mut repos, &mut channel, &mut component, &mut arch);
        }

        if repos.is_empty() {
            if let Ok(env_repos) = env::var("PKG_REPOS") {
                for r in env_repos.split_whitespace() {
                    if !r.is_empty() {
                        repos.push(r.trim_matches('"').trim_matches('\'').to_string());
                    }
                }
            }
        }
        if repos.is_empty() {
            repos.push("https://packages.termux.dev/apt/termux-main".to_string());
        }
        if channel.is_empty() {
            channel = "stable".to_string();
        }
        if component.is_empty() {
            component = "main".to_string();
        }
        if arch.is_empty() {
            arch = "aarch64".to_string();
        }

        Self {
            prefix,
            pkg_root,
            pkg_db,
            pkg_cache,
            pkg_index,
            pkg_etc,
            pkg_cert,
            repos,
            channel,
            component,
            arch,
            deb_strip,
        }
    }

    pub fn ensure_dirs(&self) {
        let _ = fs::create_dir_all(&self.pkg_db);
        let _ = fs::create_dir_all(&self.pkg_cache);
        let _ = fs::create_dir_all(&self.pkg_index);
        let _ = fs::create_dir_all(self.pkg_root.join("tmp"));
    }
}

pub fn parse_repo_conf(
    raw: &str,
    repos: &mut Vec<String>,
    channel: &mut String,
    component: &mut String,
    arch: &mut String,
) {
    let mut in_array = false;
    for raw_line in raw.lines() {
        let line = match raw_line.find('#') {
            Some(idx) => raw_line[..idx].trim(),
            None => raw_line.trim(),
        };
        if line.is_empty() {
            continue;
        }
        if in_array {
            if let Some(before_close) = line.split(')').next() {
                extract_tokens(before_close, repos);
            }
            if line.contains(')') {
                in_array = false;
            }
            continue;
        }
        if let Some(rest) = line.strip_prefix("PKG_REPOS=") {
            let rest = rest.trim();
            if let Some(after_open) = rest.strip_prefix('(') {
                if let Some(idx) = after_open.find(')') {
                    extract_tokens(&after_open[..idx], repos);
                } else {
                    extract_tokens(after_open, repos);
                    in_array = true;
                }
            } else {
                extract_tokens(rest, repos);
            }
        } else if let Some(v) = line.strip_prefix("PKG_CHANNEL=") {
            if channel.is_empty() {
                *channel = unquote(v);
            }
        } else if let Some(v) = line.strip_prefix("PKG_COMPONENT=") {
            if component.is_empty() {
                *component = unquote(v);
            }
        } else if let Some(v) = line.strip_prefix("PKG_ARCH=") {
            if arch.is_empty() {
                *arch = unquote(v);
            }
        }
    }
}

fn unquote(s: &str) -> String {
    s.trim().trim_matches('"').trim_matches('\'').to_string()
}

fn extract_tokens(s: &str, out: &mut Vec<String>) {
    for tok in s.split_whitespace() {
        let clean = unquote(tok);
        if !clean.is_empty() {
            out.push(clean);
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct PkgEntry {
    pub repo_id: String,
    pub name: String,
    pub version: String,
    pub arch: String,
    pub essential: String,
    pub depends: String,
    pub pre_depends: String,
    pub provides: String,
    pub conflicts: String,
    pub filename: String,
    pub size: String,
    pub sha256: String,
}

impl PkgEntry {
    pub fn to_tsv_line(&self) -> String {
        format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            self.name,
            self.version,
            self.arch,
            self.essential,
            self.depends.replace('\t', " "),
            self.pre_depends.replace('\t', " "),
            self.provides.replace('\t', " "),
            self.conflicts.replace('\t', " "),
            self.filename,
            self.size,
            self.sha256
        )
    }

    pub fn from_tsv_line(repo_id: &str, line: &str) -> Option<Self> {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.is_empty() || cols[0].is_empty() {
            return None;
        }
        let get = |i: usize| cols.get(i).copied().unwrap_or("").to_string();
        Some(Self {
            repo_id: repo_id.to_string(),
            name: get(0),
            version: get(1),
            arch: get(2),
            essential: get(3),
            depends: get(4),
            pre_depends: get(5),
            provides: get(6),
            conflicts: get(7),
            filename: get(8),
            size: get(9),
            sha256: get(10),
        })
    }
}

/// Fast pure-Rust parser for Debian/APT `Packages` stanzas (1:1 identical to `index.awk`).
pub fn parse_packages_stanzas(repo_id: &str, content: &str) -> Vec<PkgEntry> {
    let mut out = Vec::new();
    let mut cur = PkgEntry {
        repo_id: repo_id.to_string(),
        ..Default::default()
    };
    let mut last_key = String::new();

    let flush = |cur: &mut PkgEntry, out: &mut Vec<PkgEntry>| {
        if !cur.name.is_empty() {
            out.push(cur.clone());
        }
        *cur = PkgEntry {
            repo_id: repo_id.to_string(),
            ..Default::default()
        };
    };

    for raw_line in content.lines() {
        let line = raw_line.strip_suffix('\r').unwrap_or(raw_line);
        if line.is_empty() {
            flush(&mut cur, &mut out);
            last_key.clear();
            continue;
        }
        if line.starts_with(' ') || line.starts_with('\t') {
            let cont = &line[1..];
            match last_key.as_str() {
                "Depends" => {
                    cur.depends.push(' ');
                    cur.depends.push_str(cont);
                }
                "Pre-Depends" => {
                    cur.pre_depends.push(' ');
                    cur.pre_depends.push_str(cont);
                }
                "Provides" => {
                    cur.provides.push(' ');
                    cur.provides.push_str(cont);
                }
                "Conflicts" => {
                    cur.conflicts.push(' ');
                    cur.conflicts.push_str(cont);
                }
                _ => {}
            }
            continue;
        }
        if let Some(colon) = line.find(':') {
            let key = &line[..colon];
            let val = line[colon + 1..].trim_start_matches([' ', '\t']);
            match key {
                "Package" => cur.name = val.to_string(),
                "Version" => cur.version = val.to_string(),
                "Architecture" => cur.arch = val.to_string(),
                "Essential" => cur.essential = val.to_string(),
                "Depends" => cur.depends = val.to_string(),
                "Pre-Depends" => cur.pre_depends = val.to_string(),
                "Provides" => cur.provides = val.to_string(),
                "Conflicts" => cur.conflicts = val.to_string(),
                "Filename" => cur.filename = val.to_string(),
                "Size" => cur.size = val.to_string(),
                "SHA256" => cur.sha256 = val.to_string(),
                _ => {}
            }
            last_key = key.to_string();
        }
    }
    flush(&mut cur, &mut out);
    out
}

pub struct RepoCatalog {
    pub by_name: HashMap<String, PkgEntry>,
    pub by_provides: HashMap<String, PkgEntry>,
    pub all_entries: Vec<PkgEntry>,
    pub repo_urls: HashMap<String, String>,
}

impl RepoCatalog {
    pub fn load_from_index(pkg_index: &Path, rebuild_missing: bool) -> Self {
        let mut by_name: HashMap<String, PkgEntry> = HashMap::new();
        let mut by_provides: HashMap<String, PkgEntry> = HashMap::new();
        let mut all_entries: Vec<PkgEntry> = Vec::new();
        let mut repo_urls: HashMap<String, String> = HashMap::new();

        let rd = match fs::read_dir(pkg_index) {
            Ok(r) => r,
            Err(_) => {
                return Self {
                    by_name,
                    by_provides,
                    all_entries,
                    repo_urls,
                }
            }
        };

        let mut pkg_files: Vec<PathBuf> = Vec::new();
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if name.starts_with("repo.") && name.ends_with(".Packages") {
                pkg_files.push(e.path());
            } else if name.starts_with("repo.") && name.ends_with(".url") {
                let repo_id = name.trim_end_matches(".url").to_string();
                if let Ok(u) = fs::read_to_string(e.path()) {
                    repo_urls.insert(repo_id, u.trim().trim_end_matches('/').to_string());
                }
            }
        }
        pkg_files.sort();

        for pf in pkg_files {
            let fname = pf.file_name().unwrap().to_string_lossy();
            let repo_id = fname.trim_end_matches(".Packages").to_string();
            let tsv_path = pkg_index.join(format!("{repo_id}.tsv"));

            let entries: Vec<PkgEntry> = if !rebuild_missing && tsv_path.is_file() {
                fs::read_to_string(&tsv_path)
                    .unwrap_or_default()
                    .lines()
                    .filter_map(|l| PkgEntry::from_tsv_line(&repo_id, l))
                    .collect()
            } else if let Ok(raw) = fs::read_to_string(&pf) {
                let parsed = parse_packages_stanzas(&repo_id, &raw);
                let mut tsv_buf = String::with_capacity(parsed.len() * 120);
                for p in &parsed {
                    tsv_buf.push_str(&p.to_tsv_line());
                    tsv_buf.push('\n');
                }
                let _ = fs::write(&tsv_path, tsv_buf);
                parsed
            } else {
                Vec::new()
            };

            for entry in entries {
                if !entry.provides.is_empty() {
                    for prov in entry.provides.split(',') {
                        let pclean = prov
                            .split('(')
                            .next()
                            .unwrap_or("")
                            .trim()
                            .to_string();
                        if !pclean.is_empty() && !by_provides.contains_key(&pclean) {
                            by_provides.insert(pclean, entry.clone());
                        }
                    }
                }
                match by_name.get(&entry.name) {
                    None => {
                        by_name.insert(entry.name.clone(), entry.clone());
                    }
                    Some(existing) => {
                        if deb_vercmp(&entry.version, &existing.version) > 0 {
                            by_name.insert(entry.name.clone(), entry.clone());
                        }
                    }
                }
                all_entries.push(entry);
            }
        }

        Self {
            by_name,
            by_provides,
            all_entries,
            repo_urls,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.by_name.is_empty()
    }

    pub fn choose_dep(&self, term: &str) -> Option<&PkgEntry> {
        for alt in term.split('|') {
            let alt = alt.trim();
            if alt.is_empty() {
                continue;
            }
            let nm = alt
                .split(['(', '[', ':'])
                .next()
                .unwrap_or("")
                .trim();
            if nm.is_empty() {
                continue;
            }
            let (op, want_ver) = if let Some(start) = alt.find('(') {
                if let Some(end) = alt[start + 1..].find(')') {
                    let inside = alt[start + 1..start + 1 + end].trim();
                    let mut parts = inside.split_whitespace();
                    (
                        parts.next().unwrap_or(""),
                        parts.next().unwrap_or(""),
                    )
                } else {
                    ("", "")
                }
            } else {
                ("", "")
            };

            let cand = self.by_name.get(nm).or_else(|| self.by_provides.get(nm));
            if let Some(entry) = cand {
                if !want_ver.is_empty() && !ver_satisfies(&entry.version, op, want_ver) {
                    continue;
                }
                return Some(entry);
            }
        }
        None
    }

    pub fn resolve_install_order(&self, roots: &[String]) -> Vec<String> {
        let mut done = HashSet::new();
        let mut doing = HashSet::new();
        let mut order = Vec::new();
        for r in roots {
            let actual = if self.by_name.contains_key(r) {
                r.clone()
            } else if let Some(prov) = self.by_provides.get(r) {
                prov.name.clone()
            } else {
                r.clone()
            };
            self.visit_dep(&actual, &mut doing, &mut done, &mut order);
        }
        order
    }

    fn visit_dep(
        &self,
        name: &str,
        doing: &mut HashSet<String>,
        done: &mut HashSet<String>,
        order: &mut Vec<String>,
    ) {
        if done.contains(name) {
            return;
        }
        if doing.contains(name) {
            log_warn(&format!("dependency cycle at: {name}"));
            return;
        }
        let entry = match self.by_name.get(name) {
            Some(e) => e,
            None => {
                log_warn(&format!("unknown package: {name}"));
                return;
            }
        };
        doing.insert(name.to_string());
        let combined_deps = match (entry.depends.is_empty(), entry.pre_depends.is_empty()) {
            (true, true) => String::new(),
            (false, true) => entry.depends.clone(),
            (true, false) => entry.pre_depends.clone(),
            (false, false) => format!("{}, {}", entry.depends, entry.pre_depends),
        };
        for term in combined_deps.split(',') {
            let term = term.trim();
            if term.is_empty() {
                continue;
            }
            match self.choose_dep(term) {
                Some(cand) => self.visit_dep(&cand.name, doing, done, order),
                None => log_warn(&format!("cannot resolve dependency: {term} (for {name})")),
            }
        }
        doing.remove(name);
        done.insert(name.to_string());
        order.push(name.to_string());
    }
}

// ------------------------------------------------------------------ DB helpers
fn db_meta_path(pkg_db: &Path, name: &str) -> PathBuf {
    pkg_db.join(format!("{name}.meta"))
}

fn db_files_path(pkg_db: &Path, name: &str) -> PathBuf {
    pkg_db.join(format!("{name}.files"))
}

fn db_exists(pkg_db: &Path, name: &str) -> bool {
    db_meta_path(pkg_db, name).is_file()
}

fn db_get(pkg_db: &Path, name: &str, key: &str) -> Option<String> {
    let raw = fs::read_to_string(db_meta_path(pkg_db, name)).ok()?;
    let prefix = format!("{key}=");
    for line in raw.lines() {
        if let Some(val) = line.strip_prefix(&prefix) {
            return Some(val.to_string());
        }
    }
    None
}

fn db_put(pkg_db: &Path, name: &str, ver: &str, arch: &str, files: &[String]) -> io::Result<()> {
    fs::create_dir_all(pkg_db)?;
    let meta_p = db_meta_path(pkg_db, name);
    let files_p = db_files_path(pkg_db, name);
    let meta_tmp = pkg_db.join(format!("{name}.meta.tmp"));
    let files_tmp = pkg_db.join(format!("{name}.files.tmp"));

    let meta_body = format!("name={name}\nversion={ver}\narch={arch}\n");
    let mut files_body = String::new();
    for f in files {
        files_body.push_str(f);
        files_body.push('\n');
    }

    fs::write(&meta_tmp, meta_body)?;
    fs::write(&files_tmp, files_body)?;
    fs::rename(&meta_tmp, &meta_p)?;
    fs::rename(&files_tmp, &files_p)?;
    Ok(())
}

fn db_remove(pkg_db: &Path, name: &str) {
    let _ = fs::remove_file(db_meta_path(pkg_db, name));
    let _ = fs::remove_file(db_files_path(pkg_db, name));
}

fn db_sync_dpkg(cfg: &PkgConfig) {
    let status_file = cfg.prefix.join("var/lib/dpkg/status");
    let raw = match fs::read_to_string(&status_file) {
        Ok(r) => r,
        Err(_) => return,
    };
    cfg.ensure_dirs();
    let mut pkg = String::new();
    let mut ver = String::new();
    let mut arch = String::new();
    for line in raw.lines() {
        let line = line.trim_end_matches('\r');
        if line.is_empty() {
            pkg.clear();
            ver.clear();
            arch.clear();
        } else if let Some(v) = line.strip_prefix("Package:") {
            pkg = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("Version:") {
            ver = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("Architecture:") {
            arch = v.trim().to_string();
        } else if line.starts_with("Status:") && line.ends_with("installed") {
            if !pkg.is_empty() && !ver.is_empty() && !db_exists(&cfg.pkg_db, &pkg) {
                let a = if arch.is_empty() { "aarch64" } else { &arch };
                let _ = db_put(&cfg.pkg_db, &pkg, &ver, a, &[]);
            }
        }
    }
}

fn log_info(msg: &str) {
    eprintln!("pkg: {msg}");
}

fn log_warn(msg: &str) {
    eprintln!("pkg: warn: {msg}");
}

fn filter_pkg_args(args: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    let mut skip_next = false;
    for a in args {
        if skip_next {
            skip_next = false;
            continue;
        }
        match a.as_str() {
            "-o" | "--option" | "-t" | "--target-release" => skip_next = true,
            s if s.starts_with('-') => {}
            _ => out.push(a.clone()),
        }
    }
    out
}

fn sha256_file_hex(path: &Path) -> io::Result<String> {
    let mut f = fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(hex::encode(hasher.finalize()))
}

fn download_bytes(agent: &ureq::Agent, url: &str) -> Result<Vec<u8>, String> {
    let mut last_err = String::new();
    for attempt in 0..3 {
        if attempt > 0 {
            std::thread::sleep(std::time::Duration::from_millis(400 * attempt));
        }
        match agent.get(url).call() {
            Ok(resp) => {
                let mut buf = Vec::new();
                if let Err(e) = resp.into_reader().read_to_end(&mut buf) {
                    last_err = e.to_string();
                    continue;
                }
                return Ok(buf);
            }
            Err(e) => {
                last_err = e.to_string();
            }
        }
    }
    Err(last_err)
}

fn fetch_deb_verified(
    agent: &ureq::Agent,
    url: &str,
    expected_sha: &str,
    out_path: &Path,
) -> Result<(), String> {
    if out_path.is_file() && !expected_sha.is_empty() {
        if let Ok(have) = sha256_file_hex(out_path) {
            if have.eq_ignore_ascii_case(expected_sha) {
                log_info(&format!(
                    "cache hit: {}",
                    out_path.file_name().unwrap_or_default().to_string_lossy()
                ));
                return Ok(());
            }
        }
    }
    if let Some(parent) = out_path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    log_info(&format!("fetch: {url}"));
    let data = download_bytes(agent, url).map_err(|e| format!("download failed: {url} ({e})"))?;
    if !expected_sha.is_empty() {
        let have = hex::encode(Sha256::digest(&data));
        if !have.eq_ignore_ascii_case(expected_sha) {
            return Err(format!(
                "sha256 mismatch {}: got {} want {}",
                out_path.file_name().unwrap_or_default().to_string_lossy(),
                have,
                expected_sha
            ));
        }
    }
    let part = out_path.with_extension("deb.part");
    fs::write(&part, &data).map_err(|e| format!("write failed: {e}"))?;
    fs::rename(&part, out_path).map_err(|e| format!("rename failed: {e}"))?;
    Ok(())
}

// ------------------------------------------------------------------ Commands

fn cmd_update(cfg: &PkgConfig) -> i32 {
    cfg.ensure_dirs();
    if let Ok(rd) = fs::read_dir(&cfg.pkg_index) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().into_owned();
            if n.starts_with("repo.")
                && (n.ends_with(".Packages") || n.ends_with(".tsv") || n.ends_with(".url"))
            {
                let _ = fs::remove_file(e.path());
            }
        }
    }

    let agent = build_http_agent(Some(&cfg.pkg_cert), 45);
    let mut idx_num = 0usize;
    let mut got = 0usize;

    for repo_url in &cfg.repos {
        idx_num += 1;
        let base_url = repo_url.trim_end_matches('/');
        let full_url = format!(
            "{base_url}/dists/{}/{}/binary-{}/Packages.gz",
            cfg.channel, cfg.component, cfg.arch
        );
        log_info(&format!("update: {base_url}"));
        if fetch_and_index_repo(&agent, &cfg.pkg_index, idx_num, base_url, &full_url) {
            got += 1;
            break;
        }
    }

    // Extra repositories in $PREFIX/etc/apt/sources.list.d/*.list
    let sources_d = cfg.prefix.join("etc/apt/sources.list.d");
    let bracket_re = Regex::new(r"\[[^\]]*\]").unwrap();
    if let Ok(rd) = fs::read_dir(&sources_d) {
        let mut list_files: Vec<PathBuf> = rd
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().and_then(|s| s.to_str()) == Some("list"))
            .collect();
        list_files.sort();
        for lf in list_files {
            if let Ok(raw) = fs::read_to_string(&lf) {
                for raw_line in raw.lines() {
                    let no_comment = raw_line.split('#').next().unwrap_or("");
                    let clean = bracket_re.replace_all(no_comment, "");
                    let parts: Vec<&str> = clean.split_whitespace().collect();
                    if parts.len() >= 3 && parts[0] == "deb" {
                        let deb_url = parts[1].trim_end_matches('/');
                        let suite = parts[2];
                        let comp = parts.get(3).copied().unwrap_or("main");
                        idx_num += 1;
                        let full_url = format!(
                            "{deb_url}/dists/{suite}/{comp}/binary-{}/Packages.gz",
                            cfg.arch
                        );
                        log_info(&format!("update: {deb_url} ({suite}/{comp})"));
                        if fetch_and_index_repo(&agent, &cfg.pkg_index, idx_num, deb_url, &full_url)
                        {
                            got += 1;
                        }
                    }
                }
            }
        }
    }

    if got == 0 {
        eprintln!(
            "pkg: error: update failed: no repository index fetched (repos: {})",
            cfg.repos.join(" ")
        );
        return 1;
    }
    0
}

fn fetch_and_index_repo(
    agent: &ureq::Agent,
    pkg_index: &Path,
    idx_num: usize,
    base_url: &str,
    full_url: &str,
) -> bool {
    let gz_bytes = match download_bytes(agent, full_url) {
        Ok(b) => b,
        Err(_) => {
            log_warn(&format!("unreachable: {base_url}"));
            return false;
        }
    };
    let mut dec = GzDecoder::new(&gz_bytes[..]);
    let mut text = String::new();
    if dec.read_to_string(&mut text).is_err() {
        log_warn(&format!("gunzip failed: {base_url}"));
        return false;
    }
    let line_count = text.lines().count();
    let repo_id = format!("repo.{idx_num}");
    let idx_path = pkg_index.join(format!("{repo_id}.Packages"));
    let url_path = pkg_index.join(format!("{repo_id}.url"));
    let tsv_path = pkg_index.join(format!("{repo_id}.tsv"));

    if fs::write(&idx_path, &text).is_err() {
        return false;
    }
    let _ = fs::write(&url_path, format!("{base_url}\n"));

    let entries = parse_packages_stanzas(&repo_id, &text);
    let mut tsv_buf = String::with_capacity(entries.len() * 120);
    for e in &entries {
        tsv_buf.push_str(&e.to_tsv_line());
        tsv_buf.push('\n');
    }
    let _ = fs::write(&tsv_path, tsv_buf);
    log_info(&format!("  {line_count} lines"));
    true
}

fn install_entries_parallel_download(
    cfg: &PkgConfig,
    catalog: &RepoCatalog,
    to_install: &[PkgEntry],
) -> Result<(), String> {
    if to_install.is_empty() {
        return Ok(());
    }

    // 1. Parallel download + SHA-256 verification across up to 4 worker threads!
    let agent = build_http_agent(Some(&cfg.pkg_cert), 120);
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(4)
        .build()
        .map_err(|e| e.to_string())?;

    let tasks: Vec<(PkgEntry, String, PathBuf)> = to_install
        .iter()
        .map(|entry| {
            let base_url = catalog
                .repo_urls
                .get(&entry.repo_id)
                .cloned()
                .or_else(|| cfg.repos.first().cloned())
                .unwrap_or_default();
            let url = format!("{}/{}", base_url.trim_end_matches('/'), entry.filename);
            let fname = Path::new(&entry.filename)
                .file_name()
                .unwrap_or_default()
                .to_string_lossy()
                .into_owned();
            let cache_path = cfg.pkg_cache.join(fname);
            (entry.clone(), url, cache_path)
        })
        .collect();

    let dl_results: Vec<Result<(), String>> = pool.install(|| {
        tasks
            .par_iter()
            .map(|(entry, url, cache_path)| {
                if entry.filename.is_empty() {
                    return Err(format!("{}: no Filename in index", entry.name));
                }
                fetch_deb_verified(&agent, url, &entry.sha256, cache_path)
            })
            .collect()
    });

    for res in dl_results {
        res?;
    }

    // 2. Deterministic topological extraction + in-RAM prefix rewriting + DB commit
    for (entry, _url, cache_path) in &tasks {
        log_info(&format!("install: {} {}", entry.name, entry.version));
        let (ctrl_ver, files) =
            extract_deb_and_rewrite_into_prefix(cache_path, &cfg.prefix, cfg.deb_strip)
                .map_err(|e| format!("{}: extract failed ({e})", entry.name))?;
        let final_ver = ctrl_ver.filter(|v| !v.is_empty()).unwrap_or_else(|| entry.version.clone());
        let arch = if entry.arch.is_empty() {
            &cfg.arch
        } else {
            &entry.arch
        };
        db_put(&cfg.pkg_db, &entry.name, &final_ver, arch, &files)
            .map_err(|e| format!("{}: db write failed ({e})", entry.name))?;
        let _ = fs::remove_file(cache_path);
    }

    Ok(())
}

fn cmd_install(cfg: &PkgConfig, raw_args: &[String]) -> i32 {
    cfg.ensure_dirs();
    db_sync_dpkg(cfg);
    let catalog = RepoCatalog::load_from_index(&cfg.pkg_index, false);
    if catalog.is_empty() {
        eprintln!("pkg: error: no index (run: pkg update)");
        return 1;
    }

    let mut req = filter_pkg_args(raw_args);
    if req.is_empty() {
        eprintln!("pkg: error: install: need package name(s)");
        return 1;
    }

    if req.iter().any(|p| p == "openjdk-21" || p == "openjdk-17")
        && catalog.by_name.contains_key("ca-certificates-java")
        && !req.iter().any(|p| p == "ca-certificates-java")
    {
        req.push("ca-certificates-java".to_string());
    }

    for name in &req {
        if !catalog.by_name.contains_key(name)
            && !catalog.by_provides.contains_key(name)
            && !db_exists(&cfg.pkg_db, name)
        {
            eprintln!("pkg: error: unknown package: {name}");
            return 1;
        }
    }

    let order = catalog.resolve_install_order(&req);
    let mut to_install = Vec::new();
    let mut refresh_repos = false;

    for n in order {
        if db_exists(&cfg.pkg_db, &n) {
            log_info(&format!("already installed: {n}"));
            continue;
        }
        if let Some(entry) = catalog.by_name.get(&n) {
            if n.ends_with("-repo") {
                refresh_repos = true;
            }
            to_install.push(entry.clone());
        }
    }

    if let Err(msg) = install_entries_parallel_download(cfg, &catalog, &to_install) {
        eprintln!("pkg: error: {msg}");
        return 1;
    }

    sync_runtime_bridges(&cfg.prefix);
    if refresh_repos {
        log_info("refreshing repository indexes after installing *-repo package...");
        return cmd_update(cfg);
    }
    0
}

fn cmd_upgrade(cfg: &PkgConfig) -> i32 {
    cfg.ensure_dirs();
    db_sync_dpkg(cfg);
    let catalog = RepoCatalog::load_from_index(&cfg.pkg_index, false);
    if catalog.is_empty() {
        eprintln!("pkg: error: no index (run: pkg update)");
        return 1;
    }

    let mut to_upgrade: Vec<PkgEntry> = Vec::new();
    if let Ok(rd) = fs::read_dir(&cfg.pkg_db) {
        let mut meta_names: Vec<String> = rd
            .flatten()
            .filter_map(|e| {
                let s = e.file_name().to_string_lossy().into_owned();
                s.strip_suffix(".meta").map(|b| b.to_string())
            })
            .collect();
        meta_names.sort();

        for name in meta_names {
            let cur_ver = match db_get(&cfg.pkg_db, &name, "version") {
                Some(v) => v,
                None => continue,
            };
            if let Some(repo_entry) = catalog.by_name.get(&name) {
                if !repo_entry.version.is_empty() && deb_vercmp(&repo_entry.version, &cur_ver) > 0 {
                    log_info(&format!(
                        "upgrade available: {name} ({cur_ver} -> {})",
                        repo_entry.version
                    ));
                    to_upgrade.push(repo_entry.clone());
                }
            }
        }
    }

    if to_upgrade.is_empty() {
        log_info("all packages are up to date");
        return 0;
    }

    let names_list: Vec<&str> = to_upgrade.iter().map(|e| e.name.as_str()).collect();
    log_info(&format!(
        "upgrading {} package(s): {}",
        to_upgrade.len(),
        names_list.join(" ")
    ));

    for e in &to_upgrade {
        db_remove(&cfg.pkg_db, &e.name);
    }
    if let Err(msg) = install_entries_parallel_download(cfg, &catalog, &to_upgrade) {
        eprintln!("pkg: error: {msg}");
        return 1;
    }
    sync_runtime_bridges(&cfg.prefix);
    0
}

fn cmd_clean(cfg: &PkgConfig) -> i32 {
    cfg.ensure_dirs();
    let mut count = 0usize;
    if let Ok(rd) = fs::read_dir(&cfg.pkg_cache) {
        for e in rd.flatten() {
            let p = e.path();
            if p.is_file() && p.extension().and_then(|s| s.to_str()) == Some("deb") {
                if fs::remove_file(&p).is_ok() {
                    count += 1;
                }
            }
        }
    }
    log_info(&format!("clean: removed {count} cached package(s)"));
    0
}

fn cmd_remove(cfg: &PkgConfig, raw_args: &[String]) -> i32 {
    cfg.ensure_dirs();
    let names = filter_pkg_args(raw_args);
    if names.is_empty() {
        eprintln!("pkg: error: remove: need package name(s)");
        return 1;
    }
    let rbin = resolve_files_dir(&cfg.prefix).join("runtime/bin");

    for name in names {
        if !db_exists(&cfg.pkg_db, &name) {
            log_warn(&format!("not installed: {name}"));
            continue;
        }
        if let Ok(raw_files) = fs::read_to_string(db_files_path(&cfg.pkg_db, &name)) {
            for rel in raw_files.lines() {
                let rel = rel.trim();
                if rel.is_empty() {
                    continue;
                }
                let full = cfg.prefix.join(rel);
                let _ = fs::remove_file(&full);
                if let Some(bin_name) = rel.strip_prefix("bin/") {
                    if !bin_name.contains('/') && rbin.is_dir() {
                        let _ = fs::remove_file(rbin.join(bin_name));
                    }
                }
                let mut cur_dir = Path::new(rel).parent();
                while let Some(d) = cur_dir {
                    if d.as_os_str().is_empty() || d == Path::new(".") {
                        break;
                    }
                    if fs::remove_dir(cfg.prefix.join(d)).is_err() {
                        break;
                    }
                    cur_dir = d.parent();
                }
            }
        }
        db_remove(&cfg.pkg_db, &name);
        log_info(&format!("removed: {name}"));
    }
    sync_runtime_bridges(&cfg.prefix);
    0
}

fn cmd_list_or_search(cfg: &PkgConfig, pattern: Option<&str>, require_pat: bool) -> i32 {
    let pat = match pattern {
        Some(p) => p,
        None if require_pat => {
            eprintln!("pkg: error: search: need a pattern");
            return 1;
        }
        None => "",
    };
    let re = if pat.is_empty() {
        None
    } else {
        match Regex::new(pat) {
            Ok(r) => Some(r),
            Err(e) => {
                eprintln!("pkg: error: invalid regex '{pat}': {e}");
                return 1;
            }
        }
    };

    let catalog = RepoCatalog::load_from_index(&cfg.pkg_index, false);
    let mut rows = BTreeSet::new();
    for entry in &catalog.all_entries {
        let matched = match &re {
            None => true,
            Some(r) => r.is_match(&entry.name),
        };
        if matched {
            rows.insert(format!("{:<24} {}", entry.name, entry.version));
        }
    }
    for row in rows {
        println!("{row}");
    }
    0
}

fn cmd_installed(cfg: &PkgConfig) -> i32 {
    cfg.ensure_dirs();
    db_sync_dpkg(cfg);
    let mut items = BTreeMap::new();
    if let Ok(rd) = fs::read_dir(&cfg.pkg_db) {
        for e in rd.flatten() {
            let fname = e.file_name().to_string_lossy().into_owned();
            if let Some(name) = fname.strip_suffix(".meta") {
                let ver = db_get(&cfg.pkg_db, name, "version").unwrap_or_default();
                items.insert(name.to_string(), ver);
            }
        }
    }
    if items.is_empty() {
        log_info("nothing installed");
        return 0;
    }
    for (name, ver) in items {
        println!("{name} {ver}");
    }
    0
}

fn cmd_info(cfg: &PkgConfig, args: &[String]) -> i32 {
    cfg.ensure_dirs();
    let Some(target) = args.first() else {
        eprintln!("pkg: error: info: need a package name");
        return 1;
    };
    let catalog = RepoCatalog::load_from_index(&cfg.pkg_index, false);
    let Some(e) = catalog.by_name.get(target) else {
        eprintln!("pkg: error: info: unknown package: {target}");
        return 1;
    };
    println!("name:        {}", e.name);
    println!("version:     {}", e.version);
    println!("arch:        {}", e.arch);
    println!("essential:   {}", e.essential);
    println!("depends:     {}", e.depends);
    println!("pre-depends: {}", e.pre_depends);
    println!("provides:    {}", e.provides);
    println!("conflicts:   {}", e.conflicts);
    println!("filename:    {}", e.filename);
    println!("size:        {}", e.size);
    println!("sha256:      {}", e.sha256);
    0
}

fn cmd_why(cfg: &PkgConfig, args: &[String]) -> i32 {
    cfg.ensure_dirs();
    let Some(query) = args.first() else {
        eprintln!("pkg: error: why: need a file path");
        return 1;
    };
    let mut owners = BTreeSet::new();
    if let Ok(rd) = fs::read_dir(&cfg.pkg_db) {
        for e in rd.flatten() {
            let fname = e.file_name().to_string_lossy().into_owned();
            if let Some(pkg) = fname.strip_suffix(".files") {
                if let Ok(raw) = fs::read_to_string(e.path()) {
                    if raw.contains(query.as_str()) {
                        owners.insert(pkg.to_string());
                    }
                }
            }
        }
    }
    if owners.is_empty() {
        log_warn(&format!("no owner for {query}"));
        return 1;
    }
    for o in owners {
        println!("{o}");
    }
    0
}

fn cmd_selftest(cfg: &PkgConfig) -> i32 {
    let mut fail = false;
    for sh_name in ["sh", "bash"] {
        let bin = cfg.prefix.join("bin").join(sh_name);
        let ok = is_executable(&bin)
            && std::process::Command::new(&bin)
                .args(["-c", "exit 0"])
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .status()
                .map(|s| s.success())
                .unwrap_or(false);
        if ok {
            println!("PASS  {sh_name} present and runnable");
        } else {
            println!("FAIL  {sh_name} missing or not runnable ({})", bin.display());
            fail = true;
        }
    }

    if fs::File::open(&cfg.pkg_cert).is_ok() {
        println!("PASS  tls cert readable");
    } else {
        println!("FAIL  tls cert not readable ({})", cfg.pkg_cert.display());
        fail = true;
    }

    let repo_conf = cfg.pkg_etc.join("repo.conf");
    if repo_conf.is_file()
        && !cfg.repos.is_empty()
        && !cfg.channel.is_empty()
        && !cfg.arch.is_empty()
    {
        println!(
            "PASS  repo config resolves ({} repos, {}/{})",
            cfg.repos.len(),
            cfg.arch,
            cfg.channel
        );
    } else {
        println!("FAIL  repo config does not resolve ({})", repo_conf.display());
        fail = true;
    }

    if !fail {
        println!("PASS  pkg selftest");
        0
    } else {
        println!("FAIL  pkg selftest");
        1
    }
}

fn cmd_help(cfg: &PkgConfig) {
    println!(
        "pkg {PKG_VERSION} (agy-box rust engine) -- tlx package manager\n\n\
         \x20 pkg update              fetch repository indexes\n\
         \x20 pkg upgrade             upgrade installed packages\n\
         \x20 pkg install <pkg...>    resolve, parallel-download, extract\n\
         \x20 pkg remove <pkg...>     remove installed packages\n\
         \x20 pkg clean               remove cached .deb archives\n\
         \x20 pkg list [regex]        available packages\n\
         \x20 pkg installed           installed packages\n\
         \x20 pkg info <pkg>          package metadata\n\
         \x20 pkg search <regex>      search by name\n\
         \x20 pkg why <file>          which package owns a file\n\
         \x20 pkg selftest            offline self-test\n\
         \x20 pkg help | version\n\n\
         env: PREFIX={}  PKG_ROOT={}",
        cfg.prefix.display(),
        cfg.pkg_root.display()
    );
}

pub fn run(args: &[String]) -> i32 {
    let cfg = PkgConfig::load();
    let mut i = 0usize;
    while i < args.len() {
        match args[i].as_str() {
            "-h" | "--help" => {
                cmd_help(&cfg);
                return 0;
            }
            "-v" | "--version" => {
                println!("pkg {PKG_VERSION}");
                return 0;
            }
            "-o" | "--option" | "-t" | "--target-release" => {
                i = (i + 2).min(args.len());
            }
            s if s.starts_with('-') => {
                i += 1;
            }
            _ => break,
        }
    }

    let cmd = args.get(i).map(|s| s.as_str()).unwrap_or("help");
    let rest = if i + 1 <= args.len() {
        &args[i + 1..]
    } else {
        &[]
    };

    match cmd {
        "update" => cmd_update(&cfg),
        "upgrade" | "dist-upgrade" | "full-upgrade" => cmd_upgrade(&cfg),
        "install" | "add" => cmd_install(&cfg, rest),
        "remove" | "uninstall" | "purge" | "delete" => cmd_remove(&cfg, rest),
        "clean" | "autoclean" | "autoremove" => cmd_clean(&cfg),
        "list" | "list-all" => cmd_list_or_search(&cfg, rest.first().map(|s| s.as_str()), false),
        "installed" | "list-installed" => cmd_installed(&cfg),
        "info" | "show" => cmd_info(&cfg, rest),
        "search" => cmd_list_or_search(&cfg, rest.first().map(|s| s.as_str()), true),
        "why" | "files" => cmd_why(&cfg, rest),
        "selftest" => cmd_selftest(&cfg),
        "version" | "-v" | "--version" => {
            println!("pkg {PKG_VERSION}");
            0
        }
        "help" | "-h" | "--help" => {
            cmd_help(&cfg);
            0
        }
        other => {
            eprintln!("pkg: error: unknown command: {other} (try: pkg help)");
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_packages_parser_and_resolver() {
        let fix = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../tests/fixtures/Packages");
        let raw = fs::read_to_string(&fix).unwrap();
        let entries = parse_packages_stanzas("repo.1", &raw);
        assert_eq!(entries.len(), 3);

        let hello = entries.iter().find(|e| e.name == "hello").unwrap();
        assert_eq!(hello.version, "1.0.0");
        assert_eq!(hello.depends, "libhello (>= 1.0), world | glob");

        let world = entries.iter().find(|e| e.name == "world").unwrap();
        assert_eq!(world.essential, "yes");
        assert_eq!(world.provides, "glob");

        // Build catalog and resolve hello -> [libhello, world, hello]
        let tmp = env::temp_dir().join(format!("agy_box_pkg_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        fs::write(tmp.join("repo.1.Packages"), &raw).unwrap();

        let catalog = RepoCatalog::load_from_index(&tmp, true);
        let order = catalog.resolve_install_order(&["hello".to_string()]);
        assert_eq!(order, vec!["libhello", "world", "hello"]);

        // Test repo.conf bash array parser
        let conf_file = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../overlay/etc/pkg/repo.conf");
        let conf_raw = fs::read_to_string(conf_file).unwrap();
        let mut repos = Vec::new();
        let mut ch = String::new();
        let mut comp = String::new();
        let mut arch = String::new();
        parse_repo_conf(&conf_raw, &mut repos, &mut ch, &mut comp, &mut arch);
        assert_eq!(repos.len(), 3);
        assert_eq!(ch, "stable");
        assert_eq!(comp, "main");
        assert_eq!(arch, "aarch64");

        let _ = fs::remove_dir_all(&tmp);
    }
}
