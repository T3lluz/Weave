# Installs Weave's computer side on Windows for this user. No admin needed.
#   irm https://t3lluz.com/weave/install.ps1 | iex
# It puts everything under %LOCALAPPDATA%\Weave: its own Python (the small embeddable one), its
# own ffmpeg, and the weave program. It starts with Windows, and `weave pair` shows the code to
# scan. Run it again to update.
param(
  [string]$AppZip = 'https://github.com/T3lluz/Weave/archive/refs/heads/master.zip',
  [string]$PythonZip = 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-embed-amd64.zip',
  [string]$PythonSha = '4acbed6dd1c744b0376e3b1cf57ce906f9dc9e95e68824584c8099a63025a3c3',
  [string]$FfmpegZip = 'https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip',
  [string]$FfmpegSha = '60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba',
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

# Python and ffmpeg run at every logon from here on: each download is checked against the
# SHA-256 of the exact file this installer was tested with before anything is unpacked.
function Fetch($url, $name, $sha) {
  $to = Join-Path $tmp $name
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $to
  if ($sha) {
    $got = (Get-FileHash -Algorithm SHA256 $to).Hash.ToLower()
    if ($got -ne $sha.ToLower()) { Remove-Item $to -Force; throw "The download of $name isn't the file it should be (its checksum is $got). Nothing was installed from it." }
  }
  return $to
}

# a running daemon holds its files open
Get-CimInstance Win32_Process -Filter "Name = 'pythonw.exe' OR Name = 'python.exe'" |
  Where-Object { $_.ExecutablePath -like "$python*" } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Milliseconds 400

if (-not (Test-Path (Join-Path $python 'python.exe'))) {
  Write-Host 'Fetching Python...'
  Expand-Archive -Force (Fetch $PythonZip 'python.zip' $PythonSha) $python
}
# the embeddable Python only looks where its ._pth file says: add the folder weave lives in
$pth = Get-ChildItem $python -Filter 'python*._pth' | Select-Object -First 1
$lines = @(Get-Content $pth.FullName | Where-Object { $_ -ne '..\app' })
Set-Content -Encoding ASCII $pth.FullName ($lines + '..\app')

Write-Host 'Fetching Weave...'
$unpacked = Join-Path $tmp 'app'
Expand-Archive -Force (Fetch $AppZip 'weave.zip' $null) $unpacked
$package = Get-ChildItem $unpacked -Recurse -Directory -Filter 'weave' |
  Where-Object { Test-Path (Join-Path $_.FullName '__main__.py') } | Select-Object -First 1
if (-not $package) { throw 'The download has no weave program in it.' }
if (Test-Path $app) { Remove-Item -Recurse -Force $app }
New-Item -ItemType Directory -Force $app | Out-Null
Copy-Item -Recurse $package.FullName (Join-Path $app 'weave')

if (-not (Test-Path (Join-Path $ffmpeg 'bin\ffmpeg.exe')) -and -not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
  Write-Host 'Fetching ffmpeg (about 100 MB)...'
  $unzipped = Join-Path $tmp 'ffmpeg'
  Expand-Archive -Force (Fetch $FfmpegZip 'ffmpeg.zip' $FfmpegSha) $unzipped
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
