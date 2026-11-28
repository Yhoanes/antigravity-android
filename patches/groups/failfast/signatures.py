"""Constants and invariant tails for ARMv8.0-A CPU preinit compatibility shims.

Resolves .preinit_array[0] and .preinit_array[1] dynamically via ELF section
headers + .rela.dyn relocations (no hardcoded PC-relative adrp offsets):
1. .preinit_array[0] + 0x10 (32 bytes): skips HWCAP_ATOMICS trap on ARMv8.0-A
   CPUs so LD_PRELOAD liblse_compat.so can emulate LSE instructions cleanly.
2. .preinit_array[1] + 0x08 (20 bytes): uses ARMv8.0-A load/store instead of
   ldaddal during early preinit before liblse_compat.so registers its handler.
"""

# Slot 0 (+0x10): trailing 20 bytes after adrp+mov+ldr (invariant across Go/GRTE builds)
SLOT0_PATCH_OFFSET = 0x10
SLOT0_RAW_TAIL20 = bytes.fromhex("e903282a3f010aeae1020054e8033836e8045036")
SLOT0_EPILOGUE_AT_74 = bytes.fromhex("f30b40f9fd7bc2a8c0035fd6")
REPLACEMENT_CPU = bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")

# Slot 1 (+0x08): 16 bytes after adrp+add (invariant across Go/GRTE builds, independent of +0x18 alignment)
SLOT1_PATCH_OFFSET = 0x08
SLOT1_RAW_BODY16 = struct_pack_u32(0x52800029, 0xF8E90108, 0xB4000048, 0xD65F03C0) if False else bytes.fromhex("290080520801e9f8480000b4c0035fd6")
REPLACEMENT_ATOMIC_BODY16 = bytes.fromhex("090140f9080100f9490000b4c0035fd6")

EXPECTED = 1

assert len(SLOT0_RAW_TAIL20) == 20
assert len(REPLACEMENT_CPU) == 32
assert len(SLOT1_RAW_BODY16) == 16
assert len(REPLACEMENT_ATOMIC_BODY16) == 16
