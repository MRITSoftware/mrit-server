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
    create_device_in_db,
    process_remote_command_record,
    update_device_in_db,
    execute_remote_command_action,
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


def test_create_device_reuses_existing_site_id(monkeypatch):
    post_called = {"value": False}
    updated_payload = {}

    monkeypatch.setattr(
        'tuya_server.get_device_by_site_id_from_db',
        lambda site_id: {
            "id": "42",
            "tuya_device_id": "OLD123",
            "site_id": site_id,
        },
    )

    def fake_update_by_id(device_row_id, tuya_device_id=None, site_id=None, name=None, local_key=None, lan_ip=None, protocol_version=None):
        updated_payload.update({
            "device_row_id": device_row_id,
            "tuya_device_id": tuya_device_id,
            "site_id": site_id,
            "name": name,
            "local_key": local_key,
            "lan_ip": lan_ip,
            "protocol_version": protocol_version,
        })
        return True

    monkeypatch.setattr('tuya_server.update_device_by_id_in_db', fake_update_by_id)
    monkeypatch.setattr('tuya_server.requests.post', lambda *args, **kwargs: post_called.update(value=True))

    ok = create_device_in_db(
        tuya_device_id="NEW456",
        site_id="ACADEMIA_CENTRO",
        name="Academia Centro",
        local_key="local-key",
        lan_ip="192.168.0.10",
        protocol_version="3.3",
    )

    assert ok is True
    assert post_called["value"] is False
    assert updated_payload == {
        "device_row_id": "42",
        "tuya_device_id": "NEW456",
        "site_id": "ACADEMIA_CENTRO",
        "name": "Academia Centro",
        "local_key": "local-key",
        "lan_ip": "192.168.0.10",
        "protocol_version": "3.3",
    }


def test_remote_command_processes_pending_command(monkeypatch):
    updates = []
    deleted = []

    monkeypatch.setattr('tuya_server.SITE_NAME', 'ACADEMIA_CENTRO')
    monkeypatch.setattr('tuya_server.claim_remote_command', lambda command_id: True)
    monkeypatch.setattr(
        'tuya_server.execute_tuya_command_request',
        lambda payload: {"ok": True, "device": {"id": payload.get("tuya_device_id"), "ip": "", "version": ""}},
    )
    monkeypatch.setattr(
        'tuya_server.update_remote_command_status',
        lambda command_id, status, result=None, error_message=None: updates.append({
            "command_id": command_id,
            "status": status,
            "result": result,
            "error_message": error_message,
        }) or True
    )
    monkeypatch.setattr('tuya_server.delete_remote_command', lambda command_id: deleted.append(command_id) or True)

    process_remote_command_record({
        "id": 7,
        "site_id": "ACADEMIA_CENTRO",
        "status": "pending",
        "action": "on",
        "tuya_device_id": "DEV123",
    })

    assert updates == [{
        "command_id": 7,
        "status": "done",
        "result": {"ok": True, "device": {"id": "DEV123", "ip": "", "version": ""}},
        "error_message": None,
    }]
    assert deleted == [7]


def test_remote_test_action_runs_diagnostics(monkeypatch):
    monkeypatch.setattr(
        'tuya_server.resolve_device_context_for_test',
        lambda record: {
            "tuya_device_id": "DEV123",
            "resolution_source": "request",
            "local_key": "local-key",
            "lan_ip": "192.168.0.10",
            "version": 3.3,
            "cache_device": {"lan_ip": "192.168.0.10"},
            "db_device": {"tuya_device_id": "DEV123"},
        }
    )

    class TestDevice:
        def set_version(self, version):
            self.version = version

    monkeypatch.setattr('tinytuya.OutletDevice', lambda *args, **kwargs: TestDevice())
    monkeypatch.setattr('tuya_server.tuya_status_with_timeout', lambda device, timeout_seconds=0: {"dps": {"1": True}})

    result = execute_remote_command_action({
        "action": "test",
        "tuya_device_id": "DEV123",
    })

    assert result["action"] == "test"
    assert result["ok"] is True
    assert result["checks"]["device_ping_ok"] is True
    assert result["device"]["id"] == "DEV123"


def test_remote_test_overwrites_previous_saved_result(monkeypatch):
    saved = []
    deleted = []

    monkeypatch.setattr('tuya_server.SITE_NAME', 'ACADEMIA_CENTRO')
    monkeypatch.setattr('tuya_server.claim_remote_command', lambda command_id: True)
    monkeypatch.setattr(
        'tuya_server.execute_remote_command_action',
        lambda record: {"ok": True, "action": "test", "site": "ACADEMIA_CENTRO"}
    )
    monkeypatch.setattr(
        'tuya_server.find_existing_saved_test_command',
        lambda site_id, exclude_command_id: {"id": 3}
    )
    monkeypatch.setattr(
        'tuya_server.update_remote_command_status',
        lambda command_id, status, result=None, error_message=None: saved.append({
            "command_id": command_id,
            "status": status,
            "result": result,
            "error_message": error_message,
        }) or True
    )
    monkeypatch.setattr('tuya_server.delete_remote_command', lambda command_id: deleted.append(command_id) or True)

    process_remote_command_record({
        "id": 8,
        "site_id": "ACADEMIA_CENTRO",
        "status": "pending",
        "action": "test",
        "tuya_device_id": "DEV123",
    })

    assert saved == [{
        "command_id": 3,
        "status": "done",
        "result": {"ok": True, "action": "test", "site": "ACADEMIA_CENTRO"},
        "error_message": None,
    }]
    assert deleted == [8]


def test_update_device_in_db_always_sends_app_version(monkeypatch):
    captured = {}

    class DummyResponse:
        status_code = 200
        text = "[]"

        def raise_for_status(self):
            return None

        def json(self):
            return [{"ok": True}]

    def fake_patch(url, json=None, headers=None, timeout=None):
        captured["url"] = url
        captured["json"] = json
        return DummyResponse()

    monkeypatch.setattr('tuya_server.requests.patch', fake_patch)

    ok = update_device_in_db(
        tuya_device_id="DEV123",
        lan_ip="192.168.0.10",
    )

    assert ok is True
    assert captured["json"]["lan_ip"] == "192.168.0.10"
    assert captured["json"]["versao"] == "1.0"
