<div align="center">

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="96" height="96" style="border-radius: 22px; margin-bottom: 12px;" />

  <p><font size="6"><b>Antigravity for Android</b></font></p>

  <p><font color="#7d8590">Autonomous agentic development environment engineered natively for Android and ARM64.</font></p>

  <p>
    <code>Android 7.0+</code> &nbsp;&bull;&nbsp;
    <code>ARM64 v8.0-A+</code> &nbsp;&bull;&nbsp;
    <code>Shizuku Ready</code> &nbsp;&bull;&nbsp;
    <code>Native Bionic</code> &nbsp;&bull;&nbsp;
    <code>Consistent Keystore</code> &nbsp;&bull;&nbsp;
    <code>Apache 2.0</code>
  </p>

  <br/>

  <!-- Hero Visual -->
  <img alt="Antigravity v2.29.0 Interface in Action" src="docs/assets/voice_home.png" width="100%" style="border-radius: 14px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);" />
  <p align="center"><font size="2" color="#7d8590">Antigravity v2.29.0 autonomous agent development interface on Android with Gemini 4 Argon High & Live Voice Mode</font></p>

</div>

---

<p><font size="5"><b>Architectural Overview</b></font></p>

**Antigravity for Android** brings Google's autonomous agentic coding engine directly to mobile operating systems.

Unlike terminal-based chat wrappers or virtualized environments (PRoot, QEMU, chroot), Antigravity executes **natively inside Android userspace (Bionic)** within the application process. Powered by Gemini models, the agent autonomously inspects codebases, edits project trees, executes build tools, and validates results directly on the physical mobile hardware.

> [!NOTE]
> **Bare-Metal Execution with Transparent Compatibility:** Processes run directly on the host CPU via a hybrid Bionic/Glibc loader without virtualization penalties, taking full advantage of UFS storage throughput and ARMv8.1-A+ Large System Extension (LSE) atomics (with transparent userspace emulation for ARMv8.0-A CPUs via `liblse_compat.so`).

---

<p><font size="5"><b>Key Capabilities</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Autonomous Gemini Agent</b></font></p>
      <p><font color="#7d8590">End-to-end development: task planning, multi-file editing, test execution, compilation, and automatic build diagnosis.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Shizuku (Rish) Elevation</b></font></p>
      <p><font color="#7d8590">Privilege elevation to ADB shell level (<code>uid=2000</code>) without root: direct APK management (<code>pm install</code>), system logs, and shared storage access.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Native <code>pkg</code> Manager</b></font></p>
      <p><font color="#7d8590">Lightweight package manager (tlx) written in Bash and AWK. Installs development runtimes (<code>git</code>, <code>python</code>, <code>node</code>, <code>clang</code>) from Termux repositories.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Mobile & Desktop Viewports</b></font></p>
      <p><font color="#7d8590">Touch-first mobile UI by default, with dynamic switching to a desktop viewport with custom User-Agent for tablets, keyboards, and Samsung DeX.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Environment Awareness</b></font></p>
      <p><font color="#7d8590">Auto-seeded system rules (<code>AGENTS.md</code>) instruct the agent on Android filesystem paths, package syntax, and prevent desktop sandbox traps.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Seamless Upgrades</b></font></p>
      <p><font color="#7d8590">Consistent signing keystore: upgrade APK releases in-place without uninstalling or losing conversation history and project data.</font></p>
    </td>
  </tr>
</table>

---

<p><font size="5"><b>Visual Feature Walkthrough</b></font></p>

### 1. Real-Time Voice Chat & Audio Relay (`v2.29.0`)

Speak directly with the autonomous agent in real time. Powered by native WebRTC/WebAudio microphone bridging (`RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`) and unlocked `v2.29.0` voice relay feature gates (`cascade-enable-voice`, `send_voice_button_variant`).

<br/>

<img alt="Antigravity Live Voice Chat" src="docs/assets/voice_chat.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Active bidirectional voice session with live transcript, audio controls, and workspace overview drawer</font></p>

<br/>

---

### 2. Multi-Agent Workspace & Parallel Subagents

Designed for autonomous multi-agent workflows: project sidebar, parallel subagent orchestration (`self`, `research`), live task status tracking, and integrated workspace overview (`Review`, `Files`, `Terminal`, `Extensions`).

<br/>

<img alt="Antigravity Multi-Agent Workspace" src="docs/assets/workspace_subagents.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Parallel subagent spawning and live status tracking inside the v2.29.0 workspace</font></p>

<br/>

---

### 3. Customizations & Agent Marketplace

Browse and install official plugins, MCP servers, and skills directly from the built-in **Customizations Marketplace** — including **Google Workspace** (Docs, Sheets, Slides, Drive, Calendar), **Build with Google** (Gemini API, Chrome DevTools, Android CLI, Firebase, Science, Google Antigravity SDK), and third-party integrations.

<br/>

<img alt="Customizations and Agent Marketplace" src="docs/assets/customizations_marketplace.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">Integrated Customizations Marketplace for plugins, skills, and MCP servers</font></p>

<br/>

---

### 4. Appearance, Theme Engine & Desktop Mode

Inside Appearance settings, customize the theme palette, contrast, typography (`Google Sans`), and toggle **Desktop Mode** on demand:
* **Mobile Mode (Default):** Touch-friendly controls optimized for one-handed operation and virtual keyboards.
* **Desktop Mode:** Full desktop viewport with wide editor layout and desktop User-Agent (for tablets, physical keyboards, and Samsung DeX).
* *Changes apply dynamically on the fly via automatic WebView reload.*

<br/>

<img alt="Appearance Settings and Desktop Mode Toggle" src="docs/assets/settings_appearance.jpg" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
<p align="center"><font size="2" color="#7d8590">v2.29.0 Appearance settings panel with Desktop Mode, Contrast, Font, and Dark Theme customization</font></p>

<br/>

---

### 5. Autonomous Execution in Terminal

The agent directly drives the environment: executing userspace binaries, creating workspace hierarchies, running compilation tasks, and inspecting diagnostics in real time.

```text
~/ $ uname -a && python3 --version && df -h /data/user/0/com.agy/files
Linux localhost 6.1.138-android14-11-g151cf2b6bfbe-ab13719792 #1 SMP PREEMPT aarch64 Android
Python 3.14.6
Filesystem            Size  Used Avail Use% Mounted on
/dev/block/dm-61      227G  145G   82G  64% /data/user/0
```

---

### 6. Hardware Throughput & Diagnostics

Real-world test: the agent authored a custom Python I/O benchmark and executed multi-pass tests on physical storage. Sequential reads exceeded **1222 MB/s**, and random writes reached **84,395 IOPS**, proving native UFS throughput without emulation overhead.

| Operation | Block Size | Total Volume | Time (s) | Throughput (MB/s) | IOPS |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Sequential Write (`fsync`)** | 1 MB | 128 MB | 0.158 | 809.04 MB/s | 809 |
| **Sequential Read** | 1 MB | 128 MB | 0.105 | 1222.17 MB/s | 1,222 |
| **Random Write (`4K fsync`)** | 4 KB | 19.5 MB | 0.059 | 329.67 MB/s | 84,395 |
| **Random Read (`4K`)** | 4 KB | 19.5 MB | 0.337 | 58.00 MB/s | 14,848 |

---

<p><font size="5"><b>Installation & Getting Started</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>1. Download APK</b></font></p>
      <p><font color="#7d8590">Releases are available under <a href="https://github.com/Yhoanes/antigravity-android/releases">Releases</a>:<br/><br/>
      &bull; <b><code>antigravity.apk</code></b> — standard release (port 45157).<br/>
      &bull; <b><code>antigravity-bypass.apk</code></b> — region bypass edition.<br/><br/>
      <i>Seamless in-place updates are supported across versions.</i></font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>2. Grant Permissions</b></font></p>
      <p><font color="#7d8590">Grant storage access (<code>MANAGE_EXTERNAL_STORAGE</code>).<br/><br/>
      <i>(Optional)</i> Launch <b>Shizuku</b> and grant access for elevated ADB shell operations without root.</font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>3. Bootstrap Environment</b></font></p>
      <p><font color="#7d8590">Install preferred development toolchains via the agent or shell:<br/><br/>
      <code>pkg update</code><br/>
      <code>pkg install git python nodejs</code><br/><br/>
      The environment is ready for development.</font></p>
    </td>
  </tr>
</table>

---

<p><font size="5"><b>System Requirements</b></font></p>

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
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ LPDDR5 / LPDDR5X</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Free Storage</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">2 GB (runtime core)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ UFS (packages and build caches)</td>
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

<p><font size="5"><b>Repository Structure</b></font></p>

```text
antigravity-android/
├── app/        # Android Host, WebView, and process supervisors
├── build/      # Gradle-free deterministic build pipeline (aapt, javac, d8, apksigner)
├── bus/        # Shell bus, IPC daemons, and environment variable composers
├── config/     # Centralized environment configs (app.env, paths.env, tools.env)
├── docs/       # Architecture specifications, manuals, and media assets
├── env/        # Bionic toolchain, rootfs overlay, and pkg package manager
├── patches/    # Low-level binary patches (Go pclntab, seccomp bypass, AArch64 gates)
└── web/        # Web UI, touch ergonomics, and dynamic patchers
```

---

<div align="center">
  <p><font color="#7d8590">Distributed under the <a href="LICENSE">Apache License 2.0</a></font></p>
</div>
