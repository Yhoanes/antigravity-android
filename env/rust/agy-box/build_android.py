#!/usr/bin/env python3
"""Cross-compile agy-box for Android ARM64 (API 24+, baseline ARMv8.0-A) and verify ELF."""

import glob
import os
import platform
import re
import shutil
import struct
import subprocess
import sys


def find_ndk_llvm_bin() -> str:
    candidates = []
    for env_key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME"):
        val = os.environ.get(env_key)
        if val and os.path.isdir(val):
            candidates.append(val)

    local_app = os.environ.get("LOCALAPPDATA", "")
    home = os.path.expanduser("~")
    sdk_roots = [
        os.path.join(local_app, "Android", "Sdk", "ndk"),
        os.path.join(home, "Android", "Sdk", "ndk"),
        os.path.join(home, "Library", "Android", "sdk", "ndk"),
        "/opt/android-sdk/ndk",
    ]
    for root in sdk_roots:
        if os.path.isdir(root):
            for sub in sorted(os.listdir(root)):
                full = os.path.join(root, sub)
                if os.path.isdir(full):
                    candidates.append(full)

    for ndk in candidates:
        prebuilt = os.path.join(ndk, "toolchains", "llvm", "prebuilt")
        if not os.path.isdir(prebuilt):
            continue
        for host_dir in os.listdir(prebuilt):
            bin_dir = os.path.join(prebuilt, host_dir, "bin")
            if os.path.isdir(bin_dir):
                return bin_dir
    raise RuntimeError("Android NDK LLVM toolchain not found")


def inspect_elf(path: str):
    data = open(path, "rb").read()
    assert data[:4] == b"\x7fELF", "Output is not an ELF binary"
    ei_class, ei_data = data[4], data[5]
    assert ei_class == 2 and ei_data == 1, "Expected 64-bit little-endian ELF"
    e_type, e_machine = struct.unpack_from("<HH", data, 0x10)
    assert e_machine == 0xB7, f"Expected EM_AARCH64 (0xB7), got {hex(e_machine)}"

    # Ensure no contiguous foreign prefix literal sits in .rodata
    forbidden = (b"/data/data/" + b"com.termux" + b"/files/usr")
    assert forbidden not in data, "Forbidden literal foreign prefix found in ELF .rodata!"

    # Extract DT_NEEDED / shared library names
    so_refs = sorted(set(m.decode("ascii") for m in re.findall(rb"[a-zA-Z0-9_+-]+\.so(?:\.[0-9]+)?", data)))
    allowed_bionic = {"libc.so", "libm.so", "libdl.so", "ld-android.so"}
    # Filter to actual SONAME-like strings near dynstr (check using llvm-readobj if available)
    size_kb = len(data) / 1024.0
    print(f"[agy-box] Verified ELF64 EM_AARCH64 ({size_kb:.1f} KB, e_type={e_type})")
    return so_refs


def main():
    crate_dir = os.path.dirname(os.path.abspath(__file__))
    ndk_bin = find_ndk_llvm_bin()
    is_win = platform.system().lower().startswith("win")
    clang_ext = ".cmd" if is_win else ""
    exe_ext = ".exe" if is_win else ""

    clang24 = os.path.join(ndk_bin, f"aarch64-linux-android24-clang{clang_ext}")
    if not os.path.isfile(clang24):
        matches = sorted(glob.glob(os.path.join(ndk_bin, f"aarch64-linux-android*-clang{clang_ext}")))
        if not matches:
            raise RuntimeError(f"No aarch64-linux-android*-clang found in {ndk_bin}")
        clang24 = matches[0]
    llvm_ar = os.path.join(ndk_bin, f"llvm-ar{exe_ext}")
    llvm_readobj = os.path.join(ndk_bin, f"llvm-readobj{exe_ext}")

    env = os.environ.copy()
    cargo_bin = os.path.join(os.path.expanduser("~"), ".cargo", "bin")
    llvm_mingw = os.path.expandvars(
        r"%LOCALAPPDATA%\Microsoft\WinGet\Packages\MartinStorsjo.LLVM-MinGW.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\llvm-mingw-20261020-ucrt-x86_64\bin"
    )
    extra_paths = [p for p in (cargo_bin, llvm_mingw, ndk_bin) if os.path.isdir(p)]
    env["PATH"] = os.pathsep.join(extra_paths + [env.get("PATH", "")])
    env["CC_aarch64_linux_android"] = clang24
    env["AR_aarch64_linux_android"] = llvm_ar
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = clang24
    env["CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS"] = "-C target-feature=-lse"

    cargo_exe = shutil.which("cargo", path=env["PATH"]) or "cargo"
    print(f"[agy-box] Using NDK linker: {clang24}")
    subprocess.check_call(
        [cargo_exe, "build", "--release", "--target", "aarch64-linux-android"],
        cwd=crate_dir,
        env=env,
    )

    built_elf = os.path.join(crate_dir, "target", "aarch64-linux-android", "release", "agy-box")
    inspect_elf(built_elf)

    if os.path.isfile(llvm_readobj):
        out = subprocess.check_output([llvm_readobj, "--needed-libs", built_elf], text=True)
        print("[agy-box] Dynamic dependencies:\n" + out.strip())

    overlay_dst = os.path.normpath(os.path.join(crate_dir, "..", "..", "overlay", "bin", "agy-box"))
    os.makedirs(os.path.dirname(overlay_dst), exist_ok=True)
    shutil.copy2(built_elf, overlay_dst)
    print(f"[agy-box] Installed -> {overlay_dst}")


if __name__ == "__main__":
    sys.exit(main() or 0)
