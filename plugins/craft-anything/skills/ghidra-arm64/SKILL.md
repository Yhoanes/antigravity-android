---
name: ghidra-arm64
description: Install on demand, configure, and run native Android ARM64 Ghidra 12.1.4 (decompile, sleigh, analyzeHeadless, and Craft Anything Deep Ghidra integration) inside Antigravity Android while respecting mobile RAM and storage constraints.
---

# Native Android ARM64 Ghidra 12.1.4 Guide

This plugin bundles precompiled **Android ARM64 Bionic (`aarch64-linux-android`)** native binaries for Ghidra 12.1.4:
- `decompile` (`3.6 MB`, PIE, stripped)
- `sleigh` (`1.5 MB`, PIE, stripped)
- `craft-ghidra-setup` (automated installer for OpenJDK 21 + Ghidra 12.1.4 Public distribution)

## 1. When You Need Full Ghidra vs. Built-In Decompiler

- **Use Craft Anything's built-in Rust disassembler/pseudo-C first** for quick triage, symbol/string searches, imports/exports, control flow, and small-to-medium functions. It runs instantaneously in <50 MB RAM without Java.
- **Use full Ghidra 12.1.4 ARM64** when you need deep cross-reference propagation, complex C++/vtable type recovery, processor specs via SLEIGH, or batch `analyzeHeadless` scripts.

## 2. Checking & Installing Ghidra 12.1.4 On Demand

1. Check whether Ghidra is already installed on the device:
   ```sh
   ls -la "$HOME/usr/opt/ghidra/ghidra_12.1.4_PUBLIC/support/analyzeHeadless"
   ```
2. If not installed yet:
   - Inform the user that installing OpenJDK 21 + Ghidra 12.1.4 requires **~450 MB download (~900 MB extracted)** and check free disk space first with `df -h "$HOME"`.
   - Once confirmed, run:
     ```sh
     craft-ghidra-setup
     ```
   - What `craft-ghidra-setup` does automatically:
     1. Installs `openjdk-21` via Antigravity Android's `pkg` if missing.
     2. Downloads `ghidra_12.1.4_PUBLIC_20260921.zip`, verifies its SHA-256 (`ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db`), extracts to `$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC`, and **deletes the `.zip` immediately** to save phone storage.
     3. Injects this plugin's native ARM64 `decompile` and `sleigh` binaries into `Ghidra/Features/Decompiler/os/linux_arm_64/`.
     4. Rewrites `/usr/bin/env bash` shebangs to `$PREFIX/bin/bash`, configures `JAVA_HOME_OVERRIDE`, and exposes `analyzeHeadless` in `$HOME/runtime/bin/analyzeHeadless` and `$PREFIX/bin/analyzeHeadless`.

## 3. Running Ghidra Safely on Mobile Devices

1. **Dot-Free Project Directories (Critical):**
   - Ghidra refuses any project or temp directory path containing a component starting with `.` (such as `.gemini` or `.config`).
   - Always place manual `analyzeHeadless` projects under `$HOME/usr/tmp/ghidra-projects` (never inside `~/.gemini`):
     ```sh
     mkdir -p "$HOME/usr/tmp/ghidra-projects"
     GHIDRA_HEADLESS_MAXMEM="2G" analyzeHeadless "$HOME/usr/tmp/ghidra-projects" MyProj -import /path/to/libtarget.so
     ```
2. **Mobile RAM Discipline (`GHIDRA_HEADLESS_MAXMEM`):**
   - On 6–8 GB RAM phones, cap Java heap with `GHIDRA_HEADLESS_MAXMEM="1536M"` or `"2G"` so Android's Low Memory Killer (`lmkd`) does not terminate Antigravity Android in the background.
   - Avoid analyzing giant 100+ MB Chromium/Unreal engine binaries indiscriminately; target the specific `.so` / `.dll` module that implements the application's own logic, or pass `-noanalysis` first when only listing symbols/imports.
3. **Automatic Craft Anything Integration:**
   - Once `craft-ghidra-setup` finishes, the `craft-anything` MCP server automatically detects `$PREFIX/opt/ghidra/ghidra_12.1.4_PUBLIC` and uses it transparently for Deep decompilation tools without restarting the phone!
