#!/usr/bin/env python3

import os
import json
import traceback
import threading
from typing import Optional, Dict, Any, List

from flask import Flask, request, jsonify
import tinytuya

# Usar requests para chamadas HTTP diretas ao Supabase
# Isso evita dependências problemáticas como pydantic-core
try:
    import requests
    REQUESTS_AVAILABLE = True
except ImportError:
    REQUESTS_AVAILABLE = False
    # log será definido depois, então apenas print aqui
    print("[WARN] requests não disponível - funcionalidades de banco desabilitadas")

# Tuya Connector para buscar local_key da API Tuya
try:
    from tuya_connector import TuyaOpenAPI
    TUYA_CONNECTOR_AVAILABLE = True
except ImportError:
    TUYA_CONNECTOR_AVAILABLE = False
    print("[WARN] tuya-connector-python não disponível - busca de local_key desabilitada")

# =========================
# CONFIG & AUTO-SETUP
# =========================

# No Android, usar o diretório de dados do app
try:
    from android.storage import app_storage_path
    BASE_DIR = app_storage_path()
except ImportError:
    # Fallback se não estiver no Android
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

CONFIG_PATH = os.path.join(BASE_DIR, "config.json")

def create_config_if_needed():
    """Cria o config.json com nome do site/tablet."""
    if not os.path.exists(CONFIG_PATH):
        # Nome padrão - será atualizado pelo Kotlin via update_site_name()
        site = "ANDROID_DEVICE"
        
        cfg = {
            "site_name": site,
            "supabase": {
                "url": "",
                "anon_key": ""
            },
            "tuya_accounts": []
        }
        
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=4, ensure_ascii=False)
        
        print(f"[OK] config.json criado com site_name = {site}")

def update_site_name(new_name: str):
    """Atualiza o nome do site no config.json"""
    # Carregar config existente
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    else:
        cfg = {}
    
    cfg["site_name"] = new_name
    
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=4, ensure_ascii=False)
    
    global SITE_NAME
    SITE_NAME = new_name
    print(f"[OK] site_name atualizado para = {new_name}")

def update_supabase_config(url: str, anon_key: str):
    """Atualiza a configuração do Supabase no config.json"""
    # Carregar config existente
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    else:
        cfg = {}
    
    cfg["supabase"] = {
        "url": url,
        "anon_key": anon_key
    }
    
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=4, ensure_ascii=False)
    
    # Atualizar variável global
    global SUPABASE_CONFIG
    SUPABASE_CONFIG = cfg["supabase"]
    log(f"[OK] Configuração do Supabase atualizada")

def update_tuya_accounts(accounts: List[Dict[str, str]]):
    """Atualiza as contas Tuya no config.json"""
    # Carregar config existente
    if os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    else:
        cfg = {}
    
    cfg["tuya_accounts"] = accounts
    
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=4, ensure_ascii=False)
    
    # Atualizar variável global
    global TUYA_ACCOUNTS
    TUYA_ACCOUNTS = accounts
    log(f"[OK] Configuração de contas Tuya atualizada: {len(accounts)} conta(s)")

# cria se não existir
create_config_if_needed()

# carrega o config
if os.path.exists(CONFIG_PATH):
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    SITE_NAME: str = cfg.get("site_name", "SITE_DESCONHECIDO")
    SUPABASE_CONFIG = cfg.get("supabase", {})
    TUYA_ACCOUNTS = cfg.get("tuya_accounts", [])
else:
    SITE_NAME = "SITE_DESCONHECIDO"
    SUPABASE_CONFIG = {}
    TUYA_ACCOUNTS = []

# Garantir que SUPABASE_CONFIG tem a estrutura correta
if not isinstance(SUPABASE_CONFIG, dict):
    SUPABASE_CONFIG = {}

# Garantir que TUYA_ACCOUNTS é uma lista
if not isinstance(TUYA_ACCOUNTS, list):
    TUYA_ACCOUNTS = []

# Configurar Supabase automaticamente se as credenciais padrão estiverem disponíveis
# (pode ser configurado via variáveis de ambiente ou hardcoded para desenvolvimento)
DEFAULT_SUPABASE_URL = "https://kihyhoqbrkwbfudttevo.supabase.co"
DEFAULT_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtpaHlob3Ficmt3YmZ1ZHR0ZXZvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTU1NTUwMjcsImV4cCI6MjAzMTEzMTAyN30.XtBTlSiqhsuUIKmhAMEyxofV-dRst7240n912m4O4Us"

# Definir função log antes de usar
def log(msg: str) -> None:
    print(msg, flush=True)

# Se não há configuração, usar as credenciais padrão
if not SUPABASE_CONFIG.get("url") or not SUPABASE_CONFIG.get("anon_key"):
    try:
        update_supabase_config(DEFAULT_SUPABASE_URL, DEFAULT_SUPABASE_ANON_KEY)
        log(f"[INFO] Supabase configurado automaticamente: URL={DEFAULT_SUPABASE_URL}")
    except Exception as e:
        log(f"[WARN] Não foi possível configurar Supabase automaticamente: {e}")

# Configurar contas Tuya padrão se não houver configuração
# As credenciais podem ser configuradas via endpoint /config/tuya ou diretamente no config.json
DEFAULT_TUYA_ACCOUNTS = [
    {
        "access_id": "td7tp3cvq3nrc35emwg3",
        "access_key": "bbcdaa3dfe9545fca4326fcfa1cf3e2c",
        "endpoint": "https://openapi.tuyaus.com",
        "uid": "az1715569264750N2mUr"
    },
    {
        "access_id": "wwxsqj37wnfdnp98wu54",
        "access_key": "d7a140221f3b4e8f916601af4fbd6816",
        "endpoint": "https://openapi.tuyaus.com",
        "uid": "az1759235287550HcJRz"
    }
]

if not TUYA_ACCOUNTS:
    try:
        update_tuya_accounts(DEFAULT_TUYA_ACCOUNTS)
        log(f"[INFO] Contas Tuya configuradas automaticamente: {len(DEFAULT_TUYA_ACCOUNTS)} conta(s)")
    except Exception as e:
        log(f"[WARN] Não foi possível configurar contas Tuya automaticamente: {e}")

print(f"[INFO] Servidor local iniciado para SITE = {SITE_NAME}")

# =========================
# DATABASE (SUPABASE)
# =========================

def get_supabase_headers():
    """Retorna headers para requisições ao Supabase."""
    if not REQUESTS_AVAILABLE:
        raise RuntimeError("requests não está disponível")
    
    url = SUPABASE_CONFIG.get("url")
    anon_key = SUPABASE_CONFIG.get("anon_key")
    
    if not url or not anon_key:
        raise RuntimeError("Configuração do Supabase não encontrada (url ou anon_key faltando)")
    
    return {
        "apikey": anon_key,
        "Authorization": f"Bearer {anon_key}",
        "Content-Type": "application/json",
        "Prefer": "return=representation"
    }

def get_supabase_url():
    """Retorna a URL base do Supabase."""
    url = SUPABASE_CONFIG.get("url")
    if not url:
        raise RuntimeError("URL do Supabase não configurada")
    # Garantir que a URL termina com /rest/v1
    # Remover barra final se existir
    url = url.rstrip("/")
    return f"{url}/rest/v1"

def get_devices_from_db(tuya_device_ids: List[str]) -> Dict[str, Dict]:
    """
    Busca devices da tabela tuya_devices pelos tuya_device_id.
    Retorna um dict onde a chave é tuya_device_id e o valor é um dict com os dados.
    """
    if not REQUESTS_AVAILABLE or not SUPABASE_CONFIG.get("url"):
        return {}
    
    if not tuya_device_ids:
        return {}
    
    try:
        base_url = get_supabase_url()
        headers = get_supabase_headers()
        
        # Construir query para buscar múltiplos tuya_device_id
        # Supabase PostgREST usa formato: tuya_device_id=in.(id1,id2,id3)
        # URL encode os IDs para evitar problemas com caracteres especiais
        ids_param = ",".join(tuya_device_ids)
        url = f"{base_url}/tuya_devices?tuya_device_id=in.({ids_param})&select=*"
        
        response = requests.get(url, headers=headers, timeout=10)
        response.raise_for_status()
        
        data = response.json()
        result = {}
        for row in data:
            tuya_id = row.get('tuya_device_id')
            if tuya_id:
                result[tuya_id] = {
                    'id': str(row.get('id', '')),
                    'site_id': row.get('site_id'),
                    'tuya_device_id': tuya_id,
                    'name': row.get('name'),
                    'local_key': row.get('local_key'),
                    'lan_ip': row.get('lan_ip'),
                    'protocol_version': row.get('protocol_version')
                }
        
        log(f"[DB] Encontrados {len(result)} devices no banco")
        return result
        
    except Exception as e:
        log(f"[DB] Erro ao buscar devices: {e}")
        traceback.print_exc()
        return {}

def create_device_in_db(
    tuya_device_id: str,
    site_id: str,
    name: Optional[str] = None,
    local_key: Optional[str] = None,
    lan_ip: Optional[str] = None,
    protocol_version: Optional[str] = None
) -> bool:
    """
    Cria um novo device na tabela tuya_devices.
    """
    if not REQUESTS_AVAILABLE:
        log("[DB] requests não está disponível")
        return False
    
    if not SUPABASE_CONFIG.get("url") or not SUPABASE_CONFIG.get("anon_key"):
        log(f"[DB] Configuração do Supabase não encontrada. URL: {SUPABASE_CONFIG.get('url')}, Key: {'presente' if SUPABASE_CONFIG.get('anon_key') else 'ausente'}")
        return False
    
    try:
        base_url = get_supabase_url()
        headers = get_supabase_headers()
        
        # Construir dict com dados do novo device
        device_data = {
            'tuya_device_id': tuya_device_id,
            'site_id': site_id
        }
        
        if name is not None:
            device_data['name'] = name
        
        if local_key is not None:
            device_data['local_key'] = local_key
        
        if lan_ip is not None:
            device_data['lan_ip'] = lan_ip
        
        if protocol_version is not None:
            device_data['protocol_version'] = protocol_version
        
        # Criar usando Supabase REST API
        url = f"{base_url}/tuya_devices"
        
        log(f"[DB] Tentando criar device {tuya_device_id}")
        log(f"[DB] URL: {url}")
        log(f"[DB] Dados: {device_data}")
        
        response = requests.post(url, json=device_data, headers=headers, timeout=10)
        
        log(f"[DB] Status code: {response.status_code}")
        log(f"[DB] Response: {response.text[:200]}")  # Primeiros 200 caracteres
        
        response.raise_for_status()
        
        data = response.json()
        if data and len(data) > 0:
            log(f"[DB] Device {tuya_device_id} criado com sucesso: {data}")
            return True
        else:
            log(f"[DB] Resposta vazia ao criar device {tuya_device_id}")
            return False
        
    except requests.exceptions.HTTPError as e:
        log(f"[DB] Erro HTTP ao criar device {tuya_device_id}: {e}")
        log(f"[DB] Response: {e.response.text if hasattr(e, 'response') else 'N/A'}")
        traceback.print_exc()
        return False
    except Exception as e:
        log(f"[DB] Erro ao criar device {tuya_device_id}: {e}")
        traceback.print_exc()
        return False

def update_device_heartbeat(tuya_device_id: str) -> bool:
    """
    Atualiza apenas o campo updated_at de um device (heartbeat).
    Faz um PATCH com o site_id atual para forçar atualização do timestamp.
    """
    if not REQUESTS_AVAILABLE:
        log("[DB] requests não está disponível")
        return False
    
    if not SUPABASE_CONFIG.get("url") or not SUPABASE_CONFIG.get("anon_key"):
        log(f"[DB] Configuração do Supabase não encontrada. URL: {SUPABASE_CONFIG.get('url')}, Key: {'presente' if SUPABASE_CONFIG.get('anon_key') else 'ausente'}")
        return False
    
    try:
        base_url = get_supabase_url()
        headers = get_supabase_headers()
        
        # Primeiro, buscar o device para pegar o site_id atual
        url_get = f"{base_url}/tuya_devices?tuya_device_id=eq.{tuya_device_id}&select=site_id"
        response_get = requests.get(url_get, headers=headers, timeout=10)
        response_get.raise_for_status()
        
        devices = response_get.json()
        if not devices or len(devices) == 0:
            log(f"[HEARTBEAT] Device {tuya_device_id} não encontrado no banco")
            return False
        
        # Pegar o site_id atual (ou usar SITE_NAME como fallback)
        current_site_id = devices[0].get("site_id") or SITE_NAME
        
        # Fazer PATCH com o site_id atual para forçar atualização do updated_at
        # O Supabase atualiza automaticamente o updated_at quando há um UPDATE
        update_data = {"site_id": current_site_id}
        
        # Atualizar usando Supabase REST API
        url = f"{base_url}/tuya_devices?tuya_device_id=eq.{tuya_device_id}"
        
        log(f"[HEARTBEAT] Atualizando heartbeat para device {tuya_device_id}")
        
        # Usar PATCH com Prefer: return=minimal para não retornar dados
        headers_with_prefer = {**headers, "Prefer": "return=minimal"}
        response = requests.patch(url, json=update_data, headers=headers_with_prefer, timeout=10)
        
        response.raise_for_status()
        
        log(f"[HEARTBEAT] Heartbeat atualizado com sucesso para device {tuya_device_id}")
        return True
        
    except requests.exceptions.HTTPError as e:
        # Se o device não existir, não é erro crítico para heartbeat
        if e.response.status_code == 404 or (hasattr(e, 'response') and e.response.status_code == 406):
            log(f"[HEARTBEAT] Device {tuya_device_id} não encontrado no banco")
        else:
            log(f"[HEARTBEAT] Erro HTTP ao atualizar heartbeat para device {tuya_device_id}: {e}")
            log(f"[HEARTBEAT] Response: {e.response.text if hasattr(e, 'response') else 'N/A'}")
        return False
    except Exception as e:
        log(f"[HEARTBEAT] Erro ao atualizar heartbeat para device {tuya_device_id}: {e}")
        traceback.print_exc()
        return False

def update_device_in_db(
    tuya_device_id: str,
    site_id: Optional[str] = None,
    name: Optional[str] = None,
    local_key: Optional[str] = None,
    lan_ip: Optional[str] = None,
    protocol_version: Optional[str] = None
) -> bool:
    """
    Atualiza um device na tabela tuya_devices.
    Apenas atualiza os campos que foram fornecidos (não None).
    """
    if not REQUESTS_AVAILABLE:
        log("[DB] requests não está disponível")
        return False
    
    if not SUPABASE_CONFIG.get("url") or not SUPABASE_CONFIG.get("anon_key"):
        log(f"[DB] Configuração do Supabase não encontrada. URL: {SUPABASE_CONFIG.get('url')}, Key: {'presente' if SUPABASE_CONFIG.get('anon_key') else 'ausente'}")
        return False
    
    try:
        base_url = get_supabase_url()
        headers = get_supabase_headers()
        
        # Construir dict com apenas os campos que foram fornecidos
        update_data = {}
        
        if site_id is not None:
            update_data['site_id'] = site_id
        
        if name is not None:
            update_data['name'] = name
        
        if local_key is not None:
            update_data['local_key'] = local_key
        
        if lan_ip is not None:
            update_data['lan_ip'] = lan_ip
        
        if protocol_version is not None:
            update_data['protocol_version'] = protocol_version
        
        # updated_at será atualizado automaticamente pelo banco (default now())
        
        if not update_data:
            log(f"[DB] Nenhum dado para atualizar para device {tuya_device_id}")
            return False
        
        # Atualizar usando Supabase REST API
        # Supabase usa formato: /rest/v1/tuya_devices?tuya_device_id=eq.{id}
        url = f"{base_url}/tuya_devices?tuya_device_id=eq.{tuya_device_id}"
        
        log(f"[DB] Tentando atualizar device {tuya_device_id}")
        log(f"[DB] URL: {url}")
        log(f"[DB] Dados: {update_data}")
        
        response = requests.patch(url, json=update_data, headers=headers, timeout=10)
        
        log(f"[DB] Status code: {response.status_code}")
        log(f"[DB] Response: {response.text[:200]}")  # Primeiros 200 caracteres
        
        response.raise_for_status()
        
        data = response.json()
        if data and len(data) > 0:
            log(f"[DB] Device {tuya_device_id} atualizado com sucesso: {data}")
            return True
        else:
            log(f"[DB] Nenhum device encontrado com tuya_device_id = {tuya_device_id}")
            return False
        
    except requests.exceptions.HTTPError as e:
        log(f"[DB] Erro HTTP ao atualizar device {tuya_device_id}: {e}")
        log(f"[DB] Response: {e.response.text if hasattr(e, 'response') else 'N/A'}")
        traceback.print_exc()
        return False
    except Exception as e:
        log(f"[DB] Erro ao atualizar device {tuya_device_id}: {e}")
        traceback.print_exc()
        return False

# =========================
# DISCOVERY / CACHE DE IP
# =========================

DEVICE_CACHE: Dict[str, str] = {}

def scan_and_print_devices() -> None:
    """Faz um scan na rede e imprime todos os dispositivos Tuya encontrados."""
    log("[SCAN] Iniciando scan de dispositivos Tuya na rede...")
    
    try:
        # Usar timeout para evitar travamentos
        devices = scan_with_timeout(30)  # 30 segundos de timeout
        
        if devices is None:
            log("[SCAN] Timeout ou erro ao escanear dispositivos")
            return
        
        if not isinstance(devices, dict):
            log(f"[SCAN] Resultado inesperado de deviceScan(): {type(devices)}")
            return
        
        if not devices:
            log("[SCAN] Nenhum dispositivo Tuya encontrado.")
            return
        
        log(f"[SCAN] {len(devices)} dispositivo(s) encontrado(s):")
        for ip, dev in devices.items():
            gwid = dev.get("gwId")
            ver = dev.get("version") or dev.get("ver")
            log(f"[SCAN] gwId={gwid}  ip={ip}  ver={ver}")
    
    except Exception as e:
        log(f"[SCAN] Erro ao escanear dispositivos Tuya: {e}")
        traceback.print_exc()

def scan_devices() -> Dict[str, Any]:
    """Faz um scan na rede e retorna todos os dispositivos Tuya encontrados em formato dict."""
    log("[SCAN] Iniciando scan de dispositivos Tuya na rede...")
    discovered_devices = {}
    
    try:
        # Usar timeout para evitar travamentos
        devices = scan_with_timeout(30)  # 30 segundos de timeout
        
        if devices is None:
            log("[SCAN] Timeout ou erro ao escanear dispositivos")
            return {}
        
        if not isinstance(devices, dict):
            log(f"[SCAN] Resultado inesperado de deviceScan(): {type(devices)}")
            return {}
        
        if not devices:
            log("[SCAN] Nenhum dispositivo Tuya encontrado.")
            return {}
        
        log(f"[SCAN] {len(devices)} dispositivo(s) encontrado(s):")
        for ip, dev in devices.items():
            gwid = dev.get("gwId")
            ver = dev.get("version") or dev.get("ver")
            log(f"[SCAN] gwId={gwid}  ip={ip}  ver={ver}")
            
            if gwid:
                discovered_devices[gwid] = {
                    "id": gwid,
                    "ip": ip,
                    "version": ver
                }
    
    except Exception as e:
        log(f"[SCAN] Erro ao escanear dispositivos Tuya: {e}")
        traceback.print_exc()
    
    return discovered_devices

def scan_with_timeout(timeout_seconds: int = 30) -> Optional[Dict]:
    """Executa deviceScan com timeout para evitar travamentos."""
    result = [None]
    exception = [None]
    
    def scan_thread():
        try:
            result[0] = tinytuya.deviceScan()
        except Exception as e:
            exception[0] = e
    
    thread = threading.Thread(target=scan_thread, daemon=True)
    thread.start()
    thread.join(timeout=timeout_seconds)
    
    if thread.is_alive():
        log(f"[SCAN] Timeout após {timeout_seconds} segundos")
        return None
    
    if exception[0]:
        log(f"[SCAN] Exceção durante scan: {exception[0]}")
        return None
    
    return result[0]

def discover_tuya_ip(tuya_device_id: str) -> Optional[str]:
    """
    Tenta descobrir o IP LAN de um dispositivo Tuya pelo gwId (device_id),
    usando tinytuya.deviceScan() e guarda em cache.
    """
    # se já descobrimos antes, usa o cache
    if tuya_device_id in DEVICE_CACHE:
        ip_cached = DEVICE_CACHE[tuya_device_id]
        log(f"[DISCOVER] Usando IP em cache para {tuya_device_id}: {ip_cached}")
        return ip_cached
    
    log(f"[DISCOVER] Varrendo a rede para encontrar o device_id = {tuya_device_id} ...")
    
    try:
        # Usar timeout para evitar travamentos
        devices = scan_with_timeout(30)  # 30 segundos de timeout
        
        if devices is None:
            log(f"[DISCOVER] Timeout ou erro ao escanear dispositivos")
            return None
        
        if not isinstance(devices, dict):
            log(f"[DISCOVER] Resultado inesperado de deviceScan(): {type(devices)}")
            return None
        
        log(f"[DISCOVER] deviceScan encontrou {len(devices)} dispositivo(s).")
        
        for ip, dev in devices.items():
            gwid = dev.get("gwId")
            dev_ip = dev.get("ip", ip)
            log(f"[DISCOVER] Achado gwId={gwid} ip={dev_ip}")
            if gwid == tuya_device_id:
                log(f"[DISCOVER] Encontrado! device_id={gwid} ip={dev_ip}")
                DEVICE_CACHE[tuya_device_id] = dev_ip
                return dev_ip
        
        log(f"[DISCOVER] Nenhum dispositivo encontrado com device_id = {tuya_device_id}")
        return None
    
    except Exception as e:
        log(f"[DISCOVER] Erro ao escanear dispositivos Tuya: {e}")
        traceback.print_exc()
        return None

# =========================
# TUYA
# =========================

def send_tuya_command(
    action: str,
    tuya_device_id: str,
    local_key: str,
    lan_ip: Optional[str],
    version: Optional[float] = None
) -> None:
    
    if not tuya_device_id:
        raise RuntimeError("Campo tuya_device_id é obrigatório")
    if not local_key:
        raise RuntimeError("Campo local_key é obrigatório")
    
    # Se não veio IP ou veio "auto", tenta descobrir
    if not lan_ip or str(lan_ip).lower() == "auto":
        log(f"[INFO] Nenhum lan_ip informado (ou 'auto'). Tentando descobrir IP do device {tuya_device_id}...")
        lan_ip = discover_tuya_ip(tuya_device_id)
        if not lan_ip:
            raise RuntimeError("Não foi possível descobrir o IP LAN do dispositivo Tuya.")
    
    # Garante que venha só IP, nada de 'http://'
    lan_ip = str(lan_ip).strip()
    if lan_ip.startswith("http://") or lan_ip.startswith("https://"):
        raise RuntimeError("lan_ip deve ser apenas o IP (ex: 192.168.0.50), sem http:// e sem porta.")
    
    # Se não veio version ou veio vazio, usa 3.3 como padrão
    if version is None or version == "":
        version = 3.3
    
    log(f"[INFO] [{SITE_NAME}] Enviando '{action}' → {tuya_device_id} @ {lan_ip} (versão {version})")
    
    try:
        d = tinytuya.OutletDevice(tuya_device_id, lan_ip, local_key)
        
        # Usa a versão recebida do JSON ou 3.3 como padrão
        d.set_version(version)
        
        if action == "on":
            resp = d.turn_on()
        elif action == "off":
            resp = d.turn_off()
        else:
            raise ValueError(f"Ação inválida: {action}")
        
        log(f"[DEBUG] Resposta do dispositivo: {resp}")
    except Exception as e:
        # Limpar cache se houver erro de conexão
        if tuya_device_id in DEVICE_CACHE:
            log(f"[INFO] Limpando cache de IP para {tuya_device_id} devido a erro")
            del DEVICE_CACHE[tuya_device_id]
        raise RuntimeError(f"Erro ao enviar comando para dispositivo: {e}")

# =========================
# API HTTP
# =========================

app = Flask(__name__)

@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "site": SITE_NAME}), 200

@app.route("/config/tuya", methods=["POST"])
def api_config_tuya():
    """
    Configura as contas Tuya para buscar local_key.
    Body:
    {
        "accounts": [
            {
                "access_id": "td7tp3cvq3nrc35emwg3",
                "access_key": "bbcdaa3dfe9545fca4326fcfa1cf3e2c",
                "endpoint": "https://openapi.tuyaus.com",
                "uid": "az1715569264750N2mUr"
            },
            ...
        ]
    }
    """
    try:
        data = request.get_json(silent=True) or {}
        accounts = data.get("accounts", [])
        
        if not accounts:
            return jsonify({"ok": False, "error": "Nenhuma conta fornecida"}), 400
        
        # Validar contas
        for account in accounts:
            required_fields = ["access_id", "access_key", "endpoint", "uid"]
            for field in required_fields:
                if field not in account:
                    return jsonify({"ok": False, "error": f"Campo obrigatório ausente: {field}"}), 400
        
        update_tuya_accounts(accounts)
        
        return jsonify({
            "ok": True,
            "message": f"{len(accounts)} conta(s) Tuya configurada(s)"
        }), 200
        
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /config/tuya: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

@app.route("/config/supabase", methods=["POST"])
def api_config_supabase():
    """
    Configura as credenciais do Supabase.
    
    Body:
    {
        "url": "https://kihyhoqbrkwbfudttevo.supabase.co",
        "anon_key": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
    }
    """
    try:
        data = request.get_json(force=True, silent=False) or {}
        url = data.get("url")
        anon_key = data.get("anon_key")
        
        if not url or not anon_key:
            return jsonify({
                "ok": False,
                "error": "url e anon_key são obrigatórios"
            }), 400
        
        update_supabase_config(url, anon_key)
        
        return jsonify({
            "ok": True,
            "message": "Configuração do Supabase atualizada com sucesso"
        }), 200
        
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /config/supabase: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

@app.route("/tuya/command", methods=["POST"])
def api_tuya_command():
    try:
        data: Dict[str, Any] = request.get_json(force=True, silent=True) or {}
        
        action = data.get("action")
        tuya_device_id = data.get("tuya_device_id")
        local_key = data.get("local_key")
        lan_ip = data.get("lan_ip")  # pode vir None, vazio ou "auto"
        version = data.get("version")  # pode vir None, vazio ou um número (ex: 3.3, 3.4)
        
        if action not in ("on", "off"):
            return jsonify({"ok": False, "error": "action deve ser 'on' ou 'off'"}), 400
        
        if not tuya_device_id:
            return jsonify({"ok": False, "error": "tuya_device_id é obrigatório"}), 400
        
        # FALLBACK: Se local_key, lan_ip ou version não vierem no JSON, buscar do banco
        if not local_key or not lan_ip or lan_ip == "auto" or not version:
            log(f"[COMMAND] Dados incompletos no JSON - buscando do banco como fallback")
            log(f"[COMMAND] local_key presente: {bool(local_key)}, lan_ip: {lan_ip}, version: {version}")
            
            db_devices = get_devices_from_db([tuya_device_id])
            if tuya_device_id in db_devices:
                db_device = db_devices[tuya_device_id]
                log(f"[COMMAND] Device encontrado no banco, usando dados do cache")
                
                # Usar dados do banco apenas se não vieram no JSON
                if not local_key:
                    local_key = db_device.get('local_key')
                    log(f"[COMMAND] local_key obtida do banco: {local_key[:8] if local_key else 'None'}...")
                
                if not lan_ip or lan_ip == "auto":
                    lan_ip = db_device.get('lan_ip')
                    log(f"[COMMAND] lan_ip obtido do banco: {lan_ip}")
                
                if not version:
                    protocol_version = db_device.get('protocol_version')
                    if protocol_version:
                        try:
                            version = float(protocol_version)
                            log(f"[COMMAND] version obtida do banco: {version}")
                        except (ValueError, TypeError):
                            version = None
            else:
                log(f"[COMMAND] Device {tuya_device_id} não encontrado no banco")
        
        # Validar se temos os dados mínimos necessários
        if not local_key:
            return jsonify({"ok": False, "error": "local_key é obrigatório e não foi encontrado no banco"}), 400
        
        # Converte version para float se vier como string
        if version is not None and version != "":
            try:
                version = float(version)
            except (ValueError, TypeError):
                version = None
        
        send_tuya_command(
            action=action,
            tuya_device_id=tuya_device_id,
            local_key=local_key,
            lan_ip=lan_ip,
            version=version
        )
        
        return jsonify({"ok": True}), 200
    
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /tuya/command: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

@app.route("/tuya/devices", methods=["GET"])
def api_tuya_devices():
    """Retorna lista de dispositivos escaneados na rede"""
    try:
        devices = scan_devices()
        device_list = []
        for gwid, dev_info in devices.items():
            device_list.append({
                "id": gwid,
                "ip": dev_info.get("ip", ""),
                "version": dev_info.get("version", "")
            })
        return jsonify({"ok": True, "devices": device_list}), 200
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /tuya/devices: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

@app.route("/tuya/heartbeat", methods=["POST"])
def api_tuya_heartbeat():
    """
    Atualiza o campo updated_at de um dispositivo (heartbeat).
    
    Body:
    {
        "tuya_device_id": "bf1234567890abcdef"
    }
    """
    try:
        data: Dict[str, Any] = request.get_json(force=True, silent=True) or {}
        
        tuya_device_id = data.get("tuya_device_id")
        
        if not tuya_device_id:
            return jsonify({
                "ok": False,
                "error": "tuya_device_id é obrigatório"
            }), 400
        
        # Atualizar heartbeat no banco
        success = update_device_heartbeat(tuya_device_id)
        
        if success:
            return jsonify({
                "ok": True,
                "message": f"Heartbeat atualizado com sucesso para device {tuya_device_id}"
            }), 200
        else:
            return jsonify({
                "ok": False,
                "error": f"Device {tuya_device_id} não encontrado ou erro ao atualizar"
            }), 404
    
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /tuya/heartbeat: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

def fetch_local_key_from_tuya_api(tuya_device_id: str) -> Optional[str]:
    """
    Busca a local_key de um dispositivo usando a API Tuya.
    Tenta todas as contas configuradas até encontrar.
    Retorna a local_key se encontrada, None caso contrário.
    """
    if not TUYA_CONNECTOR_AVAILABLE:
        log("[TUYA_API] tuya-connector-python não está disponível")
        return None
    
    if not TUYA_ACCOUNTS:
        log("[TUYA_API] Nenhuma conta Tuya configurada")
        return None
    
    for account in TUYA_ACCOUNTS:
        try:
            access_id = account.get("access_id")
            access_key = account.get("access_key")
            endpoint = account.get("endpoint")
            uid = account.get("uid")
            
            if not all([access_id, access_key, endpoint, uid]):
                log(f"[TUYA_API] Conta incompleta, pulando...")
                continue
            
            log(f"[TUYA_API] Tentando buscar local_key para {tuya_device_id} na conta {access_id[:8]}...")
            
            api = TuyaOpenAPI(endpoint, access_id, access_key)
            api.connect()
            
            # Buscar local_key via /v2.0/cloud/thing/{dev_id}
            detail_v2 = api.get(f"/v2.0/cloud/thing/{tuya_device_id}", {})
            
            if detail_v2 and detail_v2.get("success"):
                result = detail_v2.get("result", {}) or {}
                local_key = result.get("local_key")
                
                if local_key:
                    log(f"[TUYA_API] local_key encontrada para {tuya_device_id}: {local_key[:8]}...")
                    return local_key
                else:
                    log(f"[TUYA_API] local_key não encontrada na resposta para {tuya_device_id}")
            else:
                log(f"[TUYA_API] Erro ao buscar /v2.0/cloud/thing/{tuya_device_id}: {detail_v2}")
        
        except Exception as e:
            log(f"[TUYA_API] Erro ao buscar local_key na conta {account.get('access_id', 'unknown')[:8]}: {e}")
            traceback.print_exc()
            continue
    
    log(f"[TUYA_API] local_key não encontrada para {tuya_device_id} em nenhuma conta")
    return None

@app.route("/tuya/sync", methods=["POST"])
def api_sync_devices():
    """
    Sincroniza devices encontrados na rede LAN com a tabela tuya_devices.
    Para cada device encontrado na rede, se existir na tabela com mesmo tuya_device_id,
    atualiza: lan_ip, protocol_version (sempre que disponíveis do scan).
    Opcionalmente pode receber site_id, name e local_key no body para atualizar também.
    
    Body opcional:
    {
        "site_id": "Nome da Unidade",
        "devices": {
            "tuya_device_id_1": {
                "name": "Nome do Device",
                "local_key": "local_key_da_placa"
            },
            ...
        }
    }
    """
    try:
        log("[SYNC] Iniciando sincronização de devices...")
        
        # Ler dados opcionais do body
        body_data = request.get_json(silent=True) or {}
        site_id_from_body = body_data.get("site_id") or SITE_NAME
        devices_data = body_data.get("devices", {})
        
        # 1) Fazer scan LAN para pegar devices na rede
        lan_devices = scan_devices()
        
        if not lan_devices:
            log("[SYNC] Nenhum device encontrado na rede")
            return jsonify({
                "ok": True,
                "message": "Nenhum device encontrado na rede",
                "updated": 0
            }), 200
        
        log(f"[SYNC] Encontrados {len(lan_devices)} devices na rede")
        
        # 2) Buscar devices no banco que correspondem aos encontrados na rede
        tuya_ids = list(lan_devices.keys())
        db_devices = get_devices_from_db(tuya_ids)
        
        log(f"[SYNC] Encontrados {len(db_devices)} devices no banco")
        
        # 3) Para cada device encontrado na rede, atualizar ou criar
        updated_count = 0
        created_count = 0
        updated_devices = []
        created_devices = []
        
        for tuya_id, lan_info in lan_devices.items():
            lan_ip = lan_info.get("ip")
            protocol_version = lan_info.get("version")
            
            # Converter version para string se necessário
            if protocol_version:
                protocol_version = str(protocol_version)
            
            # Buscar dados opcionais do body (name e local_key)
            device_extra_data = devices_data.get(tuya_id, {})
            name_from_body = device_extra_data.get("name") or site_id_from_body  # Usar site_id se name não fornecido
            local_key_from_body = device_extra_data.get("local_key")
            
            # Se não temos local_key do body, tentar buscar da API Tuya
            if not local_key_from_body:
                log(f"[SYNC] Tentando buscar local_key da API Tuya para {tuya_id}...")
                local_key_from_api = fetch_local_key_from_tuya_api(tuya_id)
                if local_key_from_api:
                    local_key_from_body = local_key_from_api
                    log(f"[SYNC] local_key obtida da API Tuya para {tuya_id}")
                else:
                    log(f"[SYNC] Não foi possível obter local_key da API Tuya para {tuya_id}")
            
            # Verificar se device existe no banco
            if tuya_id in db_devices:
                # Device existe: ATUALIZAR
                db_info = db_devices[tuya_id]
                
                # Preparar dados para atualização
                update_needed = False
                update_data = {}
                
                # Sempre atualizar lan_ip e protocol_version se disponíveis do scan
                if lan_ip and lan_ip != db_info.get('lan_ip'):
                    update_data['lan_ip'] = lan_ip
                    update_needed = True
                
                if protocol_version and protocol_version != db_info.get('protocol_version'):
                    update_data['protocol_version'] = protocol_version
                    update_needed = True
                
                # Atualizar site_id se fornecido no body
                if site_id_from_body and site_id_from_body != db_info.get('site_id'):
                    update_data['site_id'] = site_id_from_body
                    update_needed = True
                
                # Sempre atualizar name com site_id se fornecido
                if site_id_from_body:
                    if name_from_body != db_info.get('name'):
                        update_data['name'] = name_from_body
                        update_needed = True
                
                # Atualizar local_key se fornecido no body
                if local_key_from_body and local_key_from_body != db_info.get('local_key'):
                    update_data['local_key'] = local_key_from_body
                    update_needed = True
                
                if update_needed:
                    success = update_device_in_db(
                        tuya_device_id=tuya_id,
                        site_id=update_data.get('site_id'),
                        name=update_data.get('name'),
                        local_key=update_data.get('local_key'),
                        lan_ip=update_data.get('lan_ip'),
                        protocol_version=update_data.get('protocol_version')
                    )
                    
                    if success:
                        updated_count += 1
                        updated_devices.append({
                            "tuya_device_id": tuya_id,
                            "action": "updated",
                            "updated_fields": list(update_data.keys())
                        })
                        log(f"[SYNC] Device {tuya_id} atualizado: {list(update_data.keys())}")
                else:
                    log(f"[SYNC] Device {tuya_id} já está atualizado")
            else:
                # Device não existe: CRIAR
                log(f"[SYNC] Device {tuya_id} não encontrado no banco, criando novo registro...")
                
                success = create_device_in_db(
                    tuya_device_id=tuya_id,
                    site_id=site_id_from_body,
                    name=name_from_body or site_id_from_body,  # Garantir que name seja preenchido
                    local_key=local_key_from_body,
                    lan_ip=lan_ip,
                    protocol_version=protocol_version
                )
                
                if success:
                    created_count += 1
                    created_devices.append({
                        "tuya_device_id": tuya_id,
                        "action": "created"
                    })
                    log(f"[SYNC] Device {tuya_id} criado com sucesso")
        
        total_processed = updated_count + created_count
        log(f"[SYNC] Sincronização concluída: {updated_count} atualizados, {created_count} criados")
        
        return jsonify({
            "ok": True,
            "message": f"{updated_count} device(s) atualizado(s), {created_count} device(s) criado(s)",
            "updated": updated_count,
            "created": created_count,
            "total": total_processed,
            "devices": updated_devices + created_devices
        }), 200
        
    except Exception as e:
        err = str(e)
        log(f"[ERRO] API /tuya/sync: {err}")
        traceback.print_exc()
        return jsonify({"ok": False, "error": err}), 500

def start_server(host="0.0.0.0", port=8000):
    """Inicia o servidor Flask"""
    log(f"[START] Servidor Tuya local rodando em http://{host}:{port} (SITE={SITE_NAME})")
    # Faz o scan inicial
    scan_and_print_devices()
    app.run(host=host, port=port, debug=False, use_reloader=False)

