---
name: craft-anything
description: Investigate how shipped software works (executables, shared libraries, APKs, DEX, Electron .asar bundles, .NET assemblies, WebAssembly, game packages) with evidence-backed answers using the Craft Anything MCP tools, maintaining a durable brief, plan, hypotheses, findings, and handoff.
---

# Craft Anything: Evidence-First Software Investigation

Use this skill when an answer depends on **built or packaged software**: an ELF/PE/Mach-O binary or `.so`/`.dll`, an Android APK/DEX, an Electron `.asar` or JS bundle, a `.NET` assembly, a WebAssembly module, or a packaged desktop/mobile/game build. If full source code is already in the workspace and the question is about that source, read the source directly.

## 1. Toolset & Mode Readiness

- The `craft-anything` MCP server in this plugin starts in Deep mode with `--all-tools` (`craft-anything-cli --deep mcp --all-tools`), exposing all 302 static inspection, format parser, and rebuild tools immediately.
- Trust live MCP tool schemas first. Never run `craft-anything-cli setup --yes` on Android, as it overwrites custom plugin MCP arguments; if diagnostics are ever needed, run `craft-anything-cli capabilities` or `craft-anything-cli doctor`.
- If the user explicitly asks for a **Clean-Room** workflow (where internal code/bytes of the target must not be read), toggle `settings_set` (`deep_mode: false`) before opening any target.

## 2. Durable Working Memory Workflow

Long binary and architecture investigations outlive a single context window. Keep Craft Anything's structured ledger updated throughout the task:

1. **Resume or inspect state:** Call `core_investigation_show` and `core_session_show` first. If a handoff exists, continue from it rather than starting from scratch.
2. **Define the brief & plan:** For a new investigation, call `core_brief_write` (question, context, target paths, constraints such as static-only inspection, and definition of done) and `core_plan_update` (concise numbered steps with `todo` / `doing` / `done` / `blocked`).
3. **Track hypotheses & unknowns:**
   - Before running deeper probes to test an architectural assumption, record it with `core_hypothesis_record` including what observation would confirm or refute it.
   - Update with `core_hypothesis_update` (`supported`, `refuted`, `abandoned`) citing evidence IDs (`ev_...`).
   - Log unresolved gaps explicitly via `core_unknown_add` rather than guessing.
4. **Record settled findings:** Call `core_finding_record` with a clear factual statement and at least one `ev_...` evidence ID. Build final reports from recorded findings.
5. **Write handoffs:** At the end of multi-step investigations, call `core_handoff_write` summarizing current state, next steps, and dead ends to avoid.

## 3. Discipline & Pagination

- **Separate Observation, Derivation, and Inference:** Label whether a claim comes directly from file headers/bytes (observed), control-flow/call-graph analysis (derived), or naming/architectural interpretation (inferred).
- **Cite Evidence IDs (`ev_...`):** Every settled claim should cite its evidence ID.
- **Summary First, Then Page:** Large outputs return a summary plus an evidence ID. Request specific pages (`offset` / `limit`) or inspect `evidence://<id>` selectively so mobile context windows stay lean.
- **Entry Points by Target Format:**
  - **ELF / PE / Mach-O / `.so` / `.dll`:** `core_target_open` -> `core_target_triage` -> native symbol, string, section, disassembly, and function decompilation tools.
  - **Android APK / DEX:** `core_target_open` -> Android manifest, DEX class/method, and native JNI `.so` tools.
  - **Electron / `.asar` / JS Bundles:** `core_target_open` -> `js_app_map` and JavaScript bundle inspection tools.
  - **.NET / Managed Assemblies:** `core_target_open` -> managed metadata and type/method inspection tools.
  - **WebAssembly / Go / Game Archives:** `core_target_open` -> format-specific parsers and asset inventory tools.

## 4. Safety & Boundaries

- Static inspection never executes target binaries. Never claim runtime behavior as observed fact from static inspection alone.
- Keep investigation notes focused on software architecture, interoperability, debugging, and clean engineering; never store credentials, tokens, or personal data in findings or handoffs.
