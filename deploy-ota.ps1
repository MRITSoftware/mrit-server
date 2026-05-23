# deploy-ota.ps1 — Envia comando OTA para uma ou todas as unidades MRIT
# Uso:
#   .\deploy-ota.ps1                              # todas as unidades
#   .\deploy-ota.ps1 -Site "itaquera@gelafit.com.br"   # unidade especifica

param(
    [string]$Site = ""
)

$SUPABASE_URL = "https://kihyhoqbrkwbfudttevo.supabase.co"
$SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtpaHlob3Ficmt3YmZ1ZHR0ZXZvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MTU1NTUwMjcsImV4cCI6MjAzMTEzMTAyN30.XtBTlSiqhsuUIKmhAMEyxofV-dRst7240n912m4O4Us"

$authHeaders = @{
    "apikey"        = $SUPABASE_KEY
    "Authorization" = "Bearer $SUPABASE_KEY"
}
$postHeaders = $authHeaders + @{
    "Content-Type" = "application/json"
    "Prefer"       = "return=minimal"
}

# -- Resolve lista de alvos --------------------------------------------------
if ($Site) {
    $targets = @($Site)
    Write-Host "Alvo: $Site" -ForegroundColor Cyan
} else {
    Write-Host "Buscando unidades cadastradas..." -ForegroundColor Cyan
    try {
        $devs = Invoke-RestMethod `
            -Uri "$SUPABASE_URL/rest/v1/tuya_devices?select=site_id&site_id=not.is.null" `
            -Headers $authHeaders
        $targets = $devs |
            Select-Object -ExpandProperty site_id -Unique |
            Where-Object { $_ -and $_ -ne "" }
    } catch {
        Write-Host "Erro ao buscar unidades: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }

    if (-not $targets) {
        Write-Host "Nenhuma unidade encontrada no banco." -ForegroundColor Yellow
        exit 0
    }
    Write-Host "Encontradas $($targets.Count) unidade(s)" -ForegroundColor Cyan
}

Write-Host ""

# -- Envia OTA ---------------------------------------------------------------
$ok = 0
foreach ($t in $targets) {
    try {
        $body = @{ site_name = $t; comando = "ota_update" } | ConvertTo-Json -Compress
        Invoke-RestMethod `
            -Uri "$SUPABASE_URL/rest/v1/servidor_admin_commands" `
            -Method POST -Headers $postHeaders -Body $body | Out-Null
        Write-Host "  [OK] $t" -ForegroundColor Green
        $ok++
    } catch {
        Write-Host "  [ERRO] $t — $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "$ok/$($targets.Count) unidade(s) notificadas." -ForegroundColor Cyan
Write-Host "A atualizacao ocorre no proximo heartbeat (ate 15 min)."
Write-Host "Para acompanhar: .\check-servers.ps1"
