#!/system/bin/sh
# start-mcp.sh - Ultra-fast silent stdio launcher for Craft Anything + Ghidra ARM64 inside Antigravity Android.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PLUGIN_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"

if [ -z "${HOME:-}" ]; then
    case "$PLUGIN_DIR" in
        */.gemini/*) HOME="${PLUGIN_DIR%%/.gemini/*}" ;;
        *) HOME="/data/user/0/com.agy/files" ;;
    esac
fi
PREFIX="${PREFIX:-$HOME/usr}"

# Only inject into Ghidra 12.1.4 if it was freshly unpacked and missing decompile
GHIDRA_ROOT="$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC"
GHIDRA_NATIVE="$GHIDRA_ROOT/Ghidra/Features/Decompiler/os/linux_arm_64"
if [ -d "$GHIDRA_ROOT" ] && [ ! -f "$GHIDRA_NATIVE/decompile" ]; then
    {
        mkdir -p "$GHIDRA_NATIVE" 2>/dev/null || true
        for bin in decompile sleigh; do
            if [ -f "$SCRIPT_DIR/ghidra-arm64/$bin" ]; then
                cp -f "$SCRIPT_DIR/ghidra-arm64/$bin" "$GHIDRA_NATIVE/$bin" 2>/dev/null || true
                chmod 0755 "$GHIDRA_NATIVE/$bin" 2>/dev/null || true
            fi
        done
    } >/dev/null 2>&1
fi

export HOME PREFIX
export TMPDIR="$PREFIX/tmp"
export XDG_RUNTIME_DIR="$PREFIX/tmp/run"
export XDG_CONFIG_HOME="$HOME/.config"
export JDK_JAVA_OPTIONS="-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp"

if [ -d "$PREFIX/lib/jvm/java-21-openjdk" ]; then
    export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
fi
if [ -d "$GHIDRA_ROOT" ]; then
    export GHIDRA_INSTALL_DIR="$GHIDRA_ROOT"
fi

EXTRA_JAVA="${JAVA_HOME:+$JAVA_HOME/bin:}"
# Do not include $SCRIPT_DIR in PATH so craft-anything-cli is only found once via $HOME/runtime/bin
export PATH="$PREFIX/bin:${EXTRA_JAVA}$HOME/runtime/bin:/system/bin"

exec "$SCRIPT_DIR/craft-anything-cli" --deep mcp --all-tools
