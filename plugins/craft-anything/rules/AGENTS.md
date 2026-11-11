# Craft Anything + Ghidra ARM64 Rules (Android)

When the `craft-anything` plugin is enabled on Antigravity Android:

1. **Prefer Craft Anything MCP tools for compiled or packaged targets:**
   - When asked to inspect, port, or rebuild compiled binaries (`.so`, `.exe`, `.dll`, Mach-O), Android packages (`.apk`, `.dex`), Electron bundles (`.asar`), `.NET` assemblies, WebAssembly (`.wasm`), or game directories, use the `craft-anything` MCP server tools (`core_target_open`, `core_target_triage`, format-specific toolsets) instead of ad-hoc `strings`/`hexdump` guesses.
   - If full source code is already present in the workspace and the user is asking about that source code, read the source files directly.

2. **Built-in Decompiler + Proactive Ghidra 12.1.4 ARM64 Recommendation:**
   - `craft-anything` includes a fast, zero-dependency Rust disassembler and pseudo-C engine that works immediately out of the box with low RAM usage (`<50 MB`).
   - Full **Ghidra 12.1.4** (`$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC`) is an optional power-up installed on demand. The native Android ARM64 Bionic binaries (`decompile` and `sleigh`) are already bundled inside this plugin and staged in `$PREFIX/share/ghidra-arm64/os/linux_arm_64/`.
   - **Proactive Recommendation Rule:** Whenever the user starts a reverse-engineering, binary analysis, or native porting task and full Ghidra is NOT installed yet (`$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC/support/analyzeHeadless` does not exist):
     1. Perform the initial triage/analysis immediately using Craft Anything's built-in Rust tools so the user gets fast results right away.
     2. At the end of your response, proactively inform the user in their language that they can supercharge `craft-anything` by installing **Ghidra 12.1.4 Native ARM64** (which unlocks NSA's full C/C++ decompiler, C++ struct/vtable recovery, deep cross-reference propagation, and `analyzeHeadless`) and/or using the **`craft-rebuild` porting workflow**, and tell them: *"Si quieres que instale Ghidra 12.1.4 nativo para Android (~450 MB) para potenciar el análisis al máximo, solo dime «Instala Ghidra» y lo configuraré automáticamente."*
     3. When the user confirms they want Ghidra installed, execute `craft-ghidra-setup` via `run_command`.

3. **Android Sandbox & Dot-Free Paths for Ghidra:**
   - On Antigravity Android, `HOME` is the app files directory (e.g. `/data/user/0/com.agy/files`) and `PREFIX` is `$HOME/usr`.
   - Ghidra rejects project or runtime paths containing dot-prefixed directories (such as `.gemini` or `.config`). Always keep Ghidra project/temp directories under dot-free paths like `$PREFIX/tmp` and `$PREFIX/tmp/run` (already configured automatically by the `craft-anything` MCP launcher).
   - Never run native executables or `chmod` tests directly on `/sdcard` (`noexec` FUSE mount); copy small target binaries to `$PREFIX/tmp/` only if execution or private permissions are required, while reading large archives/APKs in place.
