#!/system/bin/sh
# install-plugin.sh - Instala y configura Craft Anything + Ghidra ARM64 (v0.4.0-r8) activo por defecto en Android.
set -eu

SRC_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
case "$SRC_DIR" in
    */.gemini/*) HOME="${SRC_DIR%%/.gemini/*}" ;;
    *) HOME="${HOME:-/data/user/0/com.agy/files}" ;;
esac
PREFIX="${PREFIX:-$HOME/usr}"
CONFIG_DIR="$HOME/.gemini/config"
TARGET_DIR="$CONFIG_DIR/plugins/craft-anything"

mkdir -p "$CONFIG_DIR/plugins"

if [ "$SRC_DIR" != "$TARGET_DIR" ]; then
    rm -rf "$TARGET_DIR"
    mkdir -p "$TARGET_DIR"
    cp -Rf "$SRC_DIR"/. "$TARGET_DIR"/
fi

chmod 0755 \
    "$TARGET_DIR/install-plugin.sh" \
    "$TARGET_DIR/mcp/bin/start-mcp.sh" \
    "$TARGET_DIR/mcp/bin/craft-anything-cli" \
    "$TARGET_DIR/mcp/bin/craft-ghidra-setup" \
    "$TARGET_DIR/mcp/bin/ghidra-arm64/decompile" \
    "$TARGET_DIR/mcp/bin/ghidra-arm64/sleigh"

mkdir -p "$PREFIX/share/ghidra-arm64/os/linux_arm_64"
cp -f "$TARGET_DIR/mcp/bin/ghidra-arm64/decompile" "$PREFIX/share/ghidra-arm64/os/linux_arm_64/decompile"
cp -f "$TARGET_DIR/mcp/bin/ghidra-arm64/sleigh" "$PREFIX/share/ghidra-arm64/os/linux_arm_64/sleigh"
chmod 0755 "$PREFIX/share/ghidra-arm64/os/linux_arm_64/decompile" "$PREFIX/share/ghidra-arm64/os/linux_arm_64/sleigh"

mkdir -p "$PREFIX/tmp/run" "$PREFIX/tmp/ghidra-temp" "$PREFIX/tmp/ghidra-cache" "$PREFIX/tmp/ghidra-settings"
chmod 0700 "$PREFIX/tmp/run" 2>/dev/null || true

mkdir -p "$HOME/.config/craft-anything"
if [ ! -f "$HOME/.config/craft-anything/config.toml" ]; then
    cat > "$HOME/.config/craft-anything/config.toml" <<'TOML'
deep_mode = true
first_run_done = true
TOML
fi

# Evitar binarios duplicados en $PREFIX/bin y crear wrappers limpios en $HOME/runtime/bin
rm -f "$PREFIX/bin/craft-anything-cli" "$PREFIX/bin/craft-ghidra-setup" 2>/dev/null || true
mkdir -p "$HOME/runtime/bin" 2>/dev/null || true
for tool in craft-anything-cli craft-ghidra-setup; do
    rm -f "$HOME/runtime/bin/$tool" 2>/dev/null || true
    cat > "$HOME/runtime/bin/$tool" <<EOF
#!/system/bin/sh
export HOME="$HOME"
export PREFIX="$PREFIX"
export XDG_CONFIG_HOME="$HOME/.config"
exec "$TARGET_DIR/mcp/bin/$tool" "\$@"
EOF
    chmod 0755 "$HOME/runtime/bin/$tool"
done

# Si Ghidra 12.1.4 ya estaba extraido, actualizar tambien los wrappers de analyzeHeadless
GHIDRA_ROOT="$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC"
if [ -f "$GHIDRA_ROOT/support/analyzeHeadless" ]; then
    mkdir -p "$PREFIX/bin" 2>/dev/null || true
    for dst in "$PREFIX/bin/analyzeHeadless" "$HOME/runtime/bin/analyzeHeadless"; do
        rm -f "$dst" 2>/dev/null || true
        cat > "$dst" <<EOF
#!/system/bin/sh
export HOME="$HOME"
export PREFIX="$PREFIX"
export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export LD_LIBRARY_PATH="$PREFIX/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export PATH="$PREFIX/bin:$HOME/runtime/bin:\$JAVA_HOME/bin:/system/bin"
export TMPDIR="\${TMPDIR:-$PREFIX/tmp}"
export JDK_JAVA_OPTIONS="-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp"
mkdir -p "\$TMPDIR/ghidra-temp" "\$TMPDIR/ghidra-cache" "\$TMPDIR/ghidra-settings"
exec "$PREFIX/bin/bash" "$GHIDRA_ROOT/support/analyzeHeadless" "\$@"
EOF
        chmod 0755 "$dst"
    done
fi

echo "============================================================"
echo " Plugin Craft Anything + Ghidra ARM64 v0.4.0-r8 activo"
echo " Ruta: $TARGET_DIR"
echo "============================================================"
