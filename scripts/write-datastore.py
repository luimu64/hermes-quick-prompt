#!/usr/bin/env python3
"""Write the app's DataStore settings directly (root) for the manual repro."""
import subprocess
import sys

ADB = "/opt/data/bin/adb-arm"
PKG = "dev.hermesprompt.app"
SERVER = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8631"


def entry(key: str, value: str) -> bytes:
    # datastore-preferences 1.1.x wire format (PreferencesProto):
    #   PreferenceMap          field 1 = map<string, Value>           -> 0x0A
    #   map entry (synthetic)  field 1 = key (string), field 2 = Value -> 0x0A + 0x12
    #   Value oneof            stringValue = field 5                   -> 0x2A
    # Without the 0x12 wrapper the entry has no value and DataStore fails
    # with CorruptionException "Value not set".
    kb = key.encode()
    vb = value.encode()
    value_msg = bytes([0x2A, len(vb)]) + vb
    inner = bytes([0x0A, len(kb)]) + kb + bytes([0x12, len(value_msg)]) + value_msg
    return bytes([0x0A, len(inner)]) + inner


def adb(*args):
    return subprocess.run([ADB, "shell"] + list(args), capture_output=True, text=True)


pb = (
    entry("server_url", SERVER)
    + entry("api_key", "test-key")
    + entry("model", "")
    + entry("profile", "")
)

# uid of the app (needs root to stat /data/user/0)
uid = adb("su", "-c", f"stat -c %u /data/user/0/{PKG}").stdout.strip()
print(f"app uid: {uid}")
adir = f"/data/user/0/{PKG}/files/datastore"
adb("su", "-c", f"mkdir -p {adir}")
tmp = "/data/local/tmp/hermes_settings.preferences_pb"
with open("/tmp/hermes_settings.preferences_pb", "wb") as f:
    f.write(pb)
subprocess.run([ADB, "push", "/tmp/hermes_settings.preferences_pb", tmp], capture_output=True)
out = adb("su", "-c", f"cp {tmp} {adir}/hermes_settings.preferences_pb && chown {uid}:{uid} {adir}/hermes_settings.preferences_pb && ls -la {adir}")
print(out.stdout)
print(out.stderr)