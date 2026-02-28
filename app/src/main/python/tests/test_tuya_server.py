import threading
import pytest

# Ensure the module can be imported regardless of current working directory
import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from tuya_server import (
    app,
    scan_devices,
    discover_tuya_ip,
    send_tuya_command,
    COMMAND_MAX_RETRIES,
    DEVICE_CACHE,
    DEVICE_CACHE_LOCK,
)

class DummyDevice:
    def __init__(self, device_id, ip, local_key, behavior):
        self.device_id = device_id
        self.ip = ip
        self.local_key = local_key
        self.behavior = behavior  # dict controlling responses
        self.version = None
        self.attempt = 0

    def set_version(self, v):
        self.version = v

    def status(self):
        # return whatever is configured or None/exception
        self.attempt += 1
        resp = self.behavior.get('status', {})
        if isinstance(resp, Exception):
            raise resp
        return resp

    def turn_on(self):
        self.attempt += 1
        resp = self.behavior.get('on', {})
        if isinstance(resp, Exception):
            raise resp
        return resp

    def turn_off(self):
        self.attempt += 1
        resp = self.behavior.get('off', {})
        if isinstance(resp, Exception):
            raise resp
        return resp


@pytest.fixture(autouse=True)
def clear_cache():
    # Ensure cache is empty before each test
    with DEVICE_CACHE_LOCK:
        DEVICE_CACHE.clear()
    yield


def test_scan_devices_success(monkeypatch):
    sample = {
        "192.168.0.5": {"gwId": "abc123", "version": "3.3"},
        "192.168.0.6": {"gwId": "xyz789", "ver": "3.1"},
    }

    monkeypatch.setattr('tinytuya.deviceScan', lambda: sample)
    found = scan_devices()
    assert "abc123" in found
    assert found["abc123"]["ip"] == "192.168.0.5"
    assert found["xyz789"]["version"] == "3.1"


def test_scan_devices_timeout(monkeypatch):
    def raise_exc():
        raise RuntimeError("network error")
    monkeypatch.setattr('tinytuya.deviceScan', raise_exc)
    found = scan_devices()
    assert found == {}


def test_discover_ip_caching(monkeypatch):
    sample = {"10.0.0.2": {"gwId": "ID1", "ip": "10.0.0.2"}}
    monkeypatch.setattr('tinytuya.deviceScan', lambda: sample)

    ip = discover_tuya_ip("ID1")
    assert ip == "10.0.0.2"
    # second call should hit cache and not call deviceScan again
    monkeypatch.setattr('tinytuya.deviceScan', lambda: {})
    ip2 = discover_tuya_ip("ID1")
    assert ip2 == "10.0.0.2"


def test_send_command_success(monkeypatch):
    # dummy behavior where status returns a dict and turn_on returns a dict
    behavior = {'status': {'dps': {'1': True}}, 'on': {'result': 'ok'}}
    # fixture for OutletDevice constructor
    def fake_outlet(id, ip, key):
        return DummyDevice(id, ip, key, behavior)
    monkeypatch.setattr('tinytuya.OutletDevice', fake_outlet)

    # with explicit lan_ip
    send_tuya_command('on', 'ID123', 'localkey', '192.168.1.10', version=3.3)


def test_send_command_autodiscover_and_retry(monkeypatch):
    # first status call will return None (timeout), second will succeed
    class StatefulDevice(DummyDevice):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.call_count = 0

        def status(self):
            self.call_count += 1
            if self.call_count == 1:
                return None
            return {'dps': {'1': False}}

        def turn_on(self):
            return {'result': 'ok'}

    devices = {'status': None, 'on': {'result': 'ok'}}

    def fake_outlet(id, ip, key):
        return StatefulDevice(id, ip, key, {})

    monkeypatch.setattr('tinytuya.OutletDevice', fake_outlet)
    # also patch discover_tuya_ip to return a different IP when called
    monkeypatch.setattr('tuya_server.discover_tuya_ip', lambda dev: '192.168.1.20')

    # Calling with lan_ip=None triggers autodiscover
    send_tuya_command('on', 'ID123', 'localkey', None)
    # after success cache should have entry because ip resolved
    with DEVICE_CACHE_LOCK:
        assert DEVICE_CACHE.get('ID123') == '192.168.1.20'


def test_send_command_failure_clears_cache(monkeypatch):
    # simulate device that always raises error
    class BadDevice(DummyDevice):
        def status(self):
            raise RuntimeError("no response")
        def turn_on(self):
            raise RuntimeError("no response")

    monkeypatch.setattr('tinytuya.OutletDevice', lambda *args, **kwargs: BadDevice(args[0], args[1], args[2], {}))
    # put something in cache to be cleared
    with DEVICE_CACHE_LOCK:
        DEVICE_CACHE['ID123'] = '1.2.3.4'

    with pytest.raises(RuntimeError):
        send_tuya_command('on', 'ID123', 'localkey', '1.2.3.4')

    with DEVICE_CACHE_LOCK:
        assert 'ID123' not in DEVICE_CACHE


def test_send_command_retries_up_to_configured_limit(monkeypatch):
    call_count = {"turn_on": 0}

    class BadDevice(DummyDevice):
        def status(self):
            return {"dps": {"1": True}}

        def turn_on(self):
            call_count["turn_on"] += 1
            raise RuntimeError("temporary failure")

    monkeypatch.setattr('tinytuya.OutletDevice', lambda *args, **kwargs: BadDevice(args[0], args[1], args[2], {}))
    monkeypatch.setattr('tuya_server.discover_tuya_ip', lambda dev: '1.2.3.4')
    monkeypatch.setattr('time.sleep', lambda _: None)

    with pytest.raises(RuntimeError) as exc_info:
        send_tuya_command('on', 'ID123', 'localkey', '1.2.3.4')

    assert str(exc_info.value).startswith("Erro ao enviar comando para dispositivo")
    assert call_count["turn_on"] == COMMAND_MAX_RETRIES


def test_api_command_runtime_error_returns_503(monkeypatch):
    monkeypatch.setattr('tuya_server.send_tuya_command', lambda **kwargs: (_ for _ in ()).throw(RuntimeError("device unreachable")))
    client = app.test_client()

    response = client.post(
        "/tuya/command",
        json={
            "action": "on",
            "tuya_device_id": "ID123",
            "local_key": "localkey",
            "lan_ip": "192.168.1.10",
            "version": 3.3,
        },
    )

    payload = response.get_json()
    assert response.status_code == 503
    assert payload["ok"] is False
    assert payload["retriable"] is True
