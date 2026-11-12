# Vraca Mi TV Stick (MiTV-AESP0) u stanje pre ciscenja.
# Pokretanje iz foldera repoa:  powershell -ExecutionPolicy Bypass -File work\restore-all.ps1 [-TvHost <tv ip>]
# Na TV-u mora biti ukljucen USB/Network debugging (Developer options), inace ADB ne moze da se poveze.
param([string]$TvHost = '<tv ip>')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$env:TV_HOST = $TvHost
$py = 'tools\venv\Scripts\python.exe'

# 1. Ponovo ukljuci sve iskljucene pakete i vrati stari Home (Google TV launcher).
powershell -NoProfile -ExecutionPolicy Bypass -File tools\restore.ps1 -TvHost $TvHost work\debloat-all.txt -Stock com.google.android.tvlauncher

# 1b. Vrati Google screensaver (Backdrop je ponovo ukljucen u koraku 1).
& $py -I tools\tv.py 'settings put secure screensaver_components com.google.android.backdrop/.Backdrop'
& $py -I tools\tv.py 'settings put secure screensaver_default_component com.google.android.backdrop/.Backdrop'

# 2. Vrati DNS na podrazumevani (automatski) rezim.
& $py -I tools\tv.py 'settings put global private_dns_mode opportunistic'
& $py -I tools\tv.py 'settings delete global private_dns_specifier'

# 2b. Vrati Androidov klik navigacije i ukloni radio dodatak (nase aplikacije, ne sistemske).
& $py -I tools\tv.py 'settings put system sound_effects_enabled 1'
& $py -I tools\tv.py 'pm uninstall local.cleanyourtv.radio'

# 3. (Opciono) ukloni novi launcher. Skini # sa sledece linije ako ga vise ne zelis:
# & $py -I tools\tv.py 'pm uninstall com.gathin.tvhome'

Write-Host 'Gotovo. Restartuj TV (tools\tv.py --reboot) i proveri pocetni ekran.'
