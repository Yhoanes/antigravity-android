"""ARMv8.0-A CPU preinit compatibility patch (dynamic .preinit_array + .rela.dyn).

Resolves .preinit_array[0] and .preinit_array[1] dynamically from the ELF64
section headers and .rela.dyn table, ensuring compatibility on ARMv8.0-A
processors (Cortex-A53/A73, Snapdragon 685) across compiler/linker updates.
"""

import struct
import sys

from patch_lib import cli as _cli
from patch_lib import module_base as base
from patch_lib.report import hex_list
from groups.failfast import signatures as sig

GROUP_ID = "failfast"
GROUP = "failfast"
FILE = "failfast"
DESCRIPTION = "Ensure ARMv8.0-A preinit compatibility via dynamic .preinit_array resolution."
TAG = "patch_failfast"
EXPECTED_COUNTS = {"cpu_feature": 1, "preinit_atomic": 1}


def _resolve_preinit_slots(data):
    """Return (slot0_file_off, slot1_file_off) via ELF64 .preinit_array + .rela.dyn."""
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None, None
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)
    if e_shoff <= 0 or e_shentsize < 64 or e_shnum == 0 or e_shstrndx >= e_shnum:
        return None, None
    if e_shoff + e_shnum * e_shentsize > len(data):
        return None, None

    shstr_off = struct.unpack_from("<Q", data, e_shoff + e_shstrndx * e_shentsize + 0x18)[0]
    preinit_vaddr = None
    preinit_size = 0
    rela_ranges = []
    vaddr_segments = []

    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", data, 0x36)
    if e_phoff > 0 and e_phoff + e_phnum * e_phentsize <= len(data):
        for i in range(e_phnum):
            ph = e_phoff + i * e_phentsize
            p_type = struct.unpack_from("<I", data, ph)[0]
            if p_type == 1:  # PT_LOAD
                p_offset, p_vaddr, _, p_filesz = struct.unpack_from("<QQQQ", data, ph + 8)
                vaddr_segments.append((p_vaddr, p_vaddr + p_filesz, p_offset))

    for i in range(e_shnum):
        sh = e_shoff + i * e_shentsize
        sh_name = struct.unpack_from("<I", data, sh)[0]
        end = data.find(b"\x00", shstr_off + sh_name)
        if end < 0:
            continue
        name = data[shstr_off + sh_name:end].decode("ascii", "replace")
        sh_addr, sh_offset, sh_size = struct.unpack_from("<QQQ", data, sh + 0x10)
        if name == ".preinit_array":
            preinit_vaddr = sh_addr
            preinit_size = sh_size
        elif name == ".rela.dyn":
            rela_ranges.append((sh_offset, sh_size))

    if preinit_vaddr is None or preinit_size < 16 or not rela_ranges:
        return None, None

    def vaddr_to_foff(va):
        for va_start, va_end, p_off in vaddr_segments:
            if va_start <= va < va_end:
                return p_off + (va - va_start)
        return va

    slots = {}
    for r_off_base, r_sz in rela_ranges:
        if r_off_base + r_sz > len(data):
            continue
        for pos in range(r_off_base, r_off_base + r_sz, 24):
            r_offset, _, r_addend = struct.unpack_from("<QQq", data, pos)
            if preinit_vaddr <= r_offset < preinit_vaddr + 16:
                idx = (r_offset - preinit_vaddr) // 8
                slots[idx] = vaddr_to_foff(r_addend)

    return slots.get(0), slots.get(1)


def run(target_path, verify=False, dry_run=False, allow_missing=False, log=print):
    report = base.new_report(
        GROUP_ID, GROUP, FILE, DESCRIPTION,
        expected_counts=EXPECTED_COUNTS, offsets={})

    data = base.read_target(target_path, report)
    if data is None:
        return False, report

    s0, s1 = _resolve_preinit_slots(data)
    raw_cpu = []
    repl_cpu = []
    raw_atomic = []
    repl_atomic = []

    if s0 is not None and s0 + 0x80 <= len(data):
        cpu_site = s0 + sig.SLOT0_PATCH_OFFSET
        epilogue_ok = bytes(data[s0 + 0x74:s0 + 0x80]) == sig.SLOT0_EPILOGUE_AT_74
        cur_cpu = bytes(data[cpu_site:cpu_site + 32])
        if epilogue_ok and cur_cpu == sig.REPLACEMENT_CPU:
            repl_cpu.append(cpu_site)
        elif epilogue_ok and cur_cpu[12:32] == sig.SLOT0_RAW_TAIL20:
            raw_cpu.append(cpu_site)

    if s1 is not None and s1 + 0x18 <= len(data):
        atomic_site = s1 + sig.SLOT1_PATCH_OFFSET
        cur_atomic = bytes(data[atomic_site:atomic_site + 16])
        if cur_atomic == sig.REPLACEMENT_ATOMIC_BODY16:
            repl_atomic.append(atomic_site)
        elif cur_atomic == sig.SLOT1_RAW_BODY16:
            raw_atomic.append(atomic_site)

    report["observed_counts"] = {
        "cpu_feature": {"raw": len(raw_cpu), "replacement": len(repl_cpu)},
        "preinit_atomic": {"raw": len(raw_atomic), "replacement": len(repl_atomic)},
    }
    report["offsets"] = {
        "cpu_raw": hex_list(raw_cpu),
        "cpu_replacement": hex_list(repl_cpu),
        "atomic_raw": hex_list(raw_atomic),
        "atomic_replacement": hex_list(repl_atomic),
    }

    cpu_ok = (len(raw_cpu) == 0 and len(repl_cpu) == 1)
    atomic_ok = (len(raw_atomic) == 0 and len(repl_atomic) == 1)

    if verify:
        ok = cpu_ok and atomic_ok
        return base.run_verify(
            report, ok, TAG, log,
            detail=" (cpu: raw=%d repl=%d; atomic: raw=%d repl=%d)" % (
                len(raw_cpu), len(repl_cpu), len(raw_atomic), len(repl_atomic))), report

    if cpu_ok and atomic_ok:
        return base.run_already(
            report, TAG, log,
            detail=" (cpu repl=%d, atomic repl=%d)" % (len(repl_cpu), len(repl_atomic))), report

    if (len(raw_cpu) == 0 and len(repl_cpu) == 0) and (len(raw_atomic) == 0 and len(repl_atomic) == 0):
        if allow_missing:
            return base.run_missing(
                report, True, TAG, log, "no preinit signature found",
                state="skipped"), report

    if len(raw_cpu) + len(repl_cpu) != 1:
        report["messages"].append("unexpected cpu feature count (raw=%d, repl=%d)" % (len(raw_cpu), len(repl_cpu)))
        log("[%s] ERROR: %s" % (TAG, report["messages"][-1]))
        return False, report

    if len(raw_atomic) + len(repl_atomic) != 1:
        report["messages"].append("unexpected preinit atomic count (raw=%d, repl=%d)" % (len(raw_atomic), len(repl_atomic)))
        log("[%s] ERROR: %s" % (TAG, report["messages"][-1]))
        return False, report

    changed = 0
    for pos in raw_cpu:
        data[pos:pos + len(sig.REPLACEMENT_CPU)] = sig.REPLACEMENT_CPU
        changed += 1

    for pos in raw_atomic:
        data[pos:pos + len(sig.REPLACEMENT_ATOMIC_BODY16)] = sig.REPLACEMENT_ATOMIC_BODY16
        changed += 1

    report["changed"] = changed
    report["state"] = "dry-run" if dry_run else "patched"
    if dry_run:
        log("[%s] dry-run: %d site(s) would be patched" % (TAG, changed))
        return True, report

    base.commit_write(target_path, data, log, TAG,
                      "patched %d occurrence(s)" % changed)
    return True, report


if __name__ == "__main__":
    sys.exit(_cli.main(run,
                       usage="Usage: python3 -m groups.failfast.module <binary> "
                             "[--verify|--dry-run|--allow-missing]"))
