"""Pure signature analysis for the eligibility and voice gate patches."""

from groups.gates import signatures as sig


def analyze(data):
    """Return a dict with raw/patched site offsets. Pure, no IO."""
    raw_cli = [m.start() for m in sig.SIG_CLI.finditer(data)]
    raw_mgr = [m.start() for m in sig.SIG_MGR.finditer(data)]
    pat_cli = [m.start() for m in sig.PATCHED_CLI.finditer(data)]
    pat_mgr = [m.start() for m in sig.PATCHED_MGR.finditer(data)]
    raw_vrel = [m.start() for m in sig.SIG_VOICE_RELAY.finditer(data)]
    pat_vrel = [m.start() for m in sig.PATCHED_VOICE_RELAY.finditer(data)]
    raw_vall = [m.start() for m in sig.SIG_VOICE_ALLOWED.finditer(data)]
    pat_vall = [m.start() for m in sig.PATCHED_VOICE_ALLOWED.finditer(data)]
    raw_vc1 = [m.start() for m in sig.SIG_VOICE_CFG_S1.finditer(data)]
    pat_vc1 = [m.start() for m in sig.PATCHED_VOICE_CFG_S1.finditer(data)]
    raw_vc2 = [m.start() for m in sig.SIG_VOICE_CFG_S2.finditer(data)]
    pat_vc2 = [m.start() for m in sig.PATCHED_VOICE_CFG_S2.finditer(data)]
    return {
        "raw_cli": raw_cli,
        "raw_mgr": raw_mgr,
        "pat_cli": pat_cli,
        "pat_mgr": pat_mgr,
        "raw_vrel": raw_vrel,
        "pat_vrel": pat_vrel,
        "raw_vall": raw_vall,
        "pat_vall": pat_vall,
        "raw_vc1": raw_vc1,
        "pat_vc1": pat_vc1,
        "raw_vc2": raw_vc2,
        "pat_vc2": pat_vc2,
    }
