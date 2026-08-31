@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  Assemble the optional CUDA pack for the BiliNote desktop app.
rem
rem  The packaged desktop backend ships without torch to keep the
rem  bundle small. GPU transcription still works through
rem  ctranslate2, but it needs cuBLAS / cuDNN runtime DLLs at
rem  inference time. This script copies those DLLs from a local
rem  PyTorch installation into a "cuda" folder that sits next to
rem  BiliNote.exe (portable) or the installed bilinote-desktop.exe.
rem
rem  Usage: build_cuda_pack.bat [torch_lib_dir] [target_dir]
rem    torch_lib_dir defaults to the biliNote conda env
rem    target_dir  defaults to desktop\dist-portable\BiliNote-Portable-x64\cuda
rem ============================================================

set "TORCH_LIB=%~1"
set "TARGET=%~2"

if "%TORCH_LIB%"=="" set "TORCH_LIB=E:\ProgramData\.conda\envs\biliNote\Lib\site-packages\torch\lib"
if "%TARGET%"=="" set "TARGET=%~dp0..\dist-portable\BiliNote-Portable-x64\cuda"

if not exist "%TORCH_LIB%\cudnn64_9.dll" (
  echo [ERROR] cudnn64_9.dll not found in "%TORCH_LIB%".
  echo         Pass the torch lib dir as the first argument.
  exit /b 1
)

echo Assembling CUDA pack:
echo   source: %TORCH_LIB%
echo   target: %TARGET%
mkdir "%TARGET%" 2>nul

set COPIED=0
for %%F in (cublas64_12.dll cublasLt64_12.dll cudnn64_9.dll cudnn_adv64_9.dll cudnn_cnn64_9.dll cudnn_engines_precompiled64_9.dll cudnn_engines_runtime_compiled64_9.dll cudnn_graph64_9.dll cudnn_heuristic64_9.dll cudnn_ops64_9.dll nvrtc64_120_0.dll nvrtc64_120_0.alt.dll nvrtc-builtins64_126.dll zlibwapi.dll) do (
  if exist "%TORCH_LIB%\%%F" (
    copy /Y "%TORCH_LIB%\%%F" "%TARGET%\%%F" >nul || exit /b 1
    set /a COPIED+=1
  )
)

if !COPIED! LSS 3 (
  echo [ERROR] Too few CUDA DLLs copied (!COPIED!^). Aborting.
  exit /b 1
)

set TOTAL=0
for %%F in ("%TARGET%\*.dll") do set /a TOTAL+=1
echo [OK] !TOTAL! DLL(s) in "%TARGET%".
echo Done. Restart the BiliNote app to enable GPU transcription.
endlocal
