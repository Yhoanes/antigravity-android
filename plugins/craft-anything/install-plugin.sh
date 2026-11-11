#!/system/bin/sh
# install-plugin.sh - Instala y registra el plugin Craft Anything + Ghidra ARM64 en Antigravity Android.
set -eu

SRC_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
HOME="${HOME:-/data/user/0/com.agy/files}"
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

rm -f "$PREFIX/bin/craft-anything-cli" "$PREFIX/bin/craft-ghidra-setup" 2>/dev/null || true
if [ -d "$HOME/runtime/bin" ]; then
    ln -sf "$TARGET_DIR/mcp/bin/craft-anything-cli" "$HOME/runtime/bin/craft-anything-cli"
    ln -sf "$TARGET_DIR/mcp/bin/craft-ghidra-setup" "$HOME/runtime/bin/craft-ghidra-setup"
fi

mkdir -p "$HOME/.config/craft-anything"
if [ ! -f "$HOME/.config/craft-anything/config.toml" ]; then
    cat > "$HOME/.config/craft-anything/config.toml" <<'TOML'
deep_mode = true
first_run_done = true
TOML
fi

CFG_FILE="$CONFIG_DIR/config.json"
if command -v python3 >/dev/null 2>&1; then
    python3 - "$CFG_FILE" <<'PY'
import json, os, sys
p = sys.argv[1]
data = {}
if os.path.isfile(p):
    try:
        with open(p, "r", encoding="utf-8") as f:
            txt = f.read().strip()
            if txt:
                data = json.loads(txt)
    except Exception:
        data = {}
plugins = data.setdefault("plugins", {})
entry = plugins.setdefault("craft-anything", {})
entry.setdefault("enabled", False)
entry.pop("installedFrom", None)
with open(p, "w", encoding="utf-8") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
PY
elif command -v node >/dev/null 2>&1; then
    node -e '
const fs = require("fs");
const p = process.argv[1];
let data = {};
if (fs.existsSync(p)) {
  try { const t = fs.readFileSync(p, "utf8").trim(); if (t) data = JSON.parse(t); } catch (_) {}
}
data.plugins = data.plugins || {};
const prev = data.plugins["craft-anything"] || {};
delete prev.installedFrom;
data.plugins["craft-anything"] = Object.assign({}, prev, {
  enabled: typeof prev.enabled === "boolean" ? prev.enabled : false
});
fs.writeFileSync(p, JSON.stringify(data, null, 2) + "\n");
' "$CFG_FILE"
elif [ ! -s "$CFG_FILE" ]; then
    cat > "$CFG_FILE" <<'JSON'
{
  "plugins": {
    "craft-anything": {
      "enabled": false
    }
  }
}
JSON
fi

echo "============================================================"
echo " Plugin Craft Anything + Ghidra ARM64 instalado con exito"
echo " Ruta: $TARGET_DIR"
echo "============================================================"
