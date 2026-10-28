"""Tests for context/scripts/tuya_lamps.py (no real lamps are contacted)."""
import importlib.util
import json
from pathlib import Path

import pytest
import tinytuya

SCRIPT = Path(__file__).resolve().parents[2] / "context" / "scripts" / "tuya_lamps.py"

pytestmark = pytest.mark.skipif(not SCRIPT.exists(), reason="private context repo not present")


@pytest.fixture
def lamps(tmp_path, monkeypatch):
    spec = importlib.util.spec_from_file_location("tuya_lamps", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    monkeypatch.setattr(mod, "DEVICES", str(tmp_path / "devices.json"))
    monkeypatch.setattr(mod, "_scan", None)
    return mod


def fake_bulb(status_response, version=3.3):
    """A real BulbDevice with its transport replaced; records outgoing DP writes."""
    b = tinytuya.BulbDevice("ebe4889dc6441727559kx9", "192.168.0.11", "0123456789abcdef")
    b.set_version(version)
    b.sent = []
    b._send_receive = lambda *a, **kw: dict(status_response)
    b.set_multiple_values = lambda dps, nowait=False: b.sent.append(dps) or {}
    b.set_status = lambda on, switch=1, nowait=False: b.sent.append({str(switch): on}) or {}
    return b


DEVICE = {"name": "Sala_Luminaria", "id": "ebe4889dc6441727559kx9",
          "key": "0123456789abcdef", "ip": "192.168.0.12", "version": 3.3}

FULL_DPS = {"dps": {"20": True, "21": "white", "22": 10, "23": 216, "24": "011403e803e8", "26": 0}}


class TestPrepare:
    def test_detects_type_b_from_live_status(self, lamps, monkeypatch):
        b = fake_bulb(FULL_DPS)
        monkeypatch.setattr(lamps, "resolve", lambda d, devs=None, force=False: d)
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        out = lamps.prepare(dict(DEVICE))

        assert out.bulb_type == "B"
        assert out.dpset["brightness"] == "22"
        assert (out.dpset["value_min"], out.dpset["value_max"]) == (10, 1000)

    def test_falls_back_when_detection_fails(self, lamps, monkeypatch, capsys):
        # Partial response: no '21', so tinytuya cannot classify the bulb.
        b = fake_bulb({"dps": {"22": 500}})
        monkeypatch.setattr(lamps, "resolve", lambda d, devs=None, force=False: d)
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        out = lamps.prepare(dict(DEVICE))

        assert out.bulb_configured
        assert out.dpset["brightness"] == "22"
        assert (out.dpset["value_min"], out.dpset["value_max"]) == (10, 1000)
        assert "falling back to type B" in capsys.readouterr().err

    def test_retries_on_a_new_address_when_the_reply_fails_to_decrypt(self, lamps, monkeypatch):
        bad = fake_bulb({"Error": "Unexpected Payload from Device", "Err": "904"})
        good = fake_bulb(FULL_DPS)
        monkeypatch.setattr(lamps, "reachable", lambda ip: True)
        monkeypatch.setattr(lamps, "lan_scan",
                            lambda: {DEVICE["id"]: {"ip": "192.168.0.11", "version": "3.3"}})
        monkeypatch.setattr(lamps, "connect", lambda d: good if d["ip"] == "192.168.0.11" else bad)

        assert lamps.prepare(dict(DEVICE)).bulb_type == "B"

    def test_a_rotated_local_key_is_named_as_such(self, lamps, monkeypatch):
        # The scan still sees this device id at this very address, so the address is right and it
        # is the key that is stale - what re-pairing a lamp does.
        b = fake_bulb({"Error": "Unexpected Payload from Device", "Err": "904"})
        monkeypatch.setattr(lamps, "reachable", lambda ip: True)
        monkeypatch.setattr(lamps, "lan_scan",
                            lambda: {DEVICE["id"]: {"ip": DEVICE["ip"], "version": "3.3"}})
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        with pytest.raises(SystemExit) as e:
            lamps.prepare(dict(DEVICE))

        assert "local key is wrong" in str(e.value)
        assert "pull" in str(e.value)

    def test_unreachable_lamp_exits_with_a_clear_message(self, lamps, monkeypatch):
        b = fake_bulb({"Error": "Network Error: Unable to Connect", "Err": "901"})
        monkeypatch.setattr(lamps, "resolve", lambda d, devs=None, force=False: d)
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        with pytest.raises(SystemExit) as e:
            lamps.prepare(dict(DEVICE))

        assert "cannot reach lamp" in str(e.value)
        assert "Sala_Luminaria" in str(e.value)


class TestBrightness:
    @pytest.mark.parametrize("pct,expected", [(100, 1000), (50, 500), (1, 10)])
    def test_percentage_maps_onto_dps_22(self, lamps, monkeypatch, pct, expected):
        b = fake_bulb(FULL_DPS)
        monkeypatch.setattr(lamps, "resolve", lambda d, devs=None, force=False: d)
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        lamps.prepare(dict(DEVICE)).set_brightness_percentage(pct)

        assert b.sent[-1]["22"] == expected

    def test_fallback_path_produces_the_same_values(self, lamps, monkeypatch):
        b = fake_bulb({"dps": {"22": 500}})
        monkeypatch.setattr(lamps, "resolve", lambda d, devs=None, force=False: d)
        monkeypatch.setattr(lamps, "connect", lambda d: b)

        lamps.prepare(dict(DEVICE)).set_brightness_percentage(100)

        assert b.sent[-1]["22"] == 1000


OTHER = {"name": "Escritorio 1", "id": "eb9c93e1dc4a66815czniv",
         "key": "fedcba9876543210", "ip": "192.168.0.16", "version": 3.3}


class TestActiveLamp:
    """Since the October 2026 move there is one lamp; devices.json still lists the old eight."""

    @pytest.mark.parametrize("name", [None, "all", "*", "Sala_Luminaria", "escritorio", "nonsense"])
    def test_every_target_resolves_to_the_active_lamp(self, lamps, monkeypatch, name):
        monkeypatch.setattr(lamps, "ACTIVE_LAMP_ID", DEVICE["id"])

        assert lamps.targets([dict(DEVICE), dict(OTHER)], name) == [DEVICE]

    def test_a_name_that_is_not_the_active_lamp_is_reported(self, lamps, monkeypatch, capsys):
        monkeypatch.setattr(lamps, "ACTIVE_LAMP_ID", DEVICE["id"])

        lamps.targets([dict(DEVICE), dict(OTHER)], "Escritorio 1")

        err = capsys.readouterr().err
        assert "not the active lamp" in err and "Sala_Luminaria" in err

    def test_the_lamps_own_name_passes_without_a_warning(self, lamps, monkeypatch, capsys):
        monkeypatch.setattr(lamps, "ACTIVE_LAMP_ID", DEVICE["id"])

        lamps.targets([dict(DEVICE)], "luminaria")

        assert capsys.readouterr().err == ""

    def test_exits_when_the_active_lamp_is_missing_from_devices_json(self, lamps, monkeypatch):
        monkeypatch.setattr(lamps, "ACTIVE_LAMP_ID", "ebdeadbeefdeadbeefdead")

        with pytest.raises(SystemExit) as e:
            lamps.targets([dict(DEVICE)], None)

        assert "is not in" in str(e.value)

    def test_none_restores_matching_by_name(self, lamps, monkeypatch):
        monkeypatch.setattr(lamps, "ACTIVE_LAMP_ID", None)
        devs = [dict(DEVICE), dict(OTHER)]

        assert lamps.targets(devs, "escritorio") == [OTHER]
        assert lamps.targets(devs, "all") == devs

    @pytest.mark.parametrize("rest,expected", [
        (["30"], (None, "30")),
        (["abajur", "30"], ("abajur", "30")),
        (["#FF8800"], (None, "#FF8800")),
    ])
    def test_the_name_is_optional_before_a_value(self, lamps, rest, expected):
        assert lamps.split_target_value(rest, "bright") == expected

    def test_too_many_arguments_exit(self, lamps):
        with pytest.raises(SystemExit):
            lamps.split_target_value(["a", "b", "c"], "bright")


class TestPull:
    def test_an_empty_cloud_reply_does_not_wipe_the_local_keys(self, lamps, tmp_path, monkeypatch):
        """An expired IoT Core subscription returns [] rather than an error (2026-10-28)."""
        json.dump([dict(DEVICE)], open(lamps.DEVICES, "w"))
        monkeypatch.setattr(lamps, "CLOUD", str(tmp_path / "cloud.json"))
        json.dump({"apiRegion": "us", "apiKey": "k", "apiSecret": "s"}, open(lamps.CLOUD, "w"))

        class FakeCloud:
            def __init__(self, **kw): pass
            def getdevices(self, verbose=False): return []
            def cloudrequest(self, ep):
                return {"code": 28841002, "msg": "IoT Core service subscription has expired."}

        monkeypatch.setattr(lamps.tinytuya, "Cloud", FakeCloud)
        monkeypatch.setattr(lamps.tinytuya, "deviceScan",
                            lambda *a, **kw: pytest.fail("should not scan"))

        with pytest.raises(SystemExit) as e:
            lamps.cmd_pull([dict(DEVICE)], None)

        assert "28841002" in str(e.value)
        assert json.load(open(lamps.DEVICES))[0]["key"] == DEVICE["key"]


class TestResolve:
    def test_keeps_the_recorded_ip_when_reachable(self, lamps, monkeypatch):
        monkeypatch.setattr(lamps, "reachable", lambda ip: True)
        monkeypatch.setattr(lamps, "lan_scan", lambda: pytest.fail("should not scan"))

        assert lamps.resolve(dict(DEVICE))["ip"] == "192.168.0.12"

    def test_heals_a_stale_ip_from_the_scan_and_persists_it(self, lamps, monkeypatch, capsys):
        devs = [dict(DEVICE)]
        monkeypatch.setattr(lamps, "reachable", lambda ip: False)
        monkeypatch.setattr(lamps, "lan_scan",
                            lambda: {DEVICE["id"]: {"ip": "192.168.0.11", "version": "3.3"}})

        out = lamps.resolve(devs[0], devs)

        assert out["ip"] == "192.168.0.11"
        assert "stale address" in capsys.readouterr().err
        assert json.load(open(lamps.DEVICES))[0]["ip"] == "192.168.0.11"

    def test_leaves_the_device_alone_when_the_scan_misses_it(self, lamps, monkeypatch, capsys):
        monkeypatch.setattr(lamps, "reachable", lambda ip: False)
        monkeypatch.setattr(lamps, "lan_scan", lambda: {})

        out = lamps.resolve(dict(DEVICE))

        assert out["ip"] == "192.168.0.12"
        assert "powered off?" in capsys.readouterr().err
