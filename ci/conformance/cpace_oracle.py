"""Emit CPace responder vectors from the reference implementation.

The MCF tags are the one construction draft-irtf-cfrg-cpace-21 gives no test
vectors for: section 10.4 leaves the MAC algorithm open and Sendspin pins
HMAC-SHA-512. So the Kotlin responder is pinned against the same `cpace`
package that aiosendspin - and therefore the reference server - depends on.

A lv_cat mistake changes every byte below, so this doubles as the interop
guard for the whole CPace core.

Usage:  pip install cpace && python ci/conformance/cpace_oracle.py
"""

import json

from cpace import CPace, CPaceRole

# Sendspin instantiation: PRS is the six-digit code as ASCII; sid is the
# label, the 32-byte Noise handshake hash, and the pairing index as a
# big-endian uint32; CI is empty; ADa is "server" and ADb is "client".
PRS = b"123456"
HANDSHAKE_HASH = bytes(range(32))
PAIRING_INDEX = 1
SID = b"sendspin-pair-pake-v1" + HANDSHAKE_HASH + PAIRING_INDEX.to_bytes(4, "big")

initiator = CPace.start(role=CPaceRole.INITIATOR, prs=PRS, sid=SID, ad=b"server")
responder = CPace.start(role=CPaceRole.RESPONDER, prs=PRS, sid=SID, ad=b"client")

ya = initiator.public_share
yb = responder.public_share

# Capture the responder scalar BEFORE derive(): the library zeroizes it once
# the shared secret is computed. Injecting it is what makes `yb` reproducible
# in the Kotlin test rather than random per run.
scalar = responder._scalar  # noqa: SLF001

initiator.derive(yb, b"client")
responder.derive(ya, b"server")

print(json.dumps({
    "prs": PRS.decode(),
    "sid": SID.hex(),
    "ya": ya.hex(),
    "yb": yb.hex(),
    "yb_scalar": bytes(scalar).hex(),
    "isk": responder.isk.hex(),
    "server_kc": initiator.tag().hex(),
    "client_kc": responder.tag().hex(),
}, indent=2))
