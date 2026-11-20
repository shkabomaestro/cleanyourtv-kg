# Sve odjednom, sa poslovnog racunara (cleanyourtv-kg u Documents, claude.bundle u Downloads):
#   powershell -ExecutionPolicy Bypass -File work\uradi-sve.ps1 -TvHost <tv ip>
# 1. sacuva tvoje trenutno stanje u git   2. ubaci Claude-ove izmene iz claude.bundle (samo kod, tvoja tema ostaje)
# 3. nove slike: trazi, pa ti pokaze pregled i pita koje ne zelis, dok ne kazes da je u redu
# 4. napravi i instalira radio dodatak i launcher, posalje temu   5. slika ekrana sa TV-a i provera
# 6. sacuva kljuc za potpis (za stari racunar)   7. posalje sve na tvoj GitHub fork
# -Continue: nastavi prekinut rad (preskace 1 i 2, slike bira iz vec nadjenih, bez ponovnog trazenja)
param([Parameter(Mandatory = $true)][string]$TvHost, [switch]$SkipPhotos, [switch]$Continue)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$py = 'tools\venv\Scripts\python.exe'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$downloads = "$env:USERPROFILE\Downloads"
function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what nije uspelo (kod $LASTEXITCODE)" } }
if (-not (git config user.name)) { git config user.name 'Bosko'; git config user.email 'bosko@localhost' }

if (-not $Continue) {
Step '1/7 Cuvanje tvog trenutnog stanja'
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -q -m "Stanje sa sticka $(Get-Date -Format 'yyyy-MM-dd HH:mm')"; Check 'Cuvanje stanja' }
Write-Host 'Sacuvano.'

Step '2/7 Claude-ove izmene'
$bundle = Join-Path $downloads 'claude.bundle'
if (-not (Test-Path $bundle)) { throw "Nema $bundle. Skini claude.bundle u Downloads." }
git fetch -q $bundle "+kragujevac:refs/heads/claude-izmene"; Check 'Citanje claude.bundle'
git checkout claude-izmene -- launcher addons/radio work/kg_more.py work/kg_grade.py work/kg_keep_own.py work/kg-update.ps1 work/kg-novi-racunar.ps1 work/uradi-sve.ps1 work/mreza.ps1 tools/manage.py tests themes/README.md
Check 'Ubacivanje izmena'
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -q -m 'Claude: utakmice Zvezde, kurs, istorija, prognoza, pesma na radiju, radio se sam vraca'; Check 'Cuvanje izmena' }
Write-Host 'Ubaceno.'
}

Step 'Povezivanje sa stickom'
& $adb connect "${TvHost}:5555" | Out-Host
$ErrorActionPreference = 'Continue'
for ($i = 0; $i -lt 40 -and "$(& $adb -s "${TvHost}:5555" get-state 2>$null)".Trim() -ne 'device'; $i++) {
    if ($i -eq 0) { Write-Host 'Na TV-u stikliraj "Always allow" i pritisni OK...' -ForegroundColor Yellow }
    Start-Sleep 3; & $adb connect "${TvHost}:5555" | Out-Null
}
$ErrorActionPreference = 'Stop'
if ("$(& $adb -s "${TvHost}:5555" get-state)".Trim() -ne 'device') { throw "Stick na $TvHost ne odgovara. Proveri IP na TV-u (Settings > Network)." }

if (-not $SkipPhotos) {
    Step '3/7 Nove slike'
    if ($Continue -and (Test-Path work\kg-more\candidates.json)) { & $py -I work\kg_more.py --reuse }
    else { & $py -I work\kg_more.py }
    Check 'Trazenje slika'
    while ($true) {
        Start-Process (Resolve-Path work\kragujevac-nove.jpg)
        $answer = Read-Host 'Otvoren je pregled. Brojevi slika koje NE zelis (npr. 3,17,22), ili samo Enter ako je sve u redu'
        if (-not $answer.Trim()) { break }
        $answer -split '[^0-9]+' | Where-Object { $_ } | ForEach-Object { $_.PadLeft(2, '0') } | Add-Content work\kg-more-skip.txt
        & $py -I work\kg_more.py --reuse; Check 'Novi izbor slika'
    }
    & $py -I work\kg_grade.py; Check 'Obrada slika'
    & $py -I work\kg_keep_own.py; Check 'Cuvanje tvojih slika'
    git add -A; git diff --cached --quiet
    if ($LASTEXITCODE -ne 0) { git commit -q -m 'Tema: nove slike Kragujevca'; Check 'Cuvanje slika' }
}

Step '4/7 Radio dodatak, launcher i tema na stick'
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
powershell -ExecutionPolicy Bypass -File work\kg-update.ps1 -TvHost $TvHost -SkipPhotos; Check 'Instalacija'

Step '5/7 Provera na TV-u'
Start-Sleep 20   # da se kajron, vreme i vazduh ucitaju
New-Item -ItemType Directory -Force shots | Out-Null
$env:TV_HOST = $TvHost
& $py -I tools\tv.py --screenshot shots\provera.png | Out-Null
if (Test-Path shots\provera.png) { Start-Process (Resolve-Path shots\provera.png) }
Write-Host '--- PROVERA ---'
& $adb -s "${TvHost}:5555" shell 'dumpsys package com.gathin.tvhome | grep lastUpdateTime; dumpsys package local.cleanyourtv.radio | grep lastUpdateTime'
& $adb -s "${TvHost}:5555" shell 'dumpsys meminfo com.gathin.tvhome | grep -E "TOTAL PSS|TOTAL:" | head -1'
& $adb -s "${TvHost}:5555" shell "ls /sdcard/Android/data/com.gathin.tvhome/files/wallpapers | grep -c jpg"
& $adb -s "${TvHost}:5555" logcat -d -b crash | Select-Object -Last 15

Step '6/7 Kljuc za potpis (za stari racunar)'
$key = "$env:USERPROFILE\.android\debug.keystore"
if (Test-Path $key) {
    Copy-Item $key (Join-Path $downloads 'debug.keystore') -Force
    Write-Host "Kopiran u Downloads\debug.keystore. Prebaci ga (USB ili mejlom sebi) na stari racunar u C:\Users\<ti>\.android\debug.keystore, preko starog." -ForegroundColor Yellow
}

Step '7/7 Slanje na GitHub'
$ErrorActionPreference = 'Continue'
git push origin HEAD:kragujevac
if ($LASTEXITCODE -ne 0) { Write-Warning 'Slanje na GitHub nije uspelo (prijava?). Sve je sacuvano lokalno; pokreni kasnije: git push origin HEAD:kragujevac' }
$ErrorActionPreference = 'Stop'

Write-Host "`nGotovo. Posalji Claude-u sliku ekrana sa TV-a (otvorena je) i deo ispod --- PROVERA ---." -ForegroundColor Green
