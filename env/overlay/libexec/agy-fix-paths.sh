#!/data/data/com.agy/files/usr/bin/bash
# agy-fix-paths [PATH...] -- rewrite hardcoded Termux/app prefixes to $PREFIX.
#
# Replaces every occurrence of /data/data/com.<id>/files/usr in text files with
# $PREFIX. Default target is the whole prefix. Binary files (NUL byte), ELF
# objects and symlinks are skipped. Idempotent: files already using $PREFIX are
# left byte-identical. Prints the number of files rewritten.
#
# Run it after installers that bake Termux interpreter paths into their output:
#   pip install <pkg>
#   npm install -g <pkg>
#   ./configure && make install
#
# Exits 0 even when nothing matched. Shell paths in $PREFIX are preserved.
set -uo pipefail

: "${PREFIX:=/data/data/com.agy/files/usr}"
PATTERN='/data/(data|user/[0-9]+)/com\.[^/]*/files/usr'

if [ "$#" -gt 0 ]; then
    targets=("$@")
else
    targets=("$PREFIX")
fi

esc="$(printf '%s' "$PREFIX" | sed 's/[\\&#]/\\&/g')"
count=0

while IFS= read -r -d '' f; do
    [ -n "$f" ] || continue
    tmp="$f.agyfix.$$"
    if sed -E "s#$PATTERN#$esc#g" "$f" > "$tmp" 2>/dev/null; then
        if cmp -s "$f" "$tmp"; then
            rm -f "$tmp"
        else
            # Preserve mode, then rename over the original. Rename is atomic and
            # leaves a running copy of this script reading the old inode, so the
            # pass can safely rewrite itself.
            mode="$(stat -c '%a' "$f" 2>/dev/null)" || mode=""
            [ -n "$mode" ] && chmod "$mode" "$tmp" 2>/dev/null
            mv -f "$tmp" "$f"
            count=$((count + 1))
        fi
    else
        rm -f "$tmp"
    fi
done < <(grep -rIlZ --exclude-dir=ghidra_12.1.4_PUBLIC -E "$PATTERN" -- "${targets[@]}" 2>/dev/null)

# Repoint any absolute symlinks pointing into a foreign Termux/app prefix.
while IFS= read -r -d '' link; do
    [ -n "$link" ] || continue
    target="$(readlink "$link" 2>/dev/null || true)"
    case "$target" in
        /data/[d]ata/com.*/files/usr*|/data/[u]ser/*/com.*/files/usr*)
            suffix="$(printf '%s' "$target" | sed -E 's#^/data/(data|user/[0-9]+)/com\.[^/]*/files/usr##')"
            if ln -snf "$PREFIX$suffix" "$link" 2>/dev/null; then
                count=$((count + 1))
            fi
            ;;
    esac
done < <(find "${targets[@]}" -not -path '*/ghidra_12.1.4_PUBLIC/*' -type l -print0 2>/dev/null)

# Sync runtime/bin bridges so newly installed console scripts (pip/npm/make)
# in $PREFIX/bin are immediately visible on language_server's PATH.
if [ -f "$PREFIX/libexec/pkg/lib/util.sh" ]; then
    . "$PREFIX/libexec/pkg/lib/util.sh"
    sync_runtime_bridges
fi

printf 'agy-fix-paths: rewrote %d file(s) -> %s\n' "$count" "$PREFIX"

