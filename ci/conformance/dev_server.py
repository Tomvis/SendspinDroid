"""Local aiosendspin development server that refuses the legacy dialect.

Music Assistant ships `allow_legacy_clients=true` by default, which silently
accepts the pre-encryption `client/hello`-first dialect SendSpinDroid speaks
today. That default hides exactly the failures the Phase 1 migration has to fix,
so this runner starts a server with `allow_unencrypted=False` and
`allow_noncompliant_clients=False`.

Unlike the conformance harness adapter, this server keeps a persistent identity
and a file-backed pairing store, so `server_id` and pairing records survive a
restart. Regenerating either invalidates the stored-pubkey records on the phone
and produces a handshake failure that the spec deliberately reports as a silent
close, so the identity file is created once and never overwritten.

See docs/dev-server-runbook.md.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import contextlib
import hashlib
import logging
import os
import struct
import sys
from pathlib import Path

try:
    from aiosendspin.audio.format import AudioFormat
    from aiosendspin.models.types import ConnectionReason, MediaCommand
    from aiosendspin.noise.keys import Identity
    from aiosendspin.noise.pairing import (
        PairingAttempt,
        PairingCodeFormat,
        PairMethod,
    )
    from aiosendspin.noise.pairing_token import (
        decode_pairing_code_token,
        decode_psk_token,
        encode_pairing_code_token,
    )
    from aiosendspin.noise.trust_store import PAIRING_ROUND_LIMIT, FileServerPairingStore
    from aiosendspin.server.roles.color import Color
    from aiosendspin.server.roles.controller.events import ControllerEvent
    from aiosendspin.server.roles.metadata import Metadata
    from aiosendspin.server.server import SendspinServer
    from PIL import Image
except ImportError:  # pragma: no cover - environment guidance, not logic
    print(
        "aiosendspin is not installed. See docs/dev-server-runbook.md:\n"
        "  pip install \"aiosendspin[server]==10.0.0\"",
        file=sys.stderr,
    )
    raise SystemExit(2) from None

LOGGER = logging.getLogger("dev_server")

DEFAULT_STATE_DIR = Path(".dev/sendspin")
DEFAULT_PORT = 8927

# --play-test-audio: 16-bit stereo at 48 kHz, in 100 ms chunks.
TEST_AUDIO_FORMAT = AudioFormat(sample_rate=48000, bit_depth=16, channels=2)
TEST_AUDIO_CHUNK_FRAMES = 4800

# --send-test-colors: two palettes with every field set, blue then red. Color
# refuses a palette that misses one of the spec's 4.5:1 contrast pairs.
TEST_COLORS = (
    Color(
        background_dark=(18, 52, 86),
        background_light=(220, 232, 245),
        primary=(40, 100, 180),
        accent=(230, 140, 40),
        on_dark=(170, 210, 250),
        on_light=(20, 60, 110),
    ),
    Color(
        background_dark=(90, 20, 30),
        background_light=(250, 225, 225),
        primary=(200, 40, 50),
        accent=(40, 160, 150),
        on_dark=(255, 200, 200),
        on_light=(110, 20, 30),
    ),
)


def test_audio_chunk(first_frame: int) -> bytes:
    """PCM whose samples are a frame counter, so a receiver can prove byte-exactness.

    Frame n carries n as a little-endian uint32: the low 16 bits are the left
    sample and the next 16 the right. A client that mis-sizes the audio chunk
    header sees the counter break at every chunk boundary.
    NoiseHandshakeCheck --expect-audio checks exactly that.
    """
    return b"".join(
        struct.pack("<I", (first_frame + i) & 0xFFFFFFFF)
        for i in range(TEST_AUDIO_CHUNK_FRAMES)
    )


def b64u_decode(value: str) -> bytes:
    """Decode base64url without padding."""
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def load_or_create_identity(path: Path) -> Identity:
    """Return the persistent server identity, creating it on first run.

    Mirrors music_assistant/providers/sendspin/security.py. The file is created
    with O_EXCL so two concurrent starts cannot both mint an identity, and a
    corrupt file is never silently replaced -- a changed `server_id` invalidates
    every stored-pubkey pairing record on every paired client.
    """
    try:
        raw = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        pass
    else:
        if not raw:
            raise SystemExit(
                f"{path} is empty. Refusing to mint a new identity, because that would "
                f"invalidate every existing pairing record. Delete it deliberately if "
                f"that is what you want."
            )
        try:
            return Identity.from_private_bytes(b64u_decode(raw))
        except Exception as err:
            raise SystemExit(
                f"{path} is not a valid identity key ({err}). Refusing to overwrite it; "
                f"see docs/dev-server-runbook.md."
            ) from err

    identity = Identity.generate()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(identity.private_b64u)
    LOGGER.info("Generated a new server identity at %s", path)
    return identity


async def _prompt_pairing_code(prompt: str) -> str:
    """Read the dynamic pairing code, or the token, the operator typed at the console."""
    loop = asyncio.get_running_loop()
    print(prompt, end="", flush=True)
    line = await loop.run_in_executor(None, sys.stdin.readline)
    return line.strip()


# Entered by `pair-dynamic wrong=N` and `pair-dynamic-qr wrong=N`. Wrong
# unless the device happens to show it.
WRONG_PAIRING_CODE = "000000"
WRONG_PAIRING_TOKEN = encode_pairing_code_token(bytes(24))

CONSOLE_PROMPTS = {
    PairingCodeFormat.DIGITS: "Enter the pairing code shown on the device: ",
    PairingCodeFormat.QR_CODE: "Enter the SP:1 pairing token scanned from the device: ",
}


def _console_pairing_code(wrong: int, pairing_format: PairingCodeFormat):
    """A `PairingCodeProvider` that enters `wrong` wrong codes, then prompts.

    aiosendspin calls the provider once per round, with no arguments, so each
    wrong code is one round, which the client answers with client/pair-retry.
    """
    left = wrong
    wrong_code = WRONG_PAIRING_TOKEN if pairing_format is PairingCodeFormat.QR_CODE else WRONG_PAIRING_CODE

    async def provider() -> str:
        nonlocal left
        if left > 0:
            left -= 1
            LOGGER.info("entering the wrong pairing code %s (%d more to come)", wrong_code, left)
            return wrong_code
        return await _prompt_pairing_code(CONSOLE_PROMPTS[pairing_format])

    return provider


def _wrong_code(code: str, pairing_format: PairingCodeFormat) -> str:
    """A code that differs from `code`: the next six digits, or a token with one payload bit flipped."""
    if pairing_format is PairingCodeFormat.DIGITS:
        return f"{(int(code) + 1) % 1_000_000:06d}"
    payload = bytearray(decode_pairing_code_token(code))
    payload[0] ^= 0x01
    return encode_pairing_code_token(bytes(payload))


def _shown_code(code: str, pairing_format: PairingCodeFormat) -> bool:
    """Whether `code` is a whole code as the client shows it, not a partly written file."""
    if pairing_format is PairingCodeFormat.DIGITS:
        return len(code) == 6
    return len(code) == 43 and code.startswith("SP:1")


def log_pair_pending(message: str | None) -> None:
    """Log a client/pair-pending: the client holds the attempt back, e.g. at its round limit."""
    LOGGER.info("client held the attempt back (client/pair-pending): %r", message)


def describe_client(client) -> str:
    """One-line summary of a connected client, for the console."""
    security = getattr(client, "connection_security", None)
    roles = getattr(client, "active_role_ids", None) or ()
    return (
        f"{client.client_id}  name={client.name!r}  paired={client.is_paired}  "
        f"security={security}  roles={list(roles)}"
    )


def build_server(
    loop: asyncio.AbstractEventLoop,
    identity: Identity,
    name: str,
    pairing_store,
    *,
    allow_unencrypted: bool = False,
) -> SendspinServer:
    """Construct the server with this runner's policy.

    Kept as a single function so verify_dev_server.py exercises the SAME
    configuration the dev server actually ships, rather than a parallel copy
    that could silently drift if someone flips a flag while debugging.

    `allow_unencrypted` is a parameter only so the verification script can
    stand up a deliberately permissive control server; the runner always
    passes the default.
    """
    return SendspinServer(
        loop,
        identity,
        name,
        pairing_store=pairing_store,
        allow_unencrypted=allow_unencrypted,
        allow_noncompliant_clients=allow_unencrypted,
    )


class DevServer:
    def __init__(self, args: argparse.Namespace) -> None:
        self._args = args
        self._server: SendspinServer | None = None
        self._auto_trusted: set[str] = set()

    async def start(self) -> None:
        args = self._args
        state_dir = Path(args.state_dir)
        identity = load_or_create_identity(Path(args.identity_file or state_dir / "identity.key"))
        store_path = Path(args.pairing_store or state_dir / "pairing_store.json")
        store_path.parent.mkdir(parents=True, exist_ok=True)
        store = await FileServerPairingStore.open(store_path)

        # The whole point of this runner: reject the legacy dialect.
        self._server = build_server(
            asyncio.get_running_loop(), identity, args.name, store
        )

        advertise = [args.advertise_ip] if args.advertise_ip else None
        await self._server.start_server(
            port=args.port,
            host=args.host,
            advertise_addresses=advertise,
            # A client uses one method at a time. One that dials in never
            # advertises _sendspin._tcp, so by default there is nothing to
            # browse for; --discover-clients is for a client that advertises
            # itself and waits (server-initiated).
            discover_clients=args.discover_clients,
        )
        # A client named by URL is dialled until it answers, whether or not
        # mDNS can see it.
        reason = ConnectionReason(args.connect_reason)
        for url in args.connect_to_client:
            LOGGER.info("connecting to client %s (reason: %s)", url, reason.value)
            self._server.connect_to_client(
                url, connection_reason=reason, retry_initial_connection=True
            )

        LOGGER.info("=" * 72)
        LOGGER.info("Sendspin dev server %r listening on %s:%d%s",
                    args.name, args.host, args.port, SendspinServer.API_PATH)
        LOGGER.info("server_id: %s", identity.peer_id)
        LOGGER.info("identity:  %s (do NOT delete - see runbook)", args.identity_file or state_dir / "identity.key")
        LOGGER.info("records:   %s", store_path)
        LOGGER.info("allow_unencrypted=False  allow_noncompliant_clients=False")
        if args.trust_all_unpaired:
            LOGGER.info("auto-trusting unpaired clients on connect (--trust-all-unpaired)")
        if args.discover_clients:
            LOGGER.info("browsing mDNS for clients that advertise themselves (--discover-clients)")
        LOGGER.info("=" * 72)

    async def watch_clients(self) -> None:
        """Log connections and, when asked, auto-trust unpaired clients.

        A Sentinel-keyed client completes the handshake but is not
        playback-capable until the operator trusts it. Forgetting that step
        looks exactly like a bug in the client's server/activate handling, so
        --trust-all-unpaired exists to take it off the table during Phase 1.
        """
        assert self._server is not None
        seen: set[str] = set()
        while True:
            try:
                clients = list(self._server.connected_clients)
            except Exception:
                # Do NOT assume this means shutdown. If the watcher dies here,
                # auto-trust silently stops while --trust-all-unpaired is still
                # on the command line - which recreates precisely the
                # untrusted-client confusion this flag exists to prevent.
                LOGGER.exception("failed to read connected_clients; continuing")
                await asyncio.sleep(1.0)
                continue
            current = set()
            for client in clients:
                current.add(client.client_id)
                if client.client_id not in seen:
                    LOGGER.info("client connected: %s", describe_client(client))
                if (
                    self._args.trust_all_unpaired
                    and not client.is_paired
                    and client.client_id not in self._auto_trusted
                ):
                    # A client can disconnect between the snapshot above and
                    # this call; that must not kill the watcher.
                    try:
                        await self._server.trust_unpaired(client.client_id)
                    except Exception:
                        LOGGER.exception("failed to auto-trust %s", client.client_id)
                    else:
                        self._auto_trusted.add(client.client_id)
                        LOGGER.info("auto-trusted unpaired client %s", client.client_id)
            for gone in seen - current:
                LOGGER.info("client disconnected: %s", gone)
            seen = current
            await asyncio.sleep(1.0)

    async def play_test_audio(self, seconds: float) -> None:
        """Stream `seconds` of counter PCM to the first client that gets a player role."""
        assert self._server is not None
        while True:
            target = next(
                (
                    client
                    for client in self._server.connected_clients
                    if any(role.startswith("player@") for role in client.active_role_ids)
                ),
                None,
            )
            if target is not None:
                break
            await asyncio.sleep(0.5)
        # The server holds a player's audio until its client/state arrives.
        await asyncio.sleep(1.0)

        LOGGER.info("test audio: streaming %.1fs to %s", seconds, target.client_id)
        stream = target.group.start_stream()
        frame = 0
        for _ in range(round(seconds * 48000 / TEST_AUDIO_CHUNK_FRAMES)):
            stream.prepare_audio(test_audio_chunk(frame), TEST_AUDIO_FORMAT)
            frame += TEST_AUDIO_CHUNK_FRAMES
            await stream.commit_audio()
            # Must exceed the client's min_buffer_ms + required_lead_time_ms
            # (the app reports 1500 + 500): a lower limit stalls after every
            # commit and leaves holes in the timeline.
            await stream.sleep_to_limit_buffer(5_000_000)
        # Let the tail leave the send queue before the stream ends.
        await asyncio.sleep(2.0)
        await target.group.stop()
        LOGGER.info("test audio: done, %d frames (%d bytes) committed", frame, frame * 4)

    async def offer_seek(self, seek_max_ms: int) -> None:
        """Offer 'seek' and 'seek_relative' to the first controller, and log what arrives.

        A controller event in the log is a command the server accepted: one
        outside supported_commands, or a seek outside 0..seek_max_ms, is
        dropped before any event is emitted.
        """
        assert self._server is not None
        while True:
            target = next(
                (
                    client
                    for client in self._server.connected_clients
                    if any(role.startswith("controller@") for role in client.active_role_ids)
                ),
                None,
            )
            if target is not None:
                break
            await asyncio.sleep(0.5)
        target.group.add_event_listener(
            lambda _group, event: isinstance(event, ControllerEvent)
            and LOGGER.info("controller event: %r", event)
        )
        controller = target.group.group_role("controller")
        controller.set_seek_max_ms(seek_max_ms)
        controller.set_supported_commands([MediaCommand.SEEK, MediaCommand.SEEK_RELATIVE])
        LOGGER.info("offering seek (seek_max_ms=%d) and seek_relative to %s",
                    seek_max_ms, target.client_id)

    async def send_test_artwork(self) -> None:
        """Send album artwork to the first client with an artwork stream, then clear it.

        Two images: a flat colour, which encodes to a few kilobytes and travels
        as one part, and noise, which encodes to well over the 65517 bytes one
        part can carry. The log line for each is the SHA-256 of the bytes the
        artwork role was handed to transfer; NoiseHandshakeCheck
        --expect-artwork prints the same for what it reassembled.
        """
        assert self._server is not None
        while True:
            target = next(
                (
                    (client, role)
                    for client in self._server.connected_clients
                    for role in client.roles_by_family("artwork")
                    if role.get_channel_configs()
                ),
                None,
            )
            if target is not None:
                break
            await asyncio.sleep(0.5)
        client, role = target

        send_artwork = role.send_artwork

        def logged_send_artwork(channel: int, image_data: bytes, timestamp_us: int) -> None:
            LOGGER.info(
                "test artwork: channel %d %s",
                channel,
                f"image {len(image_data)} bytes sha256={hashlib.sha256(image_data).hexdigest()}"
                if image_data
                else "clear",
            )
            send_artwork(channel, image_data, timestamp_us)

        role.send_artwork = logged_send_artwork
        artwork = client.group.group_role("artwork")
        # The stream's own late-join clear goes out first.
        await asyncio.sleep(1.0)
        for image in (
            Image.new("RGB", (64, 64), (200, 30, 30)),
            Image.frombytes("RGB", (500, 500), os.urandom(500 * 500 * 3)),
            None,
        ):
            await artwork.set_album_artwork(image)
            # The parts of a transfer are paced; let it finish before the next.
            await asyncio.sleep(2.0)
        LOGGER.info("test artwork: done")

    async def send_test_metadata(self) -> None:
        """Send three tracks' metadata to the first metadata client, then clear it.

        A compilation track, whose album artist differs from its artist; an
        album by the track's own artist; and a track without album_artist,
        year or track, which aiosendspin then leaves out of the message. Each
        step is held for 15 s, so a device can be inspected at every one.
        """
        assert self._server is not None
        while True:
            target = next(
                (
                    client
                    for client in self._server.connected_clients
                    if client.roles_by_family("metadata")
                ),
                None,
            )
            if target is not None:
                break
            await asyncio.sleep(0.5)
        metadata = target.group.group_role("metadata")
        for step in (
            Metadata(
                title="Bohemian Rhapsody", artist="Queen", album_artist="Various Artists",
                album="Now 44", year=1999, track=7,
                track_progress=0, track_duration=354000, playback_speed=1000,
            ),
            Metadata(
                title="Love of My Life", artist="Queen", album_artist="Queen",
                album="A Night at the Opera", year=1975, track=9,
                track_progress=0, track_duration=219000, playback_speed=1000,
            ),
            Metadata(
                title="Radio Edit", artist="Queen", album="Singles",
                track_progress=0, track_duration=180000, playback_speed=1000,
            ),
            None,
        ):
            LOGGER.info(
                "test metadata: %s",
                "clear"
                if step is None
                else f"title={step.title!r} artist={step.artist!r} "
                f"album_artist={step.album_artist!r} album={step.album!r} "
                f"year={step.year} track={step.track}",
            )
            metadata.set_metadata(step)
            await asyncio.sleep(15.0)
        LOGGER.info("test metadata: done")

    async def send_test_colors(self) -> None:
        """Send a palette to the first color client, schedule a second, then clear.

        The first applies at once. The second is scheduled ten seconds ahead,
        inside the 20 seconds a server may send it early, so it goes out now: a
        client that follows roles/color/v1.md holds it as its pending update
        and applies it ten seconds after the "scheduled" line, not on receipt.
        The clear is a color object with only a timestamp. Each color object
        this sends is logged as JSON. The one the role sends when it is
        activated, with only a timestamp, goes out before the role is listed
        here, so it is not logged; only the client shows it.
        """
        assert self._server is not None
        while True:
            target = next(
                (
                    (client, role)
                    for client in self._server.connected_clients
                    for role in client.roles_by_family("color")
                ),
                None,
            )
            if target is not None:
                break
            await asyncio.sleep(0.5)
        client, role = target

        send_message = role.send_message

        def logged_send_message(message) -> None:
            LOGGER.info("test colors: sent %s", message.to_json())
            send_message(message)

        role.send_message = logged_send_message
        color = client.group.group_role("color")
        # The activation's own current state goes out first.
        await asyncio.sleep(1.0)
        color.set_color(TEST_COLORS[0])
        LOGGER.info("test colors: palette A current")
        await asyncio.sleep(3.0)
        color.set_color(TEST_COLORS[1], timestamp_us=self._server.clock.now_us() + 10_000_000)
        LOGGER.info("test colors: palette B scheduled for +10s")
        await asyncio.sleep(14.0)
        color.clear()
        LOGGER.info("test colors: done")

    async def pair_with_token(self, token_file: Path) -> None:
        """Pair, by Pairing PSK, with the client whose token is in `token_file`.

        On a Sentinel-keyed connection the server re-handshakes to the pairing
        PSK first, and to the new long-term PSK once the pairing completes, so
        this exercises both in-band re-handshakes without an operator at the
        console.
        """
        assert self._server is not None
        while True:
            if token_file.exists():
                token = decode_psk_token(token_file.read_text(encoding="utf-8").strip())
                client = self._server.get_client(token.client_id)
                if client is not None and client.is_connected and client.active_role_ids:
                    break
            await asyncio.sleep(0.5)
        LOGGER.info("pairing %s with its token (re-handshake to the pairing PSK)", token.client_id)
        try:
            await self._server.initiate_pairing(
                token.client_id,
                PairingAttempt(
                    PairMethod.PAIRING_PSK,
                    pairing_psk=token.pairing_psk,
                    client_id=token.client_id,
                ),
            )
        except Exception as err:
            # NoiseHandshakeCheck without --pair declines the pairing with
            # pair/abort. Either way the re-handshake has happened.
            LOGGER.info("pairing attempt ended: %s: %s", type(err).__name__, err)
        else:
            LOGGER.info("pairing attempt completed")

    async def pair_with_code_file(
        self, code_file: Path, wrong_codes: int, pairing_format: PairingCodeFormat
    ) -> None:
        """Pair, by dynamic pairing code, with the first client that gets a role.

        Stands in for the operator: the code is read from `code_file`, where
        NoiseHandshakeCheck --pair writes the one it is showing - six digits,
        or in the qr_code format the SP:1 token a scan would read. The first
        `wrong_codes` rounds, counted across attempts, enter a different code
        instead. The client answers each with client/pair-retry and shows the
        same code again, up to its round limit, where it aborts with
        pair/abort pairing_code_mismatch. Another attempt then follows, which
        the client holds back with client/pair-pending until pairing is
        allowed (NoiseHandshakeCheck --auto-allow).
        """
        assert self._server is not None
        while True:
            client = next(
                (
                    client
                    for client in self._server.connected_clients
                    if client.is_connected and client.active_role_ids
                ),
                None,
            )
            if client is not None:
                break
            await asyncio.sleep(0.5)

        wrong_left = wrong_codes

        # aiosendspin calls this once per round.
        async def entered_code() -> str:
            nonlocal wrong_left
            while True:
                with contextlib.suppress(FileNotFoundError):
                    code = code_file.read_text(encoding="utf-8").strip()
                    if _shown_code(code, pairing_format):
                        break
                await asyncio.sleep(0.1)
            wrong = wrong_left > 0
            if wrong:
                wrong_left -= 1
                code = _wrong_code(code, pairing_format)
            LOGGER.info(
                "entering %s pairing code (%s)", "a wrong" if wrong else "the client's", pairing_format.value
            )
            return code

        # An attempt runs at most the client's round limit of rounds.
        for _ in range(wrong_codes // PAIRING_ROUND_LIMIT + 2):
            # A code left over from an earlier attempt is not this attempt's.
            code_file.unlink(missing_ok=True)
            LOGGER.info("pairing %s by dynamic pairing code", client.client_id)
            try:
                await self._server.initiate_pairing(
                    client.client_id,
                    PairingAttempt(
                        PairMethod.DYNAMIC_PAIRING_CODE,
                        pairing_code_provider=entered_code,
                        pairing_format=pairing_format,
                        on_pair_pending=log_pair_pending,
                    ),
                )
            except Exception as err:
                LOGGER.info("pairing attempt ended: %s: %s", type(err).__name__, err)
            else:
                LOGGER.info("pairing attempt completed")
                return
        LOGGER.error("no dynamic pairing attempt completed")

    # -- console -----------------------------------------------------------

    async def console(self) -> None:
        if self._args.no_console or not sys.stdin.isatty():
            # Started from a script, nohup, or CI. Without this the first
            # readline() returns EOF immediately and the server shuts down a
            # moment after reporting that it started successfully - which looks
            # exactly like a crash on connect. isatty() is not reliable on
            # Windows shells, hence the explicit flag.
            LOGGER.info("stdin is not a terminal; console disabled, serving until killed")
            while True:
                await asyncio.sleep(3600)

        loop = asyncio.get_running_loop()
        self._print_help()
        while True:
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if not line:
                return
            parts = line.strip().split()
            if not parts:
                continue
            cmd, rest = parts[0], parts[1:]
            try:
                if cmd in ("q", "quit", "exit"):
                    return
                await self._dispatch(cmd, rest)
            except Exception as err:
                LOGGER.error("%s failed: %s", cmd, err)

    async def _dispatch(self, cmd: str, rest: list[str]) -> None:
        assert self._server is not None
        server = self._server
        if cmd in ("h", "help", "?"):
            self._print_help()
        elif cmd in ("c", "clients"):
            clients = list(server.connected_clients)
            if not clients:
                print("(no clients connected)")
            for client in clients:
                print(" ", describe_client(client))
        elif cmd == "trust":
            await server.trust_unpaired(self._resolve(rest))
            print("trusted")
        elif cmd == "untrust":
            await server.untrust_unpaired(self._resolve(rest))
            print("untrusted")
        elif cmd == "unpair":
            await server.unpair(self._resolve(rest))
            print("unpaired")
        elif cmd == "pair":
            if not rest:
                raise ValueError("usage: pair <SP:0... token>")
            token = decode_psk_token(rest[0])
            client_id = token.client_id
            print(f"token decodes to client_id {client_id}")
            if server.get_client(client_id) is None:
                raise ValueError(
                    f"no connected client with id {client_id}; the device must be "
                    f"connected before it can be paired"
                )
            await server.initiate_pairing(
                client_id,
                PairingAttempt(
                    PairMethod.PAIRING_PSK,
                    pairing_psk=token.pairing_psk,
                    client_id=client_id,
                ),
            )
            print("pairing initiated")
        elif cmd in ("pair-dynamic", "pd", "pair-dynamic-qr", "pdq"):
            pairing_format = (
                PairingCodeFormat.QR_CODE if cmd in ("pair-dynamic-qr", "pdq") else PairingCodeFormat.DIGITS
            )
            wrong = next((int(arg.removeprefix("wrong=")) for arg in rest if arg.startswith("wrong=")), 0)
            client_id = self._resolve([arg for arg in rest if not arg.startswith("wrong=")])
            if server.get_client(client_id) is None:
                raise ValueError(
                    f"no connected client with id {client_id}; the device must be "
                    f"connected before it can be paired"
                )
            if pairing_format is PairingCodeFormat.QR_CODE:
                print("initiating dynamic pairing; scan the QR code on the device screen")
            else:
                print("initiating dynamic pairing; read the six digits off the device screen")
            await server.initiate_pairing(
                client_id,
                PairingAttempt(
                    PairMethod.DYNAMIC_PAIRING_CODE,
                    pairing_code_provider=_console_pairing_code(wrong, pairing_format),
                    pairing_format=pairing_format,
                    on_pair_pending=log_pair_pending,
                ),
            )
            print("pairing initiated")
        else:
            print(f"unknown command {cmd!r}; try 'help'")

    def _resolve(self, rest: list[str]) -> str:
        """Resolve a command argument to a client id, defaulting to the only one."""
        assert self._server is not None
        if rest:
            return rest[0]
        clients = list(self._server.connected_clients)
        if len(clients) == 1:
            return clients[0].client_id
        raise ValueError("specify a client_id (see 'clients')")

    @staticmethod
    def _print_help() -> None:
        print(
            "\ncommands:\n"
            "  clients            list connected clients\n"
            "  trust [id]         allow an unpaired client to play (Sentinel PSK)\n"
            "  untrust [id]       revoke unpaired playback\n"
            "  pair <token>       pair using a SP:0... pairing token from the device\n"
            "  pair-dynamic [id] [wrong=N]\n"
            "                     pair using the six-digit dynamic pairing code; with\n"
            f"                     wrong=N the first N rounds enter {WRONG_PAIRING_CODE}\n"
            "  pair-dynamic-qr [id] [wrong=N]\n"
            "                     the same in the qr_code format: paste the SP:1 token\n"
            "                     scanned from the device's QR code; wrong=N enters an\n"
            "                     all-zero code's token\n"
            "  unpair [id]        drop the pairing record and tell the client\n"
            "  quit               stop the server\n"
            "id defaults to the only connected client when omitted.\n"
        )

    async def close(self) -> None:
        if self._server is None:
            return
        # aiosendspin issue 299: close() can hang for client-initiated
        # connections. Bound it so Ctrl-C does not appear to wedge.
        with contextlib.suppress(asyncio.TimeoutError):
            await asyncio.wait_for(self._server.close(), timeout=5.0)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default="Sendspin Dev", help="friendly server name")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--advertise-ip", default=None, help="IP to advertise over mDNS")
    parser.add_argument("--state-dir", default=str(DEFAULT_STATE_DIR))
    parser.add_argument("--identity-file", default=None)
    parser.add_argument("--pairing-store", default=None)
    parser.add_argument(
        "--trust-all-unpaired",
        action="store_true",
        help="auto-trust every unpaired client so it becomes playback-capable",
    )
    parser.add_argument(
        "--discover-clients",
        action="store_true",
        help="browse mDNS for _sendspin._tcp and connect to every client found "
        "(server-initiated connections)",
    )
    parser.add_argument(
        "--connect-to-client",
        action="append",
        default=[],
        metavar="URL",
        help="connect to the client listening at this URL, e.g. "
        "ws://10.0.1.6:8928/sendspin; may be repeated",
    )
    parser.add_argument(
        "--connect-reason",
        choices=[ConnectionReason.DISCOVERY.value, ConnectionReason.PLAYBACK.value],
        default=ConnectionReason.DISCOVERY.value,
        help="what the first connection to a --connect-to-client URL declares: "
        "'discovery' activates with empty activities, 'playback' with ['playback']",
    )
    parser.add_argument(
        "--play-test-audio",
        type=float,
        default=None,
        metavar="SECONDS",
        help="stream this many seconds of counter-pattern PCM to the first player",
    )
    parser.add_argument(
        "--send-test-artwork",
        action="store_true",
        help="send two album artwork images, then a clear, to the first artwork client",
    )
    parser.add_argument(
        "--send-test-metadata",
        action="store_true",
        help="send three tracks' metadata, 15 s apart, then a clear, to the first "
        "metadata client",
    )
    parser.add_argument(
        "--send-test-colors",
        action="store_true",
        help="send a color palette to the first color client, schedule a second "
        "for 10s later, then clear the colors",
    )
    parser.add_argument(
        "--offer-seek",
        type=int,
        default=None,
        metavar="SEEK_MAX_MS",
        help="offer 'seek' (up to this position) and 'seek_relative' to the first "
        "controller, and log every controller command accepted",
    )
    parser.add_argument(
        "--pair-token-file",
        default=None,
        metavar="PATH",
        help="pair, by Pairing PSK, with the client whose SP:0 token is in this file "
        "(after --play-test-audio, if both are given)",
    )
    parser.add_argument(
        "--pair-dynamic-code-file",
        default=None,
        metavar="PATH",
        help="pair, by dynamic pairing code, with the first client that gets a role, "
        "entering the code that client writes to this file (NoiseHandshakeCheck --pair)",
    )
    parser.add_argument(
        "--pair-dynamic-wrong-codes",
        type=int,
        default=0,
        metavar="N",
        help="with --pair-dynamic-code-file: enter a wrong code in the first N rounds, "
        "counted across attempts",
    )
    parser.add_argument(
        "--pair-dynamic-format",
        choices=[f.value for f in PairingCodeFormat],
        default=PairingCodeFormat.DIGITS.value,
        help="with --pair-dynamic-code-file: the emission format to select; with "
        "qr_code the file holds the SP:1 token the device renders as a QR code",
    )
    parser.add_argument(
        "--no-console",
        action="store_true",
        help="do not read commands from stdin; serve until killed",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="raise aiosendspin and this script to DEBUG logging",
    )
    return parser


async def run(args: argparse.Namespace) -> int:
    server = DevServer(args)
    await server.start()
    watcher = asyncio.create_task(server.watch_clients())

    def report_watcher_death(task: asyncio.Task) -> None:
        if task.cancelled():
            return
        err = task.exception()
        if err is not None:
            LOGGER.error("client watcher stopped; auto-trust and connection "
                         "logging are no longer running", exc_info=err)

    watcher.add_done_callback(report_watcher_death)

    async def scripted() -> None:
        if args.offer_seek is not None:
            await server.offer_seek(args.offer_seek)
        if args.send_test_artwork:
            await server.send_test_artwork()
        if args.send_test_metadata:
            await server.send_test_metadata()
        if args.send_test_colors:
            await server.send_test_colors()
        if args.play_test_audio:
            await server.play_test_audio(args.play_test_audio)
        if args.pair_token_file:
            await server.pair_with_token(Path(args.pair_token_file))
        if args.pair_dynamic_code_file:
            await server.pair_with_code_file(
                Path(args.pair_dynamic_code_file),
                args.pair_dynamic_wrong_codes,
                PairingCodeFormat(args.pair_dynamic_format),
            )

    test_audio = None
    if (args.offer_seek is not None or args.send_test_artwork or args.send_test_metadata
            or args.send_test_colors or args.play_test_audio or args.pair_token_file
            or args.pair_dynamic_code_file):
        test_audio = asyncio.create_task(scripted())
        test_audio.add_done_callback(
            lambda task: task.cancelled() or task.exception() is None
            or LOGGER.error("scripted run failed", exc_info=task.exception())
        )

    try:
        await server.console()
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        if test_audio is not None:
            test_audio.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await test_audio
        watcher.cancel()
        # Suppress CancelledError from the cancel above, but tolerate a watcher
        # that already died of something else - otherwise that exception would
        # propagate here and skip the close() below.
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await watcher
        await server.close()
    return 0


def main() -> int:
    args = build_parser().parse_args()
    logging.basicConfig(
        level=logging.DEBUG if args.debug else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    if args.debug:
        logging.getLogger("aiosendspin").setLevel(logging.DEBUG)
        # Note for prologue debugging (item 1.2): aiosendspin 9.1.0 does NOT log
        # the raw client/init and server/init bytes at any level. It builds the
        # prologue in aiosendspin/noise/driver.py as
        # `client_init_text.encode() + server_init_text.encode()` with no log
        # statement, so DEBUG will not surface the bytes you need to diff. To
        # compare against our concatenation you have to capture the frames
        # yourself - a WebSocket proxy, or a local patch to that function.
        LOGGER.debug("aiosendspin does not log raw init bytes; see the comment "
                     "in main() if you are debugging a prologue mismatch")
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
