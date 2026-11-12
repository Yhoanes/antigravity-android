use std::cmp::Ordering;

fn char_ord(c: Option<u8>) -> i32 {
    match c {
        None => 0,
        Some(b'~') => -1,
        Some(b) if b.is_ascii_alphabetic() => b as i32,
        Some(b) => (b as i32) + 256,
    }
}

fn verrevcmp(s: &[u8], t: &[u8]) -> Ordering {
    let mut i = 0usize;
    let mut j = 0usize;
    while i < s.len() || j < t.len() {
        while (i < s.len() && !s[i].is_ascii_digit())
            || (j < t.len() && !t[j].is_ascii_digit())
        {
            let ca = if i < s.len() && !s[i].is_ascii_digit() {
                Some(s[i])
            } else {
                None
            };
            let cb = if j < t.len() && !t[j].is_ascii_digit() {
                Some(t[j])
            } else {
                None
            };
            let raw_a = s.get(i).copied();
            let raw_b = t.get(j).copied();
            if raw_a.is_none() && raw_b.is_none() {
                break;
            }
            let oa = char_ord(ca);
            let ob = char_ord(cb);
            if oa != ob {
                return oa.cmp(&ob);
            }
            if i < s.len() {
                i += 1;
            }
            if j < t.len() {
                j += 1;
            }
        }
        while i < s.len() && s[i] == b'0' {
            i += 1;
        }
        while j < t.len() && t[j] == b'0' {
            j += 1;
        }
        let da_start = i;
        while i < s.len() && s[i].is_ascii_digit() {
            i += 1;
        }
        let db_start = j;
        while j < t.len() && t[j].is_ascii_digit() {
            j += 1;
        }
        let da = &s[da_start..i];
        let db = &t[db_start..j];
        if da.len() != db.len() {
            return da.len().cmp(&db.len());
        }
        if da != db {
            return da.cmp(db);
        }
    }
    Ordering::Equal
}

fn split_epoch_upstream_rev(v: &str) -> (u64, &str, &str) {
    let (epoch, rest) = match v.find(':') {
        Some(idx) => {
            let ep = v[..idx].trim().parse::<u64>().unwrap_or(0);
            (ep, &v[idx + 1..])
        }
        None => (0, v),
    };
    let (upstream, rev) = match rest.rfind('-') {
        Some(idx) => (&rest[..idx], &rest[idx + 1..]),
        None => (rest, ""),
    };
    (epoch, upstream, rev)
}

/// Exact Debian dpkg version comparison: returns -1, 0, or 1.
pub fn deb_vercmp(a: &str, b: &str) -> i32 {
    let (ea, ua, ra) = split_epoch_upstream_rev(a.trim());
    let (eb, ub, rb) = split_epoch_upstream_rev(b.trim());
    match ea.cmp(&eb) {
        Ordering::Less => return -1,
        Ordering::Greater => return 1,
        Ordering::Equal => {}
    }
    match verrevcmp(ua.as_bytes(), ub.as_bytes()) {
        Ordering::Less => return -1,
        Ordering::Greater => return 1,
        Ordering::Equal => {}
    }
    match verrevcmp(ra.as_bytes(), rb.as_bytes()) {
        Ordering::Less => -1,
        Ordering::Equal => 0,
        Ordering::Greater => 1,
    }
}

pub fn ver_satisfies(ver: &str, op: &str, want: &str) -> bool {
    if op.is_empty() || want.is_empty() {
        return true;
    }
    let c = deb_vercmp(ver, want);
    match op {
        ">=" => c != -1,
        "<=" => c != 1,
        "=" | "==" => c == 0,
        ">>" | ">" => c == 1,
        "<<" | "<" => c == -1,
        _ => true,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selftest_vercmp_suite() {
        assert_eq!(deb_vercmp("1.0.0", "1.0.0"), 0);
        assert_eq!(deb_vercmp("1.0.0", "1.0.1"), -1);
        assert_eq!(deb_vercmp("1.0.1", "1.0.0"), 1);
        assert_eq!(deb_vercmp("2.0", "10.0"), -1);
        assert_eq!(deb_vercmp("1.0~rc1", "1.0"), -1);
        assert_eq!(deb_vercmp("1:1.0", "2.0"), 1);
        assert_eq!(deb_vercmp("1.0-1", "1.0-2"), -1);
        assert_eq!(deb_vercmp("1.0", "1.0-0"), 0);
        assert_eq!(deb_vercmp("1.0-1", "1.0-1"), 0);
        assert_eq!(deb_vercmp("1.2.3-4", "1.2.3-4build1"), -1);
        assert_eq!(deb_vercmp("1.0a", "1.0b"), -1);
        assert_eq!(deb_vercmp("1.0+really1.1", "1.0"), 1);
    }

    #[test]
    fn selftest_constraints_suite() {
        assert!(ver_satisfies("1.2.0", ">=", "1.0.0"));
        assert!(!ver_satisfies("0.9", ">=", "1.0.0"));
        assert!(ver_satisfies("1.0", "=", "1.0"));
        assert!(!ver_satisfies("1.0", ">>", "1.0"));
        assert!(ver_satisfies("1:2.0", ">=", "2.0"));
    }
}
