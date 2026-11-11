"""Byte signatures for the eligibility and voice gate patches.

HARD RULE: never touch the `w2` shape (channelz `(*ChannelTrace).clear`).
Both anchors are register specific (w1 / w3), so the w2 site cannot match.
"""

import re

# --- CLI gate -------------------------------------------------------------
# ldrb w1, [x0, #8] ; tbnz w1, #0 ; orr x1, xzr, x1 ; strb w1, [x0, #8]
_CLI_TAIL = rb"\xe1\x03\x40\xb2\x01\x20\x00\x39"
SIG_CLI = re.compile(rb"(\x01\x20\x40\x39)(.{3}\x37)" + _CLI_TAIL, re.S)
PATCHED_CLI = re.compile(rb"(\x21\x00\x80\x52)(.{3}\x37)" + _CLI_TAIL, re.S)
REPL_CLI = b"\x21\x00\x80\x52"  # mov w1, #1

# --- Manager auth gate ----------------------------------------------------
# ldrb w3,[x0,#8] ; tbz w3,#0,... ; token setup (ldp/ldr + ... + stp)
_ARM64_TBZ_W3_BIT0 = rb"[\x03\x23\x43\x63\x83\xa3\xc3\xe3]..\x36"
_ARM64_TOKEN_SETUP = rb"(?:....){1,2}\x03\x10\x06\xa9"
SIG_MGR = re.compile(rb"(\x03\x20\x40\x39" + _ARM64_TBZ_W3_BIT0 + _ARM64_TOKEN_SETUP + rb")", re.S)
# mov w3,#1 ; strb w3,[x0,#8] ; token setup
PATCHED_MGR = re.compile(rb"(\x23\x00\x80\x52\x03\x20\x00\x39" + _ARM64_TOKEN_SETUP + rb")", re.S)
REPL_MGR = b"\x23\x00\x80\x52\x03\x20\x00\x39"  # mov w3,#1 ; strb w3,[x0,#8]

# --- Voice relay & allowed gates ------------------------------------------
_VOICE_RET_TAIL = rb"\xe0\x03\x40\xb2\xfd\x83\x5f\xf8\xfe\x07\x43\xf8\xc0\x03\x5f\xd6"
SIG_VOICE_RELAY = re.compile(rb"\xe4\x00\x00\xb4\x84\x04\x40\x39\xa4\x00\x00\x36" + _VOICE_RET_TAIL, re.S)
PATCHED_VOICE_RELAY = re.compile(rb"(?<!\xe3\x2b\x00\xf9)(\x1f\x20\x03\xd5){3}" + _VOICE_RET_TAIL, re.S)
REPL_VOICE_RELAY = b"\x1f\x20\x03\xd5" * 3

SIG_VOICE_ALLOWED = re.compile(rb"\xe3\x2b\x00\xf9\x64\x00\x00\xb4\x05\x60\x42\xf9\xa5\x00\x00\xb5" + _VOICE_RET_TAIL, re.S)
PATCHED_VOICE_ALLOWED = re.compile(rb"\xe3\x2b\x00\xf9(\x1f\x20\x03\xd5){3}" + _VOICE_RET_TAIL, re.S)
REPL_VOICE_ALLOWED = b"\xe3\x2b\x00\xf9" + (b"\x1f\x20\x03\xd5" * 3)

# --- Voice instructions ResolveRequestAgentConfig -------------------------
# Site 1 @ 0x754a528 (12 bytes):
#   cbz x10, 0x754a674; tbz w9, #0, 0x754a53c; cbz x10, 0x754a674
# -> tbnz w9, #0, 0x754a534; cbz x10, 0x754a674; b 0x754a53c
REPL_VOICE_CFG_S1 = bytes.fromhex("690000374a0a00b403000014")
SIG_VOICE_CFG_S1 = re.compile(re.escape(bytes.fromhex("6a0a00b4890000362a0a00b4")), re.S)
PATCHED_VOICE_CFG_S1 = re.compile(re.escape(REPL_VOICE_CFG_S1), re.S)

# Site 2 @ 0x754a674 (24 bytes):
#   ldr x9, [sp, #0x128]; cbnz x9, 0x754a710; ldr x9, [sp, #0x140]; cbnz x9, 0x754a68c; mov x3, xzr; b 0x754a714
# -> mov x0, xzr; orr x1, xzr, #1; mov x2, xzr; bl 0x7cdee50 <buildProductAPICustomAgentSpec>; str x0, [sp, #0x138]; b 0x754a630
REPL_VOICE_CFG_S2 = bytes.fromhex("e0031faae10340b2e2031faaf4511e94e09f00f9eaffff17")
SIG_VOICE_CFG_S2 = re.compile(re.escape(bytes.fromhex("e99740f9c90400b5e9a340f9690000b5e3031faa23000014")), re.S)
PATCHED_VOICE_CFG_S2 = re.compile(re.escape(REPL_VOICE_CFG_S2), re.S)

# --- Android terminal sandbox (exebox) bypass -----------------------------
# 1. exebox.userNamespacesBlocked @ 0x636a430 (32 bytes):
#    jump directly to 0x636a494 ("user namespaces are disabled...") so exebox.Availability() returns false
REPL_SBOX_AVAIL = bytes.fromhex("f4c305d19dfa3fa99f020091fd2300d115000014020040f9410480d2fa0300aa")
SIG_SBOX_AVAIL = re.compile(re.escape(bytes.fromhex("f4c305d19dfa3fa99f020091fd2300d1e0bf00f9020040f9410480d2fa0300aa")), re.S)
PATCHED_SBOX_AVAIL = re.compile(re.escape(REPL_SBOX_AVAIL), re.S)

# 2. exebox.(*Box).init @ 0x636a220 (20 bytes):
#    nop out tbz w2, #0, 0x636a3f0 so Box.init always uses in-process urpc.Local instead of initSbox
REPL_SBOX_INIT = bytes.fromhex("000f00b5e03f40f9020040f9420040391f2003d5")
SIG_SBOX_INIT = re.compile(re.escape(bytes.fromhex("000f00b5e03f40f9020040f942004039020e0036")), re.S)
PATCHED_SBOX_INIT = re.compile(re.escape(REPL_SBOX_INIT), re.S)

# 3. command.prepareSandbox @ 0x69db628 (28 bytes):
#    unconditionally set cfg.NoSandbox = 1 (strb w1, [x0])
REPL_SBOX_PREP = bytes.fromhex("e10340b20120033901000039e16740f9a10000b41f2003d51f2003d5")
SIG_SBOX_PREP = re.compile(re.escape(bytes.fromhex("e10340b201200339e16740f9c10000b422204239420040d202000039")), re.S)
PATCHED_SBOX_PREP = re.compile(re.escape(REPL_SBOX_PREP), re.S)
