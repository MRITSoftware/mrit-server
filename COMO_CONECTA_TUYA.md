# Como o App se Conecta às Contas Tuya

## 🔍 Visão Geral

O app **NÃO se conecta diretamente** às contas Tuya. O servidor **Python** (que roda dentro do app Android) é quem faz a conexão.

---

## 📋 Fluxo de Conexão

```
App Android
    ↓
Servidor Python (Flask) - tuya_server.py
    ↓
Biblioteca tuya-connector-python
    ↓
API Tuya Cloud (openapi.tuyaus.com)
    ↓
Busca local_key dos dispositivos
```

---

## 🔑 Informações Usadas para Conectar

O servidor Python usa **4 informações** para cada conta Tuya:

### **1. access_id**
- ID de acesso da conta Tuya
- Exemplo: `"td7tp3cvq3nrc35emwg3"`

### **2. access_key**
- Chave secreta de acesso
- Exemplo: `"bbcdaa3dfe9545fca4326fcfa1cf3e2c"`

### **3. endpoint**
- URL da API Tuya (região)
- Exemplo: `"https://openapi.tuyaus.com"` (Américas)

### **4. uid**
- ID do usuário Tuya
- Exemplo: `"az1715569264750N2mUr"`

---

## 📍 Onde as Contas são Configuradas

### **1. Contas Padrão (Hardcoded)**

No arquivo `tuya_server.py`, há **2 contas padrão**:

```python
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
```

**Se não houver contas configuradas**, essas são usadas automaticamente.

### **2. Arquivo config.json**

O servidor salva as contas em:
```
/data/data/com.mritsoftware.mritserver/files/config.json
```

Estrutura:
```json
{
  "site_name": "NOME_DO_SITE",
  "supabase": {
    "url": "...",
    "anon_key": "..."
  },
  "tuya_accounts": [
    {
      "access_id": "...",
      "access_key": "...",
      "endpoint": "...",
      "uid": "..."
    }
  ]
}
```

### **3. Endpoint HTTP (POST)**

Você pode configurar contas via API:

```bash
POST http://localhost:8000/config/tuya
Content-Type: application/json

{
  "accounts": [
    {
      "access_id": "td7tp3cvq3nrc35emwg3",
      "access_key": "bbcdaa3dfe9545fca4326fcfa1cf3e2c",
      "endpoint": "https://openapi.tuyaus.com",
      "uid": "az1715569264750N2mUr"
    }
  ]
}
```

---

## 🔄 Como Funciona a Busca de local_key

### **1. Quando Precisa de local_key**

Quando um comando é enviado para um dispositivo Tuya, o servidor precisa da `local_key` para se comunicar localmente (via UDP).

### **2. Onde Busca**

1. **Cache local** (memória do servidor)
2. **Banco Supabase** (tabela `tuya_devices`, campo `local_key`)
3. **API Tuya** (se não encontrar no banco)

### **3. Processo de Busca na API Tuya**

```python
def fetch_local_key_from_tuya_api(tuya_device_id: str):
    # Para cada conta configurada:
    for account in TUYA_ACCOUNTS:
        # Conectar à API Tuya
        api = TuyaOpenAPI(endpoint, access_id, access_key)
        api.connect()
        
        # Buscar dispositivo via /v2.0/cloud/thing/{dev_id}
        detail = api.get(f"/v2.0/cloud/thing/{tuya_device_id}", {})
        
        # Extrair local_key da resposta
        local_key = detail.get("result", {}).get("local_key")
        
        # Se encontrou, retorna
        if local_key:
            return local_key
```

**O servidor tenta todas as contas** até encontrar o dispositivo.

---

## 📱 App Android (LoginActivity)

O `LoginActivity` no app Android **NÃO é usado** para conectar às contas Tuya.

Ele salva `api_key` e `api_secret` no `SharedPreferences`, mas:
- ❌ Esses valores **não são usados** pelo servidor Python
- ❌ O servidor Python usa suas próprias contas (config.json ou padrão)
- ✅ O LoginActivity parece ser código legado ou para uso futuro

---

## 🔐 Segurança

### **⚠️ ATENÇÃO: Credenciais Expostas**

As contas Tuya padrão estão **hardcoded no código**:
- ✅ Funciona para desenvolvimento/teste
- ⚠️ **NÃO é seguro** para produção
- ⚠️ Qualquer pessoa com acesso ao código vê as credenciais

### **✅ Recomendação**

1. **Remover contas padrão** do código
2. **Configurar via endpoint** `/config/tuya` após instalação
3. **Ou usar variáveis de ambiente** (se possível)
4. **Ou criptografar** o config.json

---

## 📊 Resumo

| Item | Onde | Como |
|------|------|------|
| **Contas Tuya** | `tuya_server.py` ou `config.json` | Hardcoded ou via API |
| **Informações Usadas** | `access_id`, `access_key`, `endpoint`, `uid` | Para cada conta |
| **Biblioteca** | `tuya-connector-python` | Conecta à API Tuya |
| **Objetivo** | Buscar `local_key` dos dispositivos | Para comunicação local |
| **App Android** | **NÃO se conecta** diretamente | Apenas o servidor Python |

---

## 🔍 Como Verificar Contas Configuradas

### **1. Via Logs do Servidor:**

```
[INFO] Contas Tuya configuradas automaticamente: 2 conta(s)
```

### **2. Via Endpoint:**

```bash
GET http://localhost:8000/config/tuya
```

### **3. Via Arquivo (Root necessário):**

```bash
adb shell
run-as com.mritsoftware.mritserver
cat files/config.json
```

---

## 💡 Conclusão

**O app se conecta às contas Tuya através do servidor Python**, que:
1. Usa contas configuradas em `config.json` ou padrão
2. Conecta à API Tuya usando `tuya-connector-python`
3. Busca `local_key` dos dispositivos quando necessário
4. Tenta todas as contas até encontrar o dispositivo

**O app Android em si não faz conexão direta** com a API Tuya.
