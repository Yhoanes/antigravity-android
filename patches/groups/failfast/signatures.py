"""Constants and signatures for ARMv8.0-A CPU fail-fast and preinit LSE bypass patches.

1. Bypasses the preinit CPU feature check (go/sigill-fail-fast) which terminates
   with raise(SIGILL) on ARMv8.0-A CPUs lacking hardware LSE (Snapdragon 685,
   Snapdragon 410/625/810, Cortex-A53/A73).
2. Patches the preinit .preinit_array[1] atomic initialization flag so it uses
   standard ARMv8.0-A instructions instead of ldaddal before liblse_compat.so
   registers the SIGILL handler.
"""

# Site 1: CPU feature check (0x85cd6b0 in v2.32.0)
TARGET_CPU = bytes.fromhex("930301b00a60905268de47f9e903282a3f010aeae1020054e8033836e8045036")
REPLACEMENT_CPU = bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")

# Site 2: Preinit atomic flag initialization (0x86339c0 in v2.32.0)
TARGET_ATOMIC = bytes.fromhex("480001f008a12591290080520801e9f8480000b4c0035fd61f2003d5")
REPLACEMENT_ATOMIC = bytes.fromhex("480001f008a12591090140f92a008052490000b4c0035fd60a0100f9")

# Backwards compatibility aliases
TARGET = TARGET_CPU
REPLACEMENT = REPLACEMENT_CPU
EXPECTED = 1

assert len(TARGET_CPU) == 32
assert len(REPLACEMENT_CPU) == 32
assert len(TARGET_ATOMIC) == 28
assert len(REPLACEMENT_ATOMIC) == 28
