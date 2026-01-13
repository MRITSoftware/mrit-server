# Sincronização de Contas Tuya do Banco de Dados

## ✅ Implementado

O sistema agora busca contas Tuya diretamente da tabela `contas_tuya` no Supabase e salva localmente no `config.json` para evitar consultas repetidas ao banco.

---

## 🔄 Como Funciona

### **1. Busca do Banco de Dados**

A função `fetch_tuya_accounts_from_database()` busca contas da tabela `contas_tuya`:

```python
# Busca apenas contas com enabled=true
url = f"{base_url}/contas_tuya?enabled=eq.true&select=access_id,access_key,endpoint,uid,label"
```

**Campos retornados:**
- `access_id` - ID de acesso
- `access_key` - Chave secreta
- `endpoint` - URL da API Tuya
- `uid` - ID do usuário
- `label` - Nome/etiqueta da conta (não usado, apenas informativo)

### **2. Salvamento Local**

As contas são salvas no arquivo `config.json`:

```json
{
  "site_name": "NOME_DO_SITE",
  "supabase": {...},
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

**Localização:** `/data/data/com.mritsoftware.mritserver/files/config.json`

### **3. Quando Sincroniza**

A sincronização acontece automaticamente em **3 situações**:

#### **a) Primeira Inicialização do Servidor**
- Se não houver contas no `config.json`
- Busca do banco e salva localmente
- Se não encontrar no banco, usa contas padrão como fallback

#### **b) Durante Sincronização de Dispositivos (`/tuya/sync`)**
- Se não houver contas locais quando sincronizar dispositivos
- Garante que as contas estejam atualizadas antes de buscar `local_key`

#### **c) Via Endpoint Manual (`/config/tuya/sync`)**
- Permite forçar sincronização manual
- Útil para atualizar contas após mudanças no banco

---

## 📋 Endpoints Disponíveis

### **1. Sincronizar Contas do Banco**

```bash
POST http://localhost:8000/config/tuya/sync
Content-Type: application/json

{
  "force": true  // Opcional: força atualização mesmo se já houver contas locais
}
```

**Resposta:**
```json
{
  "ok": true,
  "message": "3 conta(s) Tuya sincronizada(s) do banco",
  "accounts_count": 3
}
```

### **2. Configurar Contas Manualmente (Ainda Funciona)**

```bash
POST http://localhost:8000/config/tuya
Content-Type: application/json

{
  "accounts": [
    {
      "access_id": "...",
      "access_key": "...",
      "endpoint": "...",
      "uid": "..."
    }
  ]
}
```

---

## 🔍 Fluxo Completo

```
1. Servidor inicia
   ↓
2. Verifica se há contas no config.json
   ↓
3. Se NÃO houver:
   → Busca do banco (contas_tuya, enabled=true)
   → Salva no config.json
   → Usa contas locais
   ↓
4. Se houver:
   → Usa contas locais (não consulta banco)
   ↓
5. Quando precisar buscar local_key:
   → Usa contas locais (rápido, sem consulta ao banco)
```

---

## ⚠️ Importante

### **1. Contas Locais Têm Prioridade**

- Se já houver contas no `config.json`, **não sincroniza** automaticamente
- Isso evita sobrescrever configuração manual
- Para forçar atualização, use endpoint `/config/tuya/sync` com `force: true`

### **2. Fallback para Contas Padrão**

- Se não encontrar contas no banco **E** não houver contas locais
- Usa contas padrão hardcoded (apenas na primeira inicialização)
- Logs indicam qual fonte foi usada

### **3. Tabela no Banco**

A tabela `contas_tuya` deve ter:
- `access_id` (text, NOT NULL)
- `access_key` (text, NOT NULL)
- `endpoint` (text, NOT NULL)
- `uid` (text, NOT NULL)
- `enabled` (boolean, default true)
- `label` (text, opcional)

**Query usada:**
```sql
SELECT access_id, access_key, endpoint, uid, label
FROM contas_tuya
WHERE enabled = true;
```

---

## 🧪 Como Testar

### **1. Verificar se Contas Foram Sincronizadas:**

**Logs do servidor:**
```
[DB] 3 conta(s) Tuya encontrada(s) no banco
[SYNC] ✅ 3 conta(s) Tuya sincronizada(s) do banco e salva(s) localmente
```

### **2. Forçar Sincronização:**

```bash
curl -X POST http://localhost:8000/config/tuya/sync \
  -H "Content-Type: application/json" \
  -d '{"force": true}'
```

### **3. Verificar Contas Locais:**

```bash
# Via ADB (requer root ou app em modo debug)
adb shell
run-as com.mritsoftware.mritserver
cat files/config.json
```

---

## 📊 Vantagens

✅ **Performance:** Não consulta banco toda vez que precisa de contas  
✅ **Offline:** Funciona mesmo se banco estiver temporariamente indisponível  
✅ **Flexibilidade:** Pode configurar manualmente ou via banco  
✅ **Atualização:** Pode forçar sincronização quando necessário  

---

## 🔄 Quando Atualizar Manualmente

Você deve chamar `/config/tuya/sync` com `force: true` quando:

1. **Adicionar nova conta** no banco e quiser usar imediatamente
2. **Desabilitar conta** (`enabled=false`) e quiser remover localmente
3. **Atualizar credenciais** de uma conta existente
4. **Trocar de dispositivo** (opcional, mas recomendado)

---

## 💡 Resumo

**Antes:**
- Contas hardcoded no código
- Não atualizava do banco

**Agora:**
- ✅ Busca do banco na primeira vez
- ✅ Salva localmente para performance
- ✅ Sincroniza automaticamente quando necessário
- ✅ Pode forçar atualização via endpoint
- ✅ Mantém compatibilidade com configuração manual
