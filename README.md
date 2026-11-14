<div align="center">

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity for Android Logo" width="96" height="96" style="border-radius: 22px; margin-bottom: 12px;" />

  <h1 align="center">Antigravity for Android</h1>

  <p><font color="#7d8590">Standalone <b>Google Antigravity</b> autonomous coding IDE &amp; native <b>ARM64 Bionic</b> reverse-engineering APK for Android — engineered by <a href="https://github.com/Yhoanes"><b>Yhoanes</b></a> (zero PRoot/Termux virtualization overhead).</font></p>

  <p>
    <code>v2.32.0</code> &nbsp;&bull;&nbsp;
    <code>Android 7.0+</code> &nbsp;&bull;&nbsp;
    <code>ARM64 v8.0-A+</code> &nbsp;&bull;&nbsp;
    <code>Native Rust agy-box Engine</code> &nbsp;&bull;&nbsp;
    <code>Smart Android Marketplace</code> &nbsp;&bull;&nbsp;
    <code>AGY Video Studio (GPU)</code> &nbsp;&bull;&nbsp;
    <code>Craft Anything + Ghidra 12.1.4</code> &nbsp;&bull;&nbsp;
    <code>Live Voice Mode</code> &nbsp;&bull;&nbsp;
    <code>In-App OTA Updater</code> &nbsp;&bull;&nbsp;
    <code>BY-NC / Apache 2.0</code>
  </p>

  <p>
    <a href="https://yhoanes.github.io/antigravity-android/"><b>Official Website &amp; Benchmarks</b></a> &nbsp;&bull;&nbsp;
    <a href="https://github.com/Yhoanes/antigravity-android/releases/latest"><b>Download Signed APK (v2.32.0)</b></a> &nbsp;&bull;&nbsp;
    <a href="https://github.com/Yhoanes/antigravity-android-plugins"><b>ARM64 Plugin Marketplace</b></a>
  </p>

  <br/>

  <!-- Hero Visual -->
  <img alt="Antigravity for Android v2.32.0 Standalone Autonomous Agent IDE Interface" src="docs/assets/hero_home.jpg" width="100%" style="border-radius: 14px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);" />
  <p align="center"><font size="2" color="#7d8590">Antigravity v2.32.0 autonomous agent workspace on Android with Orchestrator (Carbon/Argon), Gemini 4 (Carbon/Argon/Hydrogen) &amp; Live Voice Mode</font></p>

</div>

---

## Architectural Overview

**Antigravity for Android (`v2.32.0`)** by **[Yhoanes](https://github.com/Yhoanes)** brings Google's autonomous agentic coding engine (`Google Antigravity` IDE & CLI) and a full native binary reverse-engineering suite directly to Android phones and tablets as a standalone APK.

Unlike terminal-based chat wrappers or virtualized Linux containers (`PRoot`, `QEMU`, `chroot`, `Termux proot-distro`), Antigravity executes **natively inside Android userspace (Bionic)** within the application process. Powered by next-generation models (**Gemini 4 Carbon**, **Gemini 4 Argon**, **Gemini 4 Hydrogen**, **Orchestrator (Carbon)**, **Orchestrator (Argon)**, **Gemini 3.8 Flash**, **Claude Opus 5.5**, **Claude Sonnet 5.5**, and **GPT-OSS 120B**), the agent autonomously inspects codebases, decompiles multi-architecture binaries, edits project trees, executes build tools, and validates results directly on physical mobile hardware.

> [!NOTE]
> **Bare-Metal Execution with Transparent Compatibility:** Processes run directly on the host CPU via a hybrid Bionic/Glibc loader without virtualization penalties, taking full advantage of UFS storage throughput and ARMv8.1-A+ Large System Extension (LSE) atomics (with transparent userspace `SIGILL` emulation for ARMv8.0-A CPUs via `liblse_compat.so`).

---

## Key Capabilities

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Autonomous Multi-Model Agent</b></font></p>
      <p><font color="#7d8590">End-to-end engineering with <b>Gemini 4 Carbon/Argon</b>, <b>Claude 5.5</b>, and parallel subagents (<code>self</code>, <code>research</code>): task planning, multi-file refactoring, compilation, and automated diagnosis.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Smart Android Marketplace &amp; Video Studio</b></font></p>
      <p><font color="#7d8590">Live <b>Built for Android</b> OTA shelf featuring <b>AGY Video Studio</b> (<code>h264_mediacodec</code> GPU rendering, neural TTS &amp; CapCut drafts), <b>GitHub MCP</b> (91 tools), headless Chromium MCP, and Bionic Go DNS bridging (<code>libagy-go-dns.so</code>).</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Craft Anything + Ghidra 12.1.4 ARM64</b></font></p>
      <p><font color="#7d8590">1-tap auto-enabled reverse-engineering suite with <b>302 MCP tools</b> in Rust plus on-demand native Android ARM64 <b>Ghidra 12.1.4</b> (<code>decompile</code> &amp; <code>sleigh</code>) for ELF, PE, Mach-O, APK/DEX, .NET, and WASM analysis.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Native Rust <code>agy-box</code> Runtime Engine</b></font></p>
      <p><font color="#7d8590">Single-binary Bionic ARM64 Rust accelerator: 4-thread parallel <code>pkg</code> (pure-Rust TLS + <code>.tar.xz/.zst</code>), zero-Python <code>agy-daemon</code> (<code>41 ms</code> <code>inotify</code>, <code>900 KB</code> heap), <code>112x</code> faster <code>agy-fix-paths</code>, Shizuku-free <code>agy-notify</code> (<code>&lt;55 ms</code>), and <code>398</code> zero-shell direct tool symlinks.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Real-Time Voice &amp; Native Encoders</b></font></p>
      <p><font color="#7d8590">Bidirectional WebRTC/WebAudio voice sessions with live transcription, paired with native Bionic ARM64 <code>webp_encoder</code> and <code>webm_encoder</code> binaries for zero-dependency media processing.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>In-App OTA Updater &amp; Shizuku (Rish)</b></font></p>
      <p><font color="#7d8590">Native <code>window.electronUpdater</code> bridge (<b>Check for Updates</b> &amp; 1-tap <b>Restart to Update</b> from GitHub Releases) with silent Shizuku <code>pm install -r -d</code> + auto-relaunch or standard Android PackageInstaller fallback.</font></p>
    </td>
  </tr>
</table>

---

## Visual Feature Walkthrough

### 1. Next-Generation Models & Touch Reasoning Controls (`v2.32.0`)

Select between **Gemini 4 Carbon**, **Gemini 4 Argon**, **Gemini 4 Hydrogen**, **Orchestrator (Carbon)**, **Orchestrator (Argon)**, **Gemini 3.8 Flash**, **Claude Opus 5.5**, **Claude Sonnet 5.5**, and **GPT-OSS 120B**. Touch-engineered submenu patches allow effortless selection of reasoning effort levels (`Low`, `Medium`, `High`) on mobile and tablet screens without requiring desktop hover events.

<br/>

<img alt="Model Selector and Reasoning Effort Submenu" src="docs/assets/model_selector.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">v2.32.0 model selector with Gemini 4 (Carbon/Argon/Hydrogen), Orchestrator (Carbon/Argon), Claude 5.5, and touch-activated reasoning effort submenu</font></p>

<br/>

---

### 2. Native ARM64 Reverse Engineering: `Craft Anything` + `Ghidra 12.1.4`

Available in 1 tap from the **`Built for Android`** shelf in **Customizations &rarr; Marketplace** (auto-enabled upon installation), **`Craft Anything + Ghidra` (`v0.4.0-r8`)** combines two complementary reverse-engineering engines natively on ARM64:

* **Tier 1 — Instant Rust Binary & App Engine (`<50 MB` RAM, 0 Downloads):** Exposes **302 MCP tools** (`craft-anything-cli --deep mcp --all-tools`, compiled for `aarch64-linux-android` with 16 KB page alignment) for static binary triage (`ELF`, `PE`, `Mach-O`, `APK/DEX`, `.NET`, `WASM`, `Electron/JS`), function disassembly, fast pseudo-C decompilation, string/symbol indexing, game asset inspection, and structured clean-room rebuilding.
* **Tier 2 — On-Demand Native ARM64 Ghidra 12.1.4 (`craft-ghidra-setup`):** Pre-staged native Android Bionic ARM64 `decompile` and `sleigh` binaries paired with an automated 1-step installer (`craft-ghidra-setup`) that fetches `openjdk-21`, configures Java TLS `cacerts`, downloads & verifies **Ghidra 12.1.4 PUBLIC** (`SHA-256`), injects the ARM64 decompiler, configures SELinux-compatible dot-free temp directories (`$PREFIX/tmp/ghidra-*`), and exposes `analyzeHeadless` across the runtime.

<br/>

<img alt="Craft Anything + Ghidra 12.1.4 ARM64 Plugin Overview" src="docs/assets/craft_anything_overview.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Craft Anything + Ghidra 12.1.4 ARM64 plugin overview and two-tier architecture</font></p>

<br/>

<img alt="Craft Anything 302 MCP Tools Enabled" src="docs/assets/craft_anything_mcp_tools.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">302 native MCP tools enabled for binary reverse engineering, call-graph recovery, and clean-room porting</font></p>

<br/>

<img alt="Live Reverse Engineering and Decompilation with Voice Readout" src="docs/assets/reverse_engineering_voice_demo.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Real-world test: Craft Anything + Ghidra 12.1.4 ARM64 decompiling 32-bit Windows PE binaries (Warcraft III) directly on Android while narrating findings via Live Voice Chat</font></p>

<br/>

---

### 3. Real-Time Bidirectional Voice Chat (`v2.32.0`)

Speak directly with the autonomous agent in real time. Powered by native WebRTC/WebAudio microphone bridging (`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`) and unlocked `v2.32.0` voice relay feature gates (`cascade-enable-voice`, `send_voice_button_variant`).

<br/>

<img alt="Antigravity Live Voice Chat" src="docs/assets/voice_chat_live.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Active bidirectional voice session with floating live transcript and audio controls</font></p>

<br/>

---

### 4. Multi-Agent Workspace & Parallel Subagents

Designed for autonomous multi-agent workflows: project sidebar, parallel subagent orchestration (`self`, `research`), live task status tracking, and integrated workspace overview (`Review`, `Files`, `Terminal`, `Extensions`).

<br/>

<img alt="Antigravity Multi-Agent Workspace" src="docs/assets/workspace_subagents.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Parallel subagent spawning and live status tracking inside the v2.32.0 workspace</font></p>

<br/>

---

### 5. Customizations & Smart Android Marketplace Fusion

Browse and install both **native Android ARM64 plugins** and **official Antigravity plugins** directly from **Customizations &rarr; Marketplace**:
* **`Built for Android` Shelf (Shelf #1):** Features 1-tap auto-enabled native ARM64 plugins hosted live via [`antigravity-android-plugins`](https://github.com/Yhoanes/antigravity-android-plugins) — including **`AGY Video Studio` (`v2.1.3`)** (GPU `h264_mediacodec` video studio, neural TTS, CapCut drafts & Shizuku), **`Craft Anything + Ghidra` (`v0.4.0-r8`, 302 MCP tools)**, **`GitHub` (`v2.1.1-android`, 91 MCP tools + Bionic OAuth bridge)**, **`Chrome DevTools` (`v1.10.4-android`)** (headless ARM64 Chromium MCP), and **`Android CLI` (`v1.0.16625373-android`)** (on-device Shizuku `rish` workflows).
* **Official Shelves:** **Google Workspace** (Docs, Sheets, Slides, Drive, Calendar, Gmail), **Build with Google** (Gemini API, Firebase, Science, Google Antigravity SDK), and partner integrations, automatically normalized for Bionic ARM64 (`libagy-go-dns.so` DNS fix & sidecar cleanup).

<br/>

<img alt="Customizations and Smart Android Marketplace" src="docs/assets/customizations_marketplace.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Smart Android Marketplace Fusion showing the live <b>Built for Android</b> shelf (AGY Video Studio, Craft Anything + Ghidra, GitHub MCP, Chrome DevTools, Android CLI) directly above Google Workspace</font></p>

<br/>

---

### 6. Execution Policies, Interface Modes & Settings (`v2.32.0`)

Configure agent autonomy and workspace layout from **Settings**:
* **Interface Layout:** Switch between **Knowledge Work** (streamlined research/writing view) and **Software Engineering** (full IDE, terminal, and multi-agent controls).
* **Execution & Permissions:** Configure **Request Review** vs **Turbo** auto-execution, plan review policies, queued message delivery, and **Desktop Mode** viewport switching.

<br/>

<img alt="General Settings and Execution Policies" src="docs/assets/settings_general.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">v2.32.0 General settings panel with Knowledge Work / Software Engineering modes and execution policies</font></p>

<br/>

---

### 7. Native Rust `agy-box` Engine &amp; Production Runtime Optimizations

Every layer of the Android Bionic runtime has been hardened for low latency, minimal RAM footprint, and zero storage bloat:

* **Native ARM64 Bionic Rust Multicall Engine (`agy-box` `v0.2.0`):** Linked solely against Android's `libc.so` and `libdl.so` (`3.52 MiB`), `agy-box` replaces shell/Python bottlenecks while retaining 100% automatic POSIX fallbacks (`libexec/` + `AGY_DISABLE_RUST_BOX=1`):
  * **4-Thread Parallel `pkg` (`rustls` + `.ar`/`.tar.{xz,gz,zst}`):** Exact Debian `dpkg vercmp` dependency resolution, parallel HTTPS downloads, and on-the-fly in-RAM prefix/shebang rewriting during extraction.
  * **Zero-Python `agy-daemon` (`41 ms` `inotify`, `900 KB` Heap):** Event-driven Linux `inotify` file watcher, HTTP webhook server, 5-field cron, GitHub PR/Issue poller, and battery monitor in **`4.32 MiB` RSS** (**~86% less RAM** than Python, working out-of-the-box before Python is installed).
  * **SIMD `agy-fix-paths` (`112x` Faster) &amp; Native `agy-notify` (`<55 ms`):** Multithreaded NEON `memchr` prefix/shebang fixer (`103 ms` vs `11,523 ms`) and Shizuku-free native Android `NotificationManager` IPC queue (`53 ms` `post`/`cancel`/`list` without waiting on `rish`).
  * **Zero-Shell Direct Runtime Bridges (`BRIDGE_FORMAT = 12`):** `398` `runtime/bin/<tool>` symlinks point directly to `agy-box`, injecting `LD_PRELOAD`, `LD_LIBRARY_PATH`, and `SSL_CERT_FILE` in C/Rust and `execve`-ing targets directly without spawning an intermediate `/system/bin/sh` process.
* **HTTP/2 &amp; WebSocket Stream Resume Guard (`agy:host-resume`):** Wraps `StreamAgentStateUpdates` on envelope boundaries (`5-byte` gRPC-Web / Connect framing) and recycles stale background streams via clean status-0 trailer injection upon `MainActivity.onResume` or `visibilitychange`, re-synchronizing completed agent turns in `<1s` after switching apps or returning from OAuth browser redirects without extra background battery drain.
* **Direct Terminal Execution (`exebox` / `urpc.Fork` Bypass):** Binary-level patches in `liblanguage_server.so` (`initEscapeSbox`, `sbox_prep`, `resolveSandbox`, `isSandboxEnabled`, `ApplySettingsToConfig`) disable Linux desktop `CLONE_NEWUSER` / `seccomp` re-exec forks (`libloader.so: invalid argument`), allowing `run_command` to execute directly on Android whether `BypassSandbox` is `false` or `true`.
* **Out-of-the-Box OpenJDK 21 &amp; Ghidra Support:** Pre-bundles the Java TLS truststore (`$PREFIX/etc/tls/certs/java/cacerts`), injects `JDK_JAVA_OPTIONS="-Duser.home=$HOME -Djava.io.tmpdir=$PREFIX/tmp"` across shells and runtime bridges, and uses self-safe glob patterns (`/data/[d]ata/...`) in fallback utilities.

---

### 8. Hardware Throughput & Diagnostics

Real-world test: the agent authored a custom Python I/O benchmark and executed multi-pass tests on physical storage. Sequential reads exceeded **1222 MB/s**, and random writes reached **84,395 IOPS**, proving native UFS throughput without emulation overhead.

| Operation | Block Size | Total Volume | Time (s) | Throughput (MB/s) | IOPS |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Sequential Write (`fsync`)** | 1 MB | 128 MB | 0.158 | 809.04 MB/s | 809 |
| **Sequential Read** | 1 MB | 128 MB | 0.105 | 1222.17 MB/s | 1,222 |
| **Random Write (`4K fsync`)** | 4 KB | 19.5 MB | 0.059 | 329.67 MB/s | 84,395 |
| **Random Read (`4K`)** | 4 KB | 19.5 MB | 0.337 | 58.00 MB/s | 14,848 |

---

## Installation & Getting Started

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>1. Download APK</b></font></p>
      <p><font color="#7d8590">Releases are available under <a href="https://github.com/Yhoanes/antigravity-android/releases">Releases</a>:<br/><br/>
      &bull; <b><code>Antigravity-v2.32.0.apk</code></b> — full signed release with native Rust <code>agy-box</code> engine, bundled Bionic toolchain, Smart Android Marketplace, and in-app OTA updater.<br/><br/>
      <i>Seamless 1-tap in-app OTA updates (<b>Restart to Update</b>) and in-place upgrades are supported across versions.</i></font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>2. Grant Permissions</b></font></p>
      <p><font color="#7d8590">Grant storage access (<code>MANAGE_EXTERNAL_STORAGE</code>) and microphone access for Live Voice Chat.<br/><br/>
      <i>(Optional)</i> Launch <b>Shizuku</b> and grant access for elevated ADB shell operations without root.</font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>3. Bootstrap &amp; Decompile</b></font></p>
      <p><font color="#7d8590">Install development toolchains or enable native Ghidra decompilation:<br/><br/>
      <code>pkg update &amp;&amp; pkg install git python nodejs</code><br/>
      <code>craft-ghidra-setup</code><br/><br/>
      Install <b>AGY Video Studio</b> or <b>Craft Anything + Ghidra</b> with <code>+</code> in <i>Customizations &rarr; Marketplace</i>.</font></p>
    </td>
  </tr>
</table>

---

## System Requirements

<table width="100%" style="border-collapse: separate; border-spacing: 0; border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; overflow: hidden;">
  <thead>
    <tr>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Component</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Minimum Requirements</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Recommended Requirements</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>CPU Architecture</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.0-A+</b> (Cortex-A53, A55, Kryo, Snapdragon 685+)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.2-A / ARMv9</b> (Snapdragon 8 Gen 1+, Dimensity, Tensor)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>RAM</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">4 GB LPDDR4X</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ LPDDR5 / LPDDR5X (for Ghidra 12.1.4 headless analysis)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Free Storage</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">2 GB (runtime core + Craft Anything Rust engine)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ UFS (OpenJDK 21, Ghidra 12.1.4, and toolchains)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Android Version</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 7.0 (API 24)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 12 – 16+</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px;"><b>Privileges</b></td>
      <td style="padding: 12px 16px;">Standard User</td>
      <td style="padding: 12px 16px;">Shizuku (Active ADB Service)</td>
    </tr>
  </tbody>
</table>

---

## Frequently Asked Questions (Google Antigravity on Android)

**How is Antigravity for Android different from running Antigravity CLI inside Termux or PRoot?**  
Unlike `proot-distro` or containerized wrappers that intercept every Linux syscall with heavy I/O overhead, **Antigravity for Android** runs natively inside Android userspace (`Bionic`/`Glibc` hybrid loader + single-binary Rust `agy-box` engine), delivering **1,222 MB/s** sequential reads, **84,395 IOPS** on physical UFS storage, full WebView Workbench UI (`v2.32.0`), Live Voice WebRTC, and 1-tap OTA updates in a standalone APK.

**Does Antigravity for Android require root access?**  
No. It runs out of the box as a standard unrooted user on **Android 7.0+ (`ARM64 v8.0-A+`)** thanks to binary-patched `exebox`/`seccomp` bypasses in `liblanguage_server.so` and `liblse_compat.so` atomics emulation. Optional **Shizuku (`rish`)** integration unlocks silent 1-tap APK upgrades and on-device ADB shell automation without rooting.

**Can it decompile ARM64, Windows PE, and Android APK binaries directly on a phone?**  
Yes. Installing **Craft Anything + Ghidra (`v0.4.0-r8`)** from *Customizations &rarr; Marketplace* activates **302 native ARM64 Rust MCP tools** immediately (`<50 MB` RAM, zero downloads) plus optional 1-command setup (`craft-ghidra-setup`) for native Android Bionic ARM64 **Ghidra 12.1.4** (`decompile` & `sleigh` + `OpenJDK 21`).

---

## Repository Structure

```text
antigravity-android/
├── app/        # Android Host, WebView, OnboardingSeeder, and process supervisors
├── build/      # Gradle-free deterministic build pipeline (aapt, javac, d8, apksigner)
├── bus/        # Modular shell bus, IPC helpers, and environment variable composers
├── config/     # Centralized environment configs (app.env, paths.env, tools.env)
├── core/       # Glibc runtime manifest, seed states, and native media encoders
├── docs/       # Architecture specifications, manuals, and high-res visual assets
├── env/        # Bionic toolchain, native Rust agy-box engine (env/rust/agy-box), TLS cacerts & pkg manager
├── patches/    # Low-level binary patches (Go pclntab, exebox/seccomp bypass, AArch64 gates)
├── plugins/    # Native Craft Anything MCP server + Ghidra 12.1.4 ARM64 plugin source
└── web/        # Workbench v2.32.0 UI, Smart Marketplace Fusion, touch patches, and audio relay
```

---

## Acknowledgments & Attribution

This project builds upon the foundational Android Bionic host architecture originally created by [werdio325-png/antigravity-android](https://github.com/werdio325-png/antigravity-android), with major architectural upgrades and expansions in this repository (`v2.32.0`), including:

* **Core Engine & Workbench Upgrade (`v2.32.0`):** Updated ARM64 `language_server` (`go1.28`), Workbench UI bundle (`Gemini 4 Carbon/Argon/Hydrogen`, `Orchestrator Carbon/Argon`, `Claude 5.5`), HTTP/2 & WebSocket `StreamAgentStateUpdates` background resume guard (`agy:host-resume` recovery), OAuth redirect & touch input trigger fixes, and native `webp_encoder` / `webm_encoder` binaries.
* **Smart Android Marketplace Fusion & `AGY Video Studio`:** Live OTA plugin fusion via [`antigravity-android-plugins`](https://github.com/Yhoanes/antigravity-android-plugins) (`Built for Android` shelf), native GPU `h264_mediacodec` video studio (`AGY Video Studio`), native 91-tool `GitHub MCP` server, headless ARM64 Chromium MCP (`Chrome DevTools`), on-device `android` CLI bridge, and 16 KB-aligned `libagy-go-dns.so` Bionic DNS resolution for Go plugin CLIs.
* **Native ARM64 Reverse Engineering (`Craft Anything` + `Ghidra 12.1.4`):** 302-tool Rust MCP server (`craft-anything-cli`), native Android Bionic ARM64 Ghidra `decompile` & `sleigh` binaries, and automated on-demand `craft-ghidra-setup` installer.
* **Native Rust `agy-box` Engine, In-App OTA Updater & Production Runtime Hardening:** Native ARM64 Rust multicall accelerator (`pkg` 4-thread parallel TLS/XZ installer, zero-Python `inotify` `agy-daemon`, `112x` faster `agy-fix-paths`, Shizuku-free `agy-notify` IPC, `398` zero-shell `BRIDGE_FORMAT = 12` symlinks), native `window.electronUpdater` 1-tap OTA updater, binary `exebox` / `urpc.Fork` sandbox bypass in `liblanguage_server.so`, and pre-bundled OpenJDK 21 `cacerts`.
* **Real-Time Voice Chat & ARMv8.0-A Compatibility (`liblse_compat.so`):** Native WebRTC/WebAudio microphone pipeline and custom `SIGILL` trap-and-emulate layer for `FEAT_LSE` atomics, `FEAT_RDM`, and `FEAT_FCMA` SIMD instructions.

---

## License, Copyright & Attribution Terms

**Copyright &copy; 2026 [Yhoanes](https://github.com/Yhoanes)** (`v2.29.0` – `v2.32.0` modifications, reverse-engineering suite integration, voice relay, runtime optimizations, documentation, and compiled APK distributions).

Pursuant to **Section 4 of the Apache License, Version 2.0**, all modifications, additions, and the derivative distribution in this repository are licensed under the **Non-Commercial &amp; Mandatory Attribution License** (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)):

* **Mandatory Attribution (`BY`):** Any fork, redistribution, modified version, or binary/APK distribution **must** retain the [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) files intact, credit **Yhoanes** as the author of the `v2.29.0` – `v2.32.0` engineering and expansions, and link back to [https://github.com/Yhoanes/antigravity-android](https://github.com/Yhoanes/antigravity-android).
* **Non-Commercial Use Only (`NC`):** Commercial sale, paid redistribution, paid bundling, or commercial monetization of this derivative work, its modifications, or its compiled APK binaries is **strictly prohibited** without prior written permission from **Yhoanes**.
* **Upstream Foundation:** The foundational Android Bionic host code derived from [werdio325-png/antigravity-android](https://github.com/werdio325-png/antigravity-android) remains licensed under the [Apache License 2.0](LICENSE).

<div align="center">
  <p><font color="#7d8590">Copyright &copy; 2026 <a href="https://github.com/Yhoanes">Yhoanes</a> &nbsp;&bull;&nbsp; <a href="LICENSE">Non-Commercial &amp; Attribution License</a> (Upstream base: <a href="LICENSE">Apache 2.0</a>)</font></p>
</div>
