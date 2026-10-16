"""Emit CPace responder vectors from the reference implementation.

The MCF tags are the one construction draft-irtf-cfrg-cpace-21 gives no test
vectors for: section 10.4 leaves the MAC algorithm open and Sendspin pins
HMAC-SHA-512. So the Kotlin responder is pinned against the same `cpace`
package that aiosendspin - and therefore the reference server - depends on.

A lv_cat mistake changes every byte below, so this doubles as the interop
guard for the whole CPace core.

Three vectors: the six-digit code in round 1, the same code in round 2, and a
qr_code PRS - 24 raw bytes, whose length changes the generator string's
padding - in round 1.

Usage:  pip install cpace && python ci/conformance/cpace_oracle.py
"""

import json
import secrets

from cpace import CPace, CPaceRole

# Sendspin instantiation: PRS is the six-digit code as ASCII, or in the
# qr_code format the raw 24-byte code; sid is the label, the 32-byte Noise
# handshake hash, and the pairing index and the round number as big-endian
# uint32s; CI is empty; ADa is "server" and ADb is "client".
DIGITS_PRS = b"123456"
# The code of pairing.md's QR-code emission reference vector.
QR_CODE_PRS = bytes(range(0xE0, 0xF8))
HANDSHAKE_HASH = bytes(range(32))
PAIRING_INDEX = 1


def sid(round_number: int) -> bytes:
    return (
        b"sendspin-pair-pake-v1"
        + HANDSHAKE_HASH
        + PAIRING_INDEX.to_bytes(4, "big")
        + round_number.to_bytes(4, "big")
    )


# Where the reference server is installed, hold the sid to its own derivation.
try:
    from aiosendspin.noise.pairing import _pake_sid
except ImportError:
    pass
else:
    for _round in (1, 2):
        assert sid(_round) == _pake_sid(HANDSHAKE_HASH, PAIRING_INDEX, _round)

# The library draws each scalar from `secrets` inside start(), so an unpatched
# run is random and cannot reproduce a previously published vector. Pinning the
# two scalars is what makes this script a re-runnable check rather than a
# one-shot capture: without it, a future `cpace` upgrade could change behaviour
# and there would be no way to tell that from ordinary randomness.
FIXED_SCALARS = [
    bytes.fromhex("4a1f3d8e2b7c05916ad4e8f3021c6b7d95e0a4381fc27de6b0935a41c87e2d05"),
    bytes.fromhex("025984ca800ed7505e9f20a4b92314c3721e16112fe1447bd807e2fcf9813398"),
]

_real_token_bytes = secrets.token_bytes


def vector(prs: bytes, round_number: int) -> dict[str, str]:
    """One CPace run, initiator and responder, under the fixed scalars."""
    pending = list(FIXED_SCALARS)

    def deterministic_token_bytes(n: int) -> bytes:
        if n == 32 and pending:
            return pending.pop(0)
        return _real_token_bytes(n)

    session_id = sid(round_number)
    secrets.token_bytes = deterministic_token_bytes
    try:
        initiator = CPace.start(role=CPaceRole.INITIATOR, prs=prs, sid=session_id, ad=b"server")
        responder = CPace.start(role=CPaceRole.RESPONDER, prs=prs, sid=session_id, ad=b"client")
    finally:
        secrets.token_bytes = _real_token_bytes

    ya = initiator.public_share
    yb = responder.public_share

    # Captured before derive(): the library zeroizes the scalar once the shared
    # secret is computed. Injecting it is what makes `yb` reproducible in the
    # Kotlin test rather than random per run.
    scalar = responder._scalar  # noqa: SLF001

    initiator.derive(yb, b"client")
    responder.derive(ya, b"server")

    return {
        "prs": prs.hex(),
        "sid": session_id.hex(),
        "ya": ya.hex(),
        "yb": yb.hex(),
        "yb_scalar": bytes(scalar).hex(),
        "isk": responder.isk.hex(),
        "server_kc": initiator.tag().hex(),
        "client_kc": responder.tag().hex(),
    }


print(json.dumps({
    "digits_round_1": vector(DIGITS_PRS, 1),
    "digits_round_2": vector(DIGITS_PRS, 2),
    "qr_code_round_1": vector(QR_CODE_PRS, 1),
}, indent=2))
