"""Tests for the conformance-harness registration script.

Runnable with plain pytest and no dependencies beyond the standard library -
no aiosendspin, no network, no JVM.

The script used to rewrite the harness's aiosendspin server adapter so its
hardcoded `allow_unencrypted=True` could be switched off. The harness now
requires the Noise handshake by default and the adapter speaks it, so nothing
in the server adapter is touched any more; `test_leaves_the_server_adapter_alone`
keeps it that way.
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import register_sendspindroid  # noqa: E402

IMPLEMENTATIONS = '''"""Implementation registry and adapter metadata."""

from .models import ImplementationSpec, RoleSpec

IMPLEMENTATIONS: dict[str, ImplementationSpec] = {
    "aiosendspin": ImplementationSpec(name="aiosendspin"),
}
'''

SERVER_ADAPTER = "server = SendspinServer(loop, allow_unencrypted=False)\n"


def make_checkout(tmp_path: Path, implementations: str = IMPLEMENTATIONS) -> Path:
    package = tmp_path / "src" / "conformance"
    (package / "adapters").mkdir(parents=True)
    (package / "implementations.py").write_text(implementations, encoding="utf-8")
    (package / "adapters" / "aiosendspin_server.py").write_text(SERVER_ADAPTER, encoding="utf-8")
    return tmp_path


def register(checkout: Path, monkeypatch: pytest.MonkeyPatch) -> int:
    monkeypatch.setattr(sys, "argv", ["register_sendspindroid.py", str(checkout)])
    return register_sendspindroid.main()


def read_implementations(checkout: Path) -> str:
    return (checkout / "src" / "conformance" / "implementations.py").read_text(encoding="utf-8")


def registered_client_spec(checkout: Path) -> dict[str, object]:
    """The keyword arguments of the client RoleSpec the script appended."""
    tree = ast.parse(read_implementations(checkout))
    entry = tree.body[-1]
    assert isinstance(entry, ast.Assign)
    client = next(kw.value for kw in entry.value.keywords if kw.arg == "client")
    return {kw.arg: ast.literal_eval(kw.value) for kw in client.keywords}


def test_registers_the_adapter_and_the_registry_entry(tmp_path, monkeypatch):
    checkout = make_checkout(tmp_path)
    assert register(checkout, monkeypatch) == 0

    wrapper = checkout / "src" / "conformance" / "adapters" / "sendspindroid_client.py"
    source = Path(register_sendspindroid.__file__).parent / "sendspindroid_client.py"
    assert wrapper.read_text(encoding="utf-8") == source.read_text(encoding="utf-8")

    content = read_implementations(checkout)
    assert content.startswith(IMPLEMENTATIONS)
    assert 'IMPLEMENTATIONS["sendspindroid"]' in content


def test_declares_only_the_encrypted_client_initiated_scenarios(tmp_path, monkeypatch):
    checkout = make_checkout(tmp_path)
    assert register(checkout, monkeypatch) == 0

    spec = registered_client_spec(checkout)
    assert spec["entrypoint"] == "conformance.adapters.sendspindroid_client"
    assert spec["supports_client_initiated"] is True
    # The app is client-initiated only, and has no cleartext dialect left.
    assert spec["supports_server_initiated"] is False
    assert spec["supports_legacy_unencrypted"] is False


def test_is_idempotent(tmp_path, monkeypatch):
    checkout = make_checkout(tmp_path)
    assert register(checkout, monkeypatch) == 0
    once = read_implementations(checkout)
    assert register(checkout, monkeypatch) == 0
    assert read_implementations(checkout) == once


def test_leaves_the_server_adapter_alone(tmp_path, monkeypatch):
    checkout = make_checkout(tmp_path)
    assert register(checkout, monkeypatch) == 0
    adapter = checkout / "src" / "conformance" / "adapters" / "aiosendspin_server.py"
    assert adapter.read_text(encoding="utf-8") == SERVER_ADAPTER


def test_fails_loudly_when_the_registry_shape_changed(tmp_path, monkeypatch):
    checkout = make_checkout(tmp_path, implementations="REGISTRY = {}\n")
    assert register(checkout, monkeypatch) == 1
    assert read_implementations(checkout) == "REGISTRY = {}\n"


def test_fails_when_the_path_is_not_a_checkout(tmp_path, monkeypatch):
    assert register(tmp_path, monkeypatch) == 1
