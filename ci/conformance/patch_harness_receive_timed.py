"""Make the harness's evidence recorders see messages read through receive_timed.

Usage: python patch_harness_receive_timed.py <path-to-conformance-checkout>

aiosendspin b3aa88b (2026-10-08) reads encrypted connections with
EncryptedWebSocket.receive_timed(), which does not call receive(). The
harness's recorders wrap receive() only, so from that commit on they see
nothing the server or client receives: every case then fails with "Server
sent stream/start before it had received any client/state" or a server/time
that answers no client/time, whichever implementation is under test.

This wraps receive_timed() the same way. It is a stopgap for a fix that
belongs in Sendspin/conformance: it does nothing once the harness mentions
receive_timed itself, and it fails loudly if the code it patches has moved.
"""
import sys
from pathlib import Path

path = Path(sys.argv[1]) / "src" / "conformance" / "adapters" / "_aiosendspin_protocol_evidence.py"
s = path.read_text(encoding="utf-8")
if "receive_timed" in s:
    print("already handles receive_timed")
    sys.exit(0)


def rep(old, new, count=1):
    global s
    assert s.count(old) == count, (old[:60], s.count(old))
    s = s.replace(old, new)


# --- first recorder: client/state and client/time received
rep("""        async def receive(transport: Any) -> Any:
            received = await self._original_receive(transport)
            if received.type is not WSMsgType.TEXT:
                return received
""", """        def observe(transport: Any, received: Any) -> Any:
            if received.type is not WSMsgType.TEXT:
                return received
""")
rep("""            elif isinstance(message, dict) and message.get("type") == "client/time":
                time_exchange.append(message)
            return received

        EncryptedWebSocket.send_str = send_str  # type: ignore[method-assign]
        EncryptedWebSocket.receive = receive  # type: ignore[method-assign]
""", """            elif isinstance(message, dict) and message.get("type") == "client/time":
                time_exchange.append(message)
            return received

        async def receive(transport: Any) -> Any:
            return observe(transport, await self._original_receive(transport))

        # The SDK reads an encrypted connection through receive_timed(), which
        # does not go through receive(); both are observed.
        self._original_receive_timed = getattr(EncryptedWebSocket, "receive_timed", None)

        async def receive_timed(transport: Any, clock: Any) -> Any:
            received, received_at = await self._original_receive_timed(transport, clock)
            return observe(transport, received), received_at

        EncryptedWebSocket.send_str = send_str  # type: ignore[method-assign]
        EncryptedWebSocket.receive = receive  # type: ignore[method-assign]
        if self._original_receive_timed is not None:
            EncryptedWebSocket.receive_timed = receive_timed  # type: ignore[method-assign]
""")
rep("""        self._transport_class.send_str = self._original_send_str  # type: ignore[method-assign]
        self._transport_class.receive = self._original_receive  # type: ignore[method-assign]

    def _record_other_sent""", """        self._transport_class.send_str = self._original_send_str  # type: ignore[method-assign]
        self._transport_class.receive = self._original_receive  # type: ignore[method-assign]
        if self._original_receive_timed is not None:
            self._transport_class.receive_timed = self._original_receive_timed  # type: ignore[method-assign]

    def _record_other_sent""")

# --- second recorder: binary frames received
rep("""        async def receive(transport: Any) -> Any:
            message = await self._original_receive(transport)
            if message.type is WSMsgType.BINARY:
                self._frames.append(binary_frame_record(message.data))
            return message

        EncryptedWebSocket.receive = receive  # type: ignore[method-assign]
""", """        def observe(message: Any) -> Any:
            if message.type is WSMsgType.BINARY:
                self._frames.append(binary_frame_record(message.data))
            return message

        async def receive(transport: Any) -> Any:
            return observe(await self._original_receive(transport))

        self._original_receive_timed = getattr(EncryptedWebSocket, "receive_timed", None)

        async def receive_timed(transport: Any, clock: Any) -> Any:
            message, received_at = await self._original_receive_timed(transport, clock)
            return observe(message), received_at

        EncryptedWebSocket.receive = receive  # type: ignore[method-assign]
        if self._original_receive_timed is not None:
            EncryptedWebSocket.receive_timed = receive_timed  # type: ignore[method-assign]
""")
rep("""        \"\"\"Restore the SDK transport's own ``receive``.\"\"\"
        self._transport_class.receive = self._original_receive  # type: ignore[method-assign]
""", """        \"\"\"Restore the SDK transport's own ``receive``.\"\"\"
        self._transport_class.receive = self._original_receive  # type: ignore[method-assign]
        if self._original_receive_timed is not None:
            self._transport_class.receive_timed = self._original_receive_timed  # type: ignore[method-assign]
""")
path.write_text(s, encoding="utf-8")
print("patched", path)
