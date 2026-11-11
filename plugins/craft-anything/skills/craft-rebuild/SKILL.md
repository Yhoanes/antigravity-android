---
name: craft-rebuild
description: Study, port, and rebuild applications, games, Electron bundles, websites, or native tools into a clean modern codebase (including Android ARM64, Web, React/Vite, Rust, or Kotlin/Compose) using Craft Anything's structured Study -> Brief -> Build -> Check workflow in Deep or Clean-Room mode.
---

# Craft Rebuild: Structured Software Porting & Reconstruction

Use this skill when the user wants to **study an existing app, game, bundle, or website and rebuild or port it** into a clean, maintainable project (for example porting a desktop tool or game to Android/Web, replacing a heavy Electron `.asar` app with a lightweight web/mobile UI, or doing a Clean-Room reimplementation).

## 1. Choose the Mode: Deep vs. Clean-Room

- **Deep Mode (default in this plugin):** Inspects package structure, symbols, strings, JS bundles (`js_app_map`), data schemas, asset catalogues, and pseudo-C / Ghidra decompilation to understand exact file formats, algorithms, state machines, and UI flows before writing new code.
- **Clean-Room Mode (when requested by the user):** Call `settings_set` (`deep_mode: false`). Never open, read, list, or decompile internal files of the target; build strictly from user specifications, external behavior/screenshots, and documented public interfaces.

## 2. Four-Phase Rebuild Workflow

### Phase 1 — Study (Read-Only Evidence Gathering)
1. Identify the target (`path` on device such as `/sdcard/Download/...` or workspace folder, or `url`).
2. Run a broad overview first:
   - Packaged binary / APK / folder: `core_target_open` and `core_target_triage`.
   - Electron or packaged JS app (`resources/app.asar` or `package.json`): run `js_app_map` (`limit: 50`) instead of wasting time decompiling the Chromium/Node runtime binary!
   - Game directory: identify the engine (Unity, Godot, Unreal, GameMaker, RPG Maker, SDL/custom) and inventory scenes, configs, shaders, audio, and data tables.
   - Native core libraries (`.so` / `.exe` / `.dll`): inspect exported symbols, JNI bridges, data structures, and key functions with built-in pseudo-C or Ghidra (`ghidra-arm64` skill).
3. Record open questions in `core_unknown_add` and settled architectural facts in `core_finding_record`.

### Phase 2 — Architecture Brief & Checklist
1. Honor the user's chosen target stack first (e.g., Kotlin + Jetpack Compose, NDK + SDL3, Rust, React/Vite, Single-file HTML). If unspecified, propose the cleanest stack for their target platform.
2. Create a concise 3–7 step implementation checklist covering:
   - Core data models, formats, and state management
   - Engine / business logic / protocol or file parsers
   - Responsive UI, touch controls (when targeting Android), and assets (using clean neutral placeholders unless the user owns the original assets)
   - Build & verification pass
3. Ask the user only high-impact product scope questions (2–4 clear choices) if part of the original scope is ambiguous.

### Phase 3 — Clean Implementation
1. Write all new source code inside a dedicated output directory (e.g., `app/` or the user's target project folder), keeping any reference target strictly read-only.
2. Reimplement layout, styling, and logic cleanly in idiomatic target-language code rather than pasting raw decompiler output.
3. Avoid leaving long-running dev servers blocking turns unless explicitly requested as a daemon preview.

### Phase 4 — Verification & Diff Summary
1. Compile/typecheck/test the new build completely and fix build errors before finishing (`rebuild_check` when working inside a Craft Anything workspace, or the target stack's native build/test suite).
2. Review `core_unknowns_show` so every remaining estimate or unverified edge case is explicitly documented.
3. Summarize in plain Spanish/English what was built, how it maps to the original architecture, any intentional differences or remaining unknowns, and the next recommended step.
