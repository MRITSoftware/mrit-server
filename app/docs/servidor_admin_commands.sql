-- Tabela para comandos admin remotos enviados aos servidores MRIT
-- Permite enviar OTA update, restart, logs e status para qualquer unidade via Supabase

CREATE TABLE servidor_admin_commands (
    id          BIGSERIAL PRIMARY KEY,
    site_name   TEXT NOT NULL,
    comando     TEXT NOT NULL CHECK (comando IN ('ota_update', 'reiniciar', 'logs', 'status', 'tuya_discover')),
    status      TEXT NOT NULL DEFAULT 'pendente'
                    CHECK (status IN ('pendente', 'executando', 'concluido', 'erro')),
    resultado   TEXT,
    iniciado_em TIMESTAMPTZ,
    concluido_em TIMESTAMPTZ,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

-- Index para a query de polling do servidor (site_name + status)
CREATE INDEX idx_admin_cmds_site_status ON servidor_admin_commands (site_name, status);

-- RLS: qualquer cliente autenticado com anon key pode inserir e consultar
ALTER TABLE servidor_admin_commands ENABLE ROW LEVEL SECURITY;

CREATE POLICY "anon_select" ON servidor_admin_commands
    FOR SELECT TO anon USING (true);

CREATE POLICY "anon_insert" ON servidor_admin_commands
    FOR INSERT TO anon WITH CHECK (true);

CREATE POLICY "anon_update" ON servidor_admin_commands
    FOR UPDATE TO anon USING (true) WITH CHECK (true);

-- ============================================================
-- Como usar (via Supabase dashboard ou API):
--
-- Enviar OTA update para uma unidade:
--   INSERT INTO servidor_admin_commands (site_name, comando)
--   VALUES ('itaquera@gelafit.com.br', 'ota_update');
--
-- Ver resultado:
--   SELECT * FROM servidor_admin_commands ORDER BY created_at DESC LIMIT 10;
--
-- Comandos disponíveis:
--   ota_update    - git pull + copia arquivos + reinicia serviços
--   reiniciar     - reinicia mrit-server e mrit-webui
--   logs          - retorna últimas 100 linhas do log do servidor
--   status        - retorna site, version, devices, wifi_ssid, timestamp
--   tuya_discover - escaneia rede, busca local_key, sincroniza dispositivo
--
-- Se a tabela já existir, adicionar tuya_discover ao constraint:
--   ALTER TABLE servidor_admin_commands
--     DROP CONSTRAINT servidor_admin_commands_comando_check,
--     ADD CONSTRAINT servidor_admin_commands_comando_check
--       CHECK (comando IN ('ota_update', 'reiniciar', 'logs', 'status', 'tuya_discover'));
-- ============================================================
