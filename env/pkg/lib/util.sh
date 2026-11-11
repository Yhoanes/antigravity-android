#!/data/data/com.agy/files/usr/bin/bash
# util.sh -- shared helpers for pkg.

PKG_VERSION="0.1.0"

: "${PREFIX:=/data/data/com.agy/files/usr}"
PKG_ROOT="${PKG_ROOT:-$PREFIX/var/lib/pkg}"
PKG_DB="$PKG_ROOT/installed"
PKG_CACHE="$PKG_ROOT/cache"
PKG_INDEX="$PKG_ROOT/index"
PKG_ETC="$PREFIX/etc/pkg"

# TLS trust: the overlay ships the bundle at $PREFIX/etc/tls/cert.pem. Termux's
# curl is compiled with a /data/data/com.termux/... default, so point it at ours.
PKG_CERT="$PREFIX/etc/tls/cert.pem"
export SSL_CERT_FILE="$PKG_CERT"
export CURL_CA_BUNDLE="$PKG_CERT"
export OPENSSL_CONF="${OPENSSL_CONF:-$PREFIX/etc/tls/openssl.cnf}"

log()  { printf 'pkg: %s\n' "$*" >&2; }
warn() { printf 'pkg: warn: %s\n' "$*" >&2; }
die()  { printf 'pkg: error: %s\n' "$*" >&2; exit 1; }

ensure_dirs() { mkdir -p "$PKG_DB" "$PKG_CACHE" "$PKG_INDEX" "$PKG_ROOT/tmp"; }

# rewrite_prefix DIRS... -- rewrite foreign-prefix paths to $PREFIX.
# Matches any Android app-private prefix (/data/data/com.<id>/files/usr): both
# the Termux debs we install and our own baked default prefix. Every occurrence
# is replaced, not only the shebang, so helpers that hardcode the prefix in
# their body (gzexe tmpdir, dpkg .list, *-config) work after extraction.
# Text-only (grep -I skips binaries), follows no symlinks, idempotent.
rewrite_prefix() {
    local f esc link target suffix
    # Escape sed-replacement metacharacters in $PREFIX (never expected, cheap).
    esc="$(printf '%s' "$PREFIX" | sed 's/[\\&#]/\\&/g')"
    # One grep walk finds every matching text file, then one sed per file.
    # Avoids spawning 2-3 processes per file and re-reads nothing.
    grep -rIlZ --exclude-dir=ghidra_12.1.4_PUBLIC -E '/data/(data|user/[0-9]+)/com\.[^/]*/files/usr' -- "$@" 2>/dev/null \
        | while IFS= read -r -d '' f; do
            sed -i -E "s#/data/(data|user/[0-9]+)/com\.[^/]*/files/usr#$esc#g" "$f"
        done
    # Repoint absolute symlinks that point into a foreign Termux/app prefix.
    find "$@" -not -path '*/ghidra_12.1.4_PUBLIC/*' -type l -print0 2>/dev/null \
        | while IFS= read -r -d '' link; do
            target="$(readlink "$link" 2>/dev/null || true)"
            case "$target" in
                /data/[d]ata/com.*/files/usr*|/data/[u]ser/*/com.*/files/usr*)
                    suffix="$(printf '%s' "$target" | sed -E 's#^/data/(data|user/[0-9]+)/com\.[^/]*/files/usr##')"
                    ln -snf "$PREFIX$suffix" "$link" 2>/dev/null || true
                    ;;
            esac
        done
}

# sync_runtime_bridges -- materialize runtime/bin bridges for any new $PREFIX/bin
# tools and prune bridges for removed tools so language_server sees changes
# immediately without an app restart.
sync_runtime_bridges() {
    local rbin
    rbin="$(cd "$(dirname "$PREFIX")" 2>/dev/null && pwd)/runtime/bin"
    [ -d "$rbin" ] || return 0
    local tool name bridge count=0
    for tool in "$PREFIX/bin"/*; do
        [ -f "$tool" ] || continue
        name="$(basename "$tool")"
        count=$((count + 1))
        case "$name" in
            rish|xdg-open|open|open_helper|sensible-browser|x-www-browser|gnome-open|google-chrome|chrome|chromium|chromium-browser|torch|termux-torch|termux-camera-torch|webp_encoder|webm_encoder|webm_encoder.elf|analyzeHeadless|craft-anything-cli|craft-ghidra-setup)
                continue
                ;;
        esac
        bridge="$rbin/$name"
        [ -f "$bridge" ] && continue
        cat > "$bridge" <<EOF
#!/system/bin/sh
# AGY bridge -> bionic \$PREFIX/bin/$name
PREFIX='$PREFIX'
export PREFIX
export HOME="\$(dirname "\$PREFIX")"
export TMPDIR="\$PREFIX/tmp"
export JDK_JAVA_OPTIONS="-Duser.home=\$HOME -Djava.io.tmpdir=\$PREFIX/tmp"
if [ -f "\$PREFIX/lib/libtermux-exec.so" ]; then
  export LD_PRELOAD="\$PREFIX/lib/libtermux-exec.so"
else
  unset LD_PRELOAD
fi
export LD_LIBRARY_PATH="\$PREFIX/lib"
export PATH="\$PREFIX/bin:$rbin:/system/bin"
export OPENSSL_CONF="\$PREFIX/etc/tls/openssl.cnf"
export SSL_CERT_FILE="\$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="\$PREFIX/etc/tls/cert.pem"
if [ -x "\$PREFIX/bin/$name" ]; then
  exec "\$PREFIX/bin/$name" "\$@"
else
  exec /system/bin/sh "\$@"
fi
EOF
        chmod 755 "$bridge" 2>/dev/null || true
    done
    printf '3\n%s\n%d\n' "$PREFIX" "$count" > "$rbin/.agy-bridges" 2>/dev/null || true
}


load_conf() {
    [ -f "$PKG_ETC/repo.conf" ] && . "$PKG_ETC/repo.conf"
    : "${PKG_REPOS:=https://packages.termux.dev/apt/termux-main}"
    : "${PKG_CHANNEL:=stable}"
    : "${PKG_COMPONENT:=main}"
    : "${PKG_ARCH:=aarch64}"
    # data.tar paths are ./data/data/com.<id>/files/usr/... -> 6 components.
    # deb_files strips by component count, not by a hardcoded prefix string.
    : "${PKG_DEB_STRIP:=6}"
}
