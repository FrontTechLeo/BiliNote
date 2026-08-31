@echo off
setlocal enabledelayedexpansion

REM ============================================================
REM BiliNote Desktop - portable (green) build assembler
REM Produces:
REM   desktop/dist-portable/BiliNote-Portable-x64/   (run BiliNote.exe directly)
REM   desktop/dist-portable/BiliNote-Desktop-Portable-x64.zip
REM Portable mode: a "portable.flag" marker makes the app keep ALL data
REM (database, notes, screenshots, models, settings) inside its own folder.
REM Nothing is written to the registry or the user profile.
REM Prerequisite: sidecar already packed (desktop/scripts/build_sidecar.bat).
REM ============================================================

cd /d %~dp0..\..
echo Working directory: %cd%

if not exist desktop\src-tauri\bin\BiliNoteBackend\BiliNoteBackend-*.exe (
    echo Sidecar missing! Run desktop\scripts\build_sidecar.bat first.
    exit /b 1
)

if not exist desktop\src-tauri\target\release\bilinote-desktop.exe (
    echo Release app not built yet, running: pnpm tauri build --no-bundle
    cd desktop
    call pnpm tauri build --no-bundle
    if errorlevel 1 exit /b 1
    cd ..
)

set "OUT_DIR=desktop\dist-portable\BiliNote-Portable-x64"
if exist desktop\dist-portable rmdir /s /q desktop\dist-portable
mkdir "%OUT_DIR%"

copy /Y desktop\src-tauri\target\release\bilinote-desktop.exe "%OUT_DIR%\BiliNote.exe" >nul

REM Resolve the triple-suffixed sidecar explicitly (wildcard copy is unreliable),
REM then verify the copied size matches the source.
set "SIDECAR_SRC="
for %%F in ("desktop\src-tauri\bin\BiliNoteBackend\BiliNoteBackend-*.exe") do set "SIDECAR_SRC=%%~fF"
if not defined SIDECAR_SRC (
    echo No sidecar exe found in desktop\src-tauri\bin\BiliNoteBackend
    exit /b 1
)
copy /Y "!SIDECAR_SRC!" "%OUT_DIR%\BiliNoteBackend.exe" >nul
for %%S in ("!SIDECAR_SRC!") do set "SIDECAR_SIZE=%%~zS"
for %%D in ("%OUT_DIR%\BiliNoteBackend.exe") do set "COPIED_SIZE=%%~zD"
if not "!SIDECAR_SIZE!"=="!COPIED_SIZE!" (
    echo Size mismatch: source=!SIDECAR_SIZE! copied=!COPIED_SIZE! - copy failed!
    exit /b 1
)
echo Sidecar copied and verified: !COPIED_SIZE! bytes

robocopy desktop\src-tauri\bin\BiliNoteBackend\_internal "%OUT_DIR%\_internal" /E /NFL /NDL /NJH /NJS >nul
type nul > "%OUT_DIR%\portable.flag"

REM Optional CUDA pack (cuBLAS/cuDNN DLLs) so GPU transcription works without torch.
REM Skipped with: set BILINOTE_SKIP_CUDA=1   Source can be overridden with arg %1.
if defined BILINOTE_SKIP_CUDA (
    echo Skipping CUDA pack ^(BILINOTE_SKIP_CUDA is set^). GPU transcription stays disabled.
) else (
    set "CUDA_SRC_ARG=%~1"
    if defined CUDA_SRC_ARG (
        call desktop\scripts\build_cuda_pack.bat "!CUDA_SRC_ARG!" "%OUT_DIR%\cuda"
    ) else (
        call desktop\scripts\build_cuda_pack.bat "" "%OUT_DIR%\cuda"
    )
    if errorlevel 1 (
        echo CUDA pack assembly failed - continuing without GPU support.
    )
)

echo Assembled portable folder:
dir "%OUT_DIR%"

echo Creating zip archive (this may take a minute)...
powershell -NoProfile -Command "Compress-Archive -Path '%OUT_DIR%\*' -DestinationPath 'desktop\dist-portable\BiliNote-Desktop-Portable-x64.zip' -Force"

echo.
echo Portable build finished:
dir desktop\dist-portable
echo.
echo Usage: extract the folder anywhere (ASCII path recommended), run BiliNote.exe.
endlocal
