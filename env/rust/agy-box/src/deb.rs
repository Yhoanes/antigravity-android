use crate::env_util::{rewrite_prefix_in_bytes, rewrite_symlink_target};
use flate2::read::GzDecoder;
use std::collections::BTreeSet;
use std::fs::{self, File};
use std::io::{self, Cursor, Read};
use std::path::{Component, Path, PathBuf};
use tar::{Archive, EntryType};

#[derive(Debug, Clone)]
pub struct ArMember {
    pub name: String,
    pub offset: usize,
    pub size: usize,
}

/// Pure-Rust parser for standard Debian `.deb` (`!<arch>\n`) archives.
pub fn parse_ar_members(data: &[u8]) -> io::Result<Vec<ArMember>> {
    if data.len() < 8 || &data[0..8] != b"!<arch>\n" {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "not an ar archive"));
    }
    let mut off = 8usize;
    let mut members = Vec::new();
    while off + 60 <= data.len() {
        let hdr = &data[off..off + 60];
        if &hdr[58..60] != b"`\n" {
            break;
        }
        let raw_name = std::str::from_utf8(&hdr[0..16]).unwrap_or("").trim_end();
        let name = raw_name.trim_end_matches('/').to_string();
        let size_str = std::str::from_utf8(&hdr[48..58]).unwrap_or("").trim();
        let size = size_str
            .parse::<usize>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "bad ar member size"))?;
        let data_off = off + 60;
        if data_off + size > data.len() {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated ar member",
            ));
        }
        if !name.is_empty() {
            members.push(ArMember {
                name,
                offset: data_off,
                size,
            });
        }
        off = data_off + size + (size % 2);
    }
    Ok(members)
}

#[cfg(test)]
pub fn ar_member_names(deb_path: &Path) -> io::Result<Vec<String>> {
    let raw = fs::read(deb_path)?;
    let members = parse_ar_members(&raw)?;
    Ok(members.into_iter().map(|m| m.name).collect())
}

fn decode_tar_payload(member_name: &str, slice: &[u8]) -> io::Result<Vec<u8>> {
    if member_name.ends_with(".xz") {
        let mut out = Vec::with_capacity(slice.len() * 3);
        let mut cursor = Cursor::new(slice);
        lzma_rs::xz_decompress(&mut cursor, &mut out)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("xz decode: {e}")))?;
        Ok(out)
    } else if member_name.ends_with(".gz") {
        let mut dec = GzDecoder::new(slice);
        let mut out = Vec::with_capacity(slice.len() * 2);
        dec.read_to_end(&mut out)?;
        Ok(out)
    } else if member_name.ends_with(".zst") {
        let mut dec = ruzstd::StreamingDecoder::new(slice)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, format!("zstd decode: {e}")))?;
        let mut out = Vec::with_capacity(slice.len() * 2);
        dec.read_to_end(&mut out)?;
        Ok(out)
    } else if member_name.ends_with(".tar") {
        Ok(slice.to_vec())
    } else {
        Err(io::Error::new(
            io::ErrorKind::Unsupported,
            format!("unsupported tar compression: {member_name}"),
        ))
    }
}

fn find_tar_member<'a>(members: &'a [ArMember], prefix: &str) -> Option<&'a ArMember> {
    let want = format!("{prefix}.tar");
    members
        .iter()
        .find(|m| m.name == want || m.name.starts_with(&format!("{prefix}.tar.")))
}

/// Reads an RFC822 field (e.g. `"Version"`, `"Depends"`) from `control.tar.*` inside a `.deb`.
#[cfg(test)]
pub fn deb_control_field(deb_path: &Path, field: &str) -> io::Result<Option<String>> {
    let raw = fs::read(deb_path)?;
    let members = parse_ar_members(&raw)?;
    let ctrl = find_tar_member(&members, "control")
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no control.tar in deb"))?;
    let tar_bytes = decode_tar_payload(&ctrl.name, &raw[ctrl.offset..ctrl.offset + ctrl.size])?;
    let mut archive = Archive::new(Cursor::new(tar_bytes));
    let key_prefix = format!("{field}:");
    for entry_res in archive.entries()? {
        let mut entry = entry_res?;
        let p = entry.path()?.to_string_lossy().into_owned();
        let clean = p.trim_start_matches("./");
        if clean == "control" {
            let mut text = String::new();
            entry.read_to_string(&mut text)?;
            for line in text.lines() {
                if let Some(rest) = line.strip_prefix(&key_prefix) {
                    return Ok(Some(rest.trim_start().to_string()));
                }
            }
        }
    }
    Ok(None)
}

fn strip_path_components(raw_path: &Path, strip_count: usize) -> Option<PathBuf> {
    let comps: Vec<String> = raw_path
        .components()
        .filter_map(|c| match c {
            Component::CurDir => {
                // In GNU tar, "./data/data/com.termux/files/usr/bin/hello" has "." as component 1!
                Some(".".to_string())
            }
            Component::Normal(os) => Some(os.to_string_lossy().into_owned()),
            _ => None,
        })
        .collect();

    // Notice how `tar -x --strip-components=6` counts components separated by `/`:
    // e.g. `./data/data/com.termux/files/usr/bin/hello` -> `.` (1), `data` (2), `data` (3), `com.termux` (4), `files` (5), `usr` (6) -> `bin/hello`!
    // What if archive has `data/data/com.termux/files/usr/bin/hello` (5 components before bin)?
    // Let's handle BOTH exact `strip_count` AND smart fallback if no leading `.` is present!
    let raw_str = raw_path.to_string_lossy();
    let parts: Vec<&str> = raw_str
        .split('/')
        .filter(|s| !s.is_empty())
        .collect();
    let effective_strip = if parts.first() == Some(&".") {
        strip_count
    } else if strip_count >= 6 && parts.first() == Some(&"data") {
        strip_count - 1
    } else {
        strip_count
    };

    let _ = comps;
    if parts.len() <= effective_strip {
        return None;
    }
    let mut out = PathBuf::new();
    for seg in &parts[effective_strip..] {
        if *seg == ".." || *seg == "." {
            return None;
        }
        out.push(seg);
    }
    if out.as_os_str().is_empty() {
        None
    } else {
        Some(out)
    }
}

#[cfg(test)]
pub fn deb_list_files(deb_path: &Path, strip_count: usize) -> io::Result<Vec<String>> {
    let raw = fs::read(deb_path)?;
    let members = parse_ar_members(&raw)?;
    let data_m = find_tar_member(&members, "data")
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no data.tar in deb"))?;
    let tar_bytes = decode_tar_payload(&data_m.name, &raw[data_m.offset..data_m.offset + data_m.size])?;
    let mut archive = Archive::new(Cursor::new(tar_bytes));
    let mut set = BTreeSet::new();
    for entry_res in archive.entries()? {
        let entry = entry_res?;
        let p = entry.path()?;
        if let Some(rel) = strip_path_components(&p, strip_count) {
            set.insert(rel.to_string_lossy().replace('\\', "/"));
        }
    }
    Ok(set.into_iter().collect())
}

/// Extracts `data.tar.*` directly into `dest_prefix`, performing **on-the-fly** foreign prefix
/// rewriting in RAM before writing to flash storage, and returns the sorted list of installed relative file/symlink paths.
pub fn extract_deb_and_rewrite_into_prefix(
    deb_path: &Path,
    dest_prefix: &Path,
    strip_count: usize,
) -> io::Result<(Option<String>, Vec<String>)> {
    let raw = fs::read(deb_path)?;
    let members = parse_ar_members(&raw)?;

    let ctrl_ver = if let Some(ctrl_m) = find_tar_member(&members, "control") {
        if let Ok(tb) = decode_tar_payload(&ctrl_m.name, &raw[ctrl_m.offset..ctrl_m.offset + ctrl_m.size]) {
            let mut arch = Archive::new(Cursor::new(tb));
            let mut found = None;
            if let Ok(entries) = arch.entries() {
                for er in entries.flatten() {
                    let mut er = er;
                    if let Ok(p) = er.path() {
                        if p.to_string_lossy().trim_start_matches("./") == "control" {
                            let mut txt = String::new();
                            if er.read_to_string(&mut txt).is_ok() {
                                for line in txt.lines() {
                                    if let Some(v) = line.strip_prefix("Version:") {
                                        found = Some(v.trim().to_string());
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            found
        } else {
            None
        }
    } else {
        None
    };

    let data_m = find_tar_member(&members, "data")
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "no data.tar in deb"))?;
    let tar_bytes = decode_tar_payload(&data_m.name, &raw[data_m.offset..data_m.offset + data_m.size])?;
    let mut archive = Archive::new(Cursor::new(tar_bytes));

    let prefix_str = dest_prefix.to_string_lossy().into_owned();
    let mut installed_files = BTreeSet::new();
    let mut hardlinks: Vec<(PathBuf, PathBuf, String)> = Vec::new();

    for entry_res in archive.entries()? {
        let mut entry = entry_res?;
        let raw_entry_path = entry.path()?.into_owned();
        let rel = match strip_path_components(&raw_entry_path, strip_count) {
            Some(r) => r,
            None => continue,
        };
        let rel_unix = rel.to_string_lossy().replace('\\', "/");
        let out_path = dest_prefix.join(&rel);
        let etype = entry.header().entry_type();
        let mode = entry.header().mode().unwrap_or(0o644);

        match etype {
            EntryType::Directory => {
                fs::create_dir_all(&out_path)?;
                apply_unix_mode(&out_path, mode | 0o700);
            }
            EntryType::Regular | EntryType::Continuous => {
                if let Some(parent) = out_path.parent() {
                    fs::create_dir_all(parent)?;
                }
                let mut buf = Vec::with_capacity(entry.size() as usize);
                entry.read_to_end(&mut buf)?;
                let final_bytes = rewrite_prefix_in_bytes(&buf, &prefix_str).unwrap_or(buf);

                let tmp_path = out_path.with_file_name(format!(
                    ".{}.agytmp",
                    out_path.file_name().unwrap_or_default().to_string_lossy()
                ));
                {
                    let mut f = File::create(&tmp_path)?;
                    io::Write::write_all(&mut f, &final_bytes)?;
                }
                apply_unix_mode(&tmp_path, mode);
                let _ = fs::remove_file(&out_path);
                fs::rename(&tmp_path, &out_path)?;
                installed_files.insert(rel_unix);
            }
            EntryType::Symlink => {
                if let Some(parent) = out_path.parent() {
                    fs::create_dir_all(parent)?;
                }
                if let Some(link_name) = entry.link_name()? {
                    let link_str = link_name.to_string_lossy().into_owned();
                    let final_link = rewrite_symlink_target(&link_str, &prefix_str).unwrap_or(link_str);
                    write_symlink(&final_link, &out_path)?;
                    installed_files.insert(rel_unix);
                }
            }
            EntryType::Link => {
                if let Some(link_name) = entry.link_name()? {
                    if let Some(target_rel) = strip_path_components(&link_name, strip_count) {
                        hardlinks.push((dest_prefix.join(target_rel), out_path, rel_unix));
                    }
                }
            }
            _ => {}
        }
    }

    for (src, dst, rel_unix) in hardlinks {
        if let Some(parent) = dst.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let _ = fs::remove_file(&dst);
        if fs::hard_link(&src, &dst).is_ok() || fs::copy(&src, &dst).is_ok() {
            installed_files.insert(rel_unix);
        }
    }

    Ok((ctrl_ver, installed_files.into_iter().collect()))
}

fn apply_unix_mode(path: &Path, mode: u32) {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let clean_mode = mode & 0o7777;
        if clean_mode != 0 {
            let _ = fs::set_permissions(path, fs::Permissions::from_mode(clean_mode));
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (path, mode);
    }
}

fn write_symlink(target: &str, link_path: &Path) -> io::Result<()> {
    let _ = fs::remove_file(link_path);
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, link_path)
    }
    #[cfg(not(unix))]
    {
        // Host Windows unit-test fallback: write marker file if symlink privileges unavailable
        fs::write(link_path, target.as_bytes())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hello_deb_fixture() {
        let deb = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../tests/fixtures/hello.deb");
        assert!(deb.is_file(), "fixture hello.deb must exist");

        let members = ar_member_names(&deb).unwrap();
        assert_eq!(members, vec!["debian-binary", "control.tar.xz", "data.tar.xz"]);

        assert_eq!(deb_control_field(&deb, "Version").unwrap().as_deref(), Some("2.12.3-1"));
        assert_eq!(deb_control_field(&deb, "Depends").unwrap().as_deref(), Some("libiconv"));

        let files = deb_list_files(&deb, 6).unwrap();
        assert!(files.iter().any(|f| f == "bin/hello"), "files: {:?}", files);
    }
}
