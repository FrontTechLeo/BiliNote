@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM BiliNote Desktop - backend sidecar packer (PyInstaller onedir)
REM Output:
REM   desktop/src-tauri/bin/BiliNoteBackend/BiliNoteBackend-<triple>.exe
REM   desktop/src-tauri/bin/BiliNoteBackend/_internal/...
REM onedir avoids the per-launch unpack cost of onefile (5-20s -> seconds).
REM Prerequisite: run inside the conda env that has backend deps.
REM ============================================================

cd /d %~dp0..\..
echo Working directory: %cd%

echo Cleaning previous build...
if exist backend\dist rmdir /s /q backend\dist
if exist backend\build rmdir /s /q backend\build
if exist desktop\src-tauri\bin rmdir /s /q desktop\src-tauri\bin
echo Clean done.

mkdir desktop\src-tauri\bin

REM Pin the packer to the biliNote conda env python. Plain `python` on PATH may
REM resolve to another env (e.g. miniconda base 3.9) whose dependency versions
REM and syntax support differ - the bundle must match the dev/test env.
set "PY=%BILINOTE_PYTHON%"
if "%PY%"=="" set "PY=E:\ProgramData\.conda\envs\biliNote\python.exe"
if not exist "%PY%" (
    echo [WARN] biliNote env python not found at "%PY%", falling back to PATH python.
    set "PY=python"
)
echo Using python: %PY%
"%PY%" --version

for /f "tokens=2 delims=:" %%A in ('rustc -Vv ^| findstr "host"') do (
    set "TARGET_TRIPLE=%%A"
)
set "TARGET_TRIPLE=%TARGET_TRIPLE: =%"
echo Detected target triple: %TARGET_TRIPLE%

"%PY%" -m PyInstaller --version >nul 2>&1
if errorlevel 1 (
    echo PyInstaller not found, installing...
    "%PY%" -m pip install pyinstaller
)

echo Packing backend with PyInstaller (onedir)...
REM torch (~3.9GB) is an optional CUDA helper only: every usage is wrapped in
REM try/except ImportError and falls back to CPU (ctranslate2). Excluding it
REM keeps the installer under the NSIS 2GB data-block limit.
"%PY%" -m PyInstaller ^
  -y ^
  --name BiliNoteBackend ^
  --paths backend ^
  --distpath desktop\src-tauri\bin ^
  --workpath backend\build ^
  --specpath backend ^
  --hidden-import uvicorn ^
  --hidden-import fastapi ^
  --hidden-import starlette ^
  --exclude-module torch ^
  --exclude-module torchvision ^
  --exclude-module torchaudio ^
  --add-data "app\db\builtin_providers.json;." ^
  backend\main.py

if errorlevel 1 (
    echo PyInstaller build FAILED!
    exit /b 1
)

move /Y desktop\src-tauri\bin\BiliNoteBackend\BiliNoteBackend.exe desktop\src-tauri\bin\BiliNoteBackend\BiliNoteBackend-%TARGET_TRIPLE%.exe

echo.
echo Build finished. Artifacts:
dir desktop\src-tauri\bin\BiliNoteBackend

echo.
echo Next step: cd desktop ^&^& pnpm tauri build
endlocal
