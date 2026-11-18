# Installs Weave's computer side on Windows for this user. No admin needed.
#   irm https://t3lluz.com/weave/install.ps1 | iex
# It puts everything under %LOCALAPPDATA%\Weave: its own Python (the small embeddable one), its
# own ffmpeg, and the weave program. It starts with Windows, and `weave pair` shows the code to
# scan. Run it again to update.
param(
  [string]$AppZip = 'https://github.com/T3lluz/Weave/archive/refs/heads/master.zip',
  [string]$PythonZip = 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip',
  [string]$FfmpegZip = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip',
  [switch]$NoPair
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # the progress bar makes downloads many times slower
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$root = Join-Path $env:LOCALAPPDATA 'Weave'
$python = Join-Path $root 'python'
$app = Join-Path $root 'app'
$ffmpeg = Join-Path $root 'data\ffmpeg'
$bin = Join-Path $root 'bin'
$tmp = Join-Path $env:TEMP ("weave-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $root, $bin, $tmp | Out-Null

function Fetch($url, $name) {
  $to = Join-Path $tmp $name
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $to
  return $to
}

# a running daemon holds its files open
Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe' OR Name = 'python.exe'" |
  Where-Object { $_.ExecutablePath -like "$python*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 400

if (-not (Test-Path (Join-Path $python 'python.exe'))) {
  Write-Host 'Fetching Python...'
  Expand-Archive -Force (Fetch $PythonZip 'python.zip') $python
}
# the embeddable Python only looks where its ._pth file says: add the folder weave lives in
$pth = Get-ChildItem $python -Filter 'python*._pth' | Select-Object -First 1
$lines = @(Get-Content $pth.FullName | Where-Object { $_ -ne '..\app' })
Set-Content -Encoding ASCII $pth.FullName ($lines + '..\app')

Write-Host 'Fetching Weave...'
$unpacked = Join-Path $tmp 'app'
Expand-Archive -Force (Fetch $AppZip 'weave.zip') $unpacked
$package = Get-ChildItem $unpacked -Recurse -Directory -Filter 'weave' |
  Where-Object { Test-Path (Join-Path $_.FullName '__main__.py') } | Select-Object -First 1
if (-not $package) { throw 'The download has no weave program in it.' }
if (Test-Path $app) { Remove-Item -Recurse -Force $app }
New-Item -ItemType Directory -Force $app | Out-Null
Copy-Item -Recurse $package.FullName (Join-Path $app 'weave')

if (-not (Test-Path (Join-Path $ffmpeg 'bin\ffmpeg.exe')) -and -not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
  Write-Host 'Fetching ffmpeg (about 100 MB)...'
  $unzipped = Join-Path $tmp 'ffmpeg'
  Expand-Archive -Force (Fetch $FfmpegZip 'ffmpeg.zip') $unzipped
  $exe = Get-ChildItem $unzipped -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1
  New-Item -ItemType Directory -Force (Join-Path $ffmpeg 'bin') | Out-Null
  Copy-Item $exe.FullName (Join-Path $ffmpeg 'bin\ffmpeg.exe')
}

# the `weave` command, on this user's PATH
Set-Content -Encoding ASCII (Join-Path $bin 'weave.cmd') "@echo off`r`n`"$python\python.exe`" -m weave %*"
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $bin) {
  [Environment]::SetEnvironmentVariable('Path', (($userPath.TrimEnd(';') + ';' + $bin).TrimStart(';')), 'User')
}
$env:Path = "$env:Path;$bin"

# start with Windows, without a window
$startup = Join-Path ([Environment]::GetFolderPath('Startup')) 'Weave.lnk'
$link = (New-Object -ComObject WScript.Shell).CreateShortcut($startup)
$link.TargetPath = Join-Path $python 'pythonw.exe'
$link.Arguments = '-m weave daemon'
$link.WorkingDirectory = $root
$link.Description = 'Weave: this PC, run from your phone'
$link.Save()
Start-Process -FilePath (Join-Path $python 'pythonw.exe') -ArgumentList '-m', 'weave', 'daemon' -WorkingDirectory $root

Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
Write-Host ''
Write-Host 'Weave is running and starts with Windows.'
Write-Host 'Windows may ask once whether Python may talk on your network: say yes for private networks.'
Write-Host ''
if (-not $NoPair) {
  Start-Sleep -Seconds 2
  & (Join-Path $python 'python.exe') -m weave pair
}
