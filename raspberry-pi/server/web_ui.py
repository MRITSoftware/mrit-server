#!/usr/bin/env python3
"""MRIT Server - Interface Web de Configuracao"""

import os, json, hashlib, subprocess, threading, time, shutil
from functools import wraps
from flask import Flask, render_template, request, redirect, url_for, session, jsonify

try:
    import requests as _req
except ImportError:
    _req = None

app = Flask(__name__, template_folder="templates")
app.secret_key = os.urandom(32)

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "config.json")
API = "http://127.0.0.1:8000"
_HASH = hashlib.sha256("MRITSERVER#REDEGELAFIT".encode()).hexdigest()
_LOGIN_FAILS: dict = {}  # ip -> [timestamps]


def _rate_limited(ip: str) -> bool:
    now = time.time()
    _LOGIN_FAILS[ip] = [t for t in _LOGIN_FAILS.get(ip, []) if now - t < 300]
    return len(_LOGIN_FAILS.get(ip, [])) >= 5

def _record_fail(ip: str) -> None:
    _LOGIN_FAILS.setdefault(ip, []).append(time.time())


# ── helpers ──────────────────────────────────────────────────────────────────

def cfg_load():
    try:
        return json.load(open(CONFIG_PATH, encoding="utf-8"))
    except Exception:
        return {}

def cfg_save(c):
    json.dump(c, open(CONFIG_PATH, "w", encoding="utf-8"), indent=4, ensure_ascii=False)

def api_call(method, path, **kw):
    if not _req:
        return None
    try:
        return getattr(_req, method)(f"{API}{path}", timeout=10, **kw).json()
    except Exception:
        return None

def run(cmd, timeout=30):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr

def login_required(f):
    @wraps(f)
    def g(*a, **kw):
        if not session.get("ok"):
            return redirect(url_for("login"))
        return f(*a, **kw)
    return g


# ── auth ─────────────────────────────────────────────────────────────────────

@app.route("/", methods=["GET", "POST"])
def login():
    err = None
    ip = request.remote_addr or "unknown"
    if request.method == "POST":
        if _rate_limited(ip):
            err = "Muitas tentativas incorretas. Aguarde 5 minutos."
        else:
            pwd = request.form.get("password", "")
            if hashlib.sha256(pwd.encode()).hexdigest() == _HASH:
                session["ok"] = True
                return redirect(url_for("dashboard"))
            _record_fail(ip)
            err = "Senha incorreta"
    return render_template("login.html", error=err)

@app.route("/sair")
def logout():
    session.clear()
    return redirect(url_for("login"))


# ── dashboard ────────────────────────────────────────────────────────────────

@app.route("/painel")
@login_required
def dashboard():
    cfg = cfg_load()
    wifi = _wifi_status()
    server_ok = api_call("post", "/health") is not None
    return render_template("dashboard.html", cfg=cfg, wifi=wifi, server_ok=server_ok)


# ── config ───────────────────────────────────────────────────────────────────

@app.route("/config/site", methods=["POST"])
@login_required
def set_site():
    site = request.form.get("site_id", "").strip()
    if site:
        c = cfg_load()
        c["site_name"] = site
        cfg_save(c)
        subprocess.Popen(["sudo", "systemctl", "restart", "mrit-server"])
    return redirect("/painel#config")


# ── OTA update ───────────────────────────────────────────────────────────────

@app.route("/ota/atualizar", methods=["POST"])
@login_required
def ota_update():
    def _do():
        time.sleep(1)
        try:
            r = subprocess.run(
                ["git", "-C", BASE_DIR, "pull", "--ff-only"],
                capture_output=True, text=True, timeout=60
            )
            if r.returncode == 0:
                src = os.path.join(BASE_DIR, "raspberry-pi", "server")
                if os.path.isdir(src):
                    for f in ["tuya_server.py", "web_ui.py"]:
                        s = os.path.join(src, f)
                        if os.path.exists(s):
                            shutil.copy2(s, BASE_DIR)
                    tpl_s = os.path.join(src, "templates")
                    tpl_d = os.path.join(BASE_DIR, "templates")
                    if os.path.isdir(tpl_s):
                        shutil.copytree(tpl_s, tpl_d, dirs_exist_ok=True)
            subprocess.run(["sudo", "systemctl", "restart", "mrit-server"],
                           capture_output=True, timeout=15)
            time.sleep(2)
            subprocess.run(["sudo", "systemctl", "restart", "mrit-webui"],
                           capture_output=True, timeout=15)
        except Exception:
            pass
    threading.Thread(target=_do, daemon=True).start()
    return jsonify({"ok": True, "msg": "Atualização iniciada. O painel reiniciará em instantes."})


# ── logs ─────────────────────────────────────────────────────────────────────

@app.route("/logs/<service>")
@login_required
def view_logs(service):
    if service not in ("mrit-server", "mrit-webui"):
        return jsonify({"ok": False, "erro": "Serviço inválido"})
    _, out, err = run(
        ["journalctl", "-u", service, "-n", "150", "--no-pager", "--output=short-iso"],
        timeout=15
    )
    return jsonify({"ok": True, "logs": out or err})


# ── wifi ─────────────────────────────────────────────────────────────────────

@app.route("/wifi/redes")
@login_required
def wifi_scan():
    _, out, _ = run(
        ["nmcli", "-t", "-f", "SSID,SIGNAL,SECURITY",
         "device", "wifi", "list", "--rescan", "yes"],
        timeout=25
    )
    nets, seen = [], set()
    for line in out.strip().split("\n"):
        p = line.split(":")
        if p and p[0] and p[0] not in seen:
            seen.add(p[0])
            nets.append({
                "ssid": p[0],
                "signal": int(p[1]) if len(p) > 1 and p[1].isdigit() else 0,
                "security": p[2] if len(p) > 2 else ""
            })
    return jsonify({"ok": True, "redes": sorted(nets, key=lambda x: -x["signal"])})

@app.route("/wifi/conectar", methods=["POST"])
@login_required
def wifi_connect():
    d = request.get_json() or {}
    ssid = d.get("ssid", "").strip()
    senha = d.get("senha", "").strip()
    if not ssid:
        return jsonify({"ok": False, "erro": "SSID obrigatorio"})
    # Remove perfil antigo para evitar conflito de configuracao
    subprocess.run(["sudo", "nmcli", "connection", "delete", ssid],
                   capture_output=True, text=True, timeout=10)
    cmd = ["sudo", "nmcli", "device", "wifi", "connect", ssid, "ifname", "wlan0"]
    if senha:
        cmd += ["password", senha]
    rc, out, err = run(cmd, timeout=45)
    if rc == 0:
        return jsonify({"ok": True, "msg": f"Conectado a {ssid}"})
    return jsonify({"ok": False, "erro": (err or out).strip()})

@app.route("/wifi/status")
@login_required
def wifi_status_route():
    return jsonify(_wifi_status())

def _wifi_status():
    try:
        _, out, _ = run(["nmcli", "-t", "-f", "DEVICE,STATE,CONNECTION", "device"])
        for line in out.strip().split("\n"):
            p = line.split(":")
            if p and p[0] == "wlan0":
                c = len(p) > 1 and p[1] == "connected"
                return {"conectado": c, "rede": p[2] if c and len(p) > 2 else None}
    except Exception:
        pass
    return {"conectado": False, "rede": None}


# ── dispositivos ─────────────────────────────────────────────────────────────

@app.route("/dispositivos/buscar")
@login_required
def dev_discover():
    if not _req:
        return jsonify({"ok": False, "erro": "requests nao disponivel"})
    try:
        r = _req.get(f"{API}/tuya/devices", timeout=35)
        return jsonify(r.json())
    except Exception as e:
        return jsonify({"ok": False, "erro": f"Servidor offline: {e}"})

@app.route("/dispositivos/sync", methods=["POST"])
@login_required
def dev_sync():
    d = api_call("post", "/tuya/sync", json=request.get_json())
    return jsonify(d or {"ok": False, "erro": "Servidor principal offline"})


# ── run ──────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=80, debug=False)
