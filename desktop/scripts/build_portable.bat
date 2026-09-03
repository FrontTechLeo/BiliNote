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
REM
REM Keep this file CRLF + ASCII only: cmd re-reads parenthesized blocks by
REM byte offset and mis-parses LF endings, and UTF-8 Chinese comments turn
REM into mojibake junk commands on a GBK console (both bit us already).
REM Close the running BiliNote.exe first, otherwise copying the exe fails.
REM ============================================================

cd /d %~dp0..\..
echo Working directory: %cd%

if not exist desktop\src-tauri\bin\BiliNoteBackend\BiliNoteBackend-*.exe (
    echo Sidecar missing! Run desktop\scripts\build_sidecar.bat first.
    exit /b 1
)

REM The shell must be rebuilt through the Tauri CLI every time: `pnpm tauri
REM build` runs beforeBuildCommand (pnpm build = tsc + vite) to refresh dist,
REM then compiles with the custom-protocol feature so dist gets embedded in
REM the exe. An exe produced by a bare `cargo build` lacks that feature and at
REM runtime loads the devUrl (localhost:5183), showing a connection-error page.
REM The old "skip build if exe exists" shortcut packaged such a broken exe
REM silently. Skip only with: set BILINOTE_SKIP_TAURI_BUILD=1
if defined BILINOTE_SKIP_TAURI_BUILD goto skip_tauri_build

echo Building frontend + Tauri shell: pnpm tauri build --no-bundle
pushd desktop
call pnpm tauri build --no-bundle
if errorlevel 1 (
    popd
    exit /b 1
)
popd

:skip_tauri_build
if not exist desktop\src-tauri\target\release\bilinote-desktop.exe (
    echo Tauri shell exe missing - cannot assemble.
    exit /b 1
)

set "OUT_DIR=desktop\dist-portable\BiliNote-Portable-x64"

REM --- Refresh program files in place, keep the portable data dir ----------
REM In portable mode the data root is data\ next to the exe (see
REM default_data_root in src-tauri\src\lib.rs); it holds notes, provider
REM config, downloaded Whisper models, logs and settings.
REM
REM The early version did `rmdir /s /q dist-portable` and rebuilt the whole
REM tree, wiping the user's 1GB+ of models. We now delete program files only
REM and never touch data\ - and we deliberately do NOT move data away and
REM back, because an interrupted build would strand it in a temp dir.
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"
echo Refreshing program files (data\ dir is preserved) ...
if exist "%OUT_DIR%\BiliNote.exe" del /q "%OUT_DIR%\BiliNote.exe"
if exist "%OUT_DIR%\BiliNoteBackend.exe" del /q "%OUT_DIR%\BiliNoteBackend.exe"
if exist "%OUT_DIR%\portable.flag" del /q "%OUT_DIR%\portable.flag"
if exist "%OUT_DIR%\_internal" rmdir /s /q "%OUT_DIR%\_internal"
if exist "%OUT_DIR%\cuda" rmdir /s /q "%OUT_DIR%\cuda"

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

REM Optional CUDA pack (cuBLAS/cuDNN DLLs) so GPU transcription works without
REM torch. Skipped with: set BILINOTE_SKIP_CUDA=1
REM Source dir can be overridden with the first argument.
if defined BILINOTE_SKIP_CUDA (
    echo Skipping CUDA pack ^(BILINOTE_SKIP_CUDA is set^). GPU transcription stays disabled.
    goto after_cuda_pack
)
set "CUDA_SRC_ARG=%~1"
if defined CUDA_SRC_ARG (
    call desktop\scripts\build_cuda_pack.bat "!CUDA_SRC_ARG!" "%OUT_DIR%\cuda"
) else (
    call desktop\scripts\build_cuda_pack.bat "" "%OUT_DIR%\cuda"
)
if errorlevel 1 (
    echo CUDA pack assembly failed - continuing without GPU support.
)

:after_cuda_pack
echo Assembled portable folder:
dir "%OUT_DIR%"

echo Creating zip archive (this may take a minute)...
REM Exclude data\ from the distribution: ship programs only, never the local
REM notes/models/settings (privacy, plus it can easily exceed 1GB).
powershell -NoProfile -Command "$items = Get-ChildItem -LiteralPath '%OUT_DIR%' -Force | Where-Object { $_.Name -ne 'data' } | ForEach-Object { $_.FullName }; if ($items) { Compress-Archive -LiteralPath $items -DestinationPath 'desktop\dist-portable\BiliNote-Desktop-Portable-x64.zip' -Force } else { Write-Host 'nothing to archive' }"
if errorlevel 1 echo WARNING: zip creation failed, portable folder is still usable.

echo.
echo Portable build finished:
dir desktop\dist-portable
echo.
echo Usage: extract the folder anywhere (ASCII path recommended), run BiliNote.exe.
endlocal
