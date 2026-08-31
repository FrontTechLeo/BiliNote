"""运行环境探测：CUDA / torch / ctranslate2。

桌面端打包版为控制体积不携带 torch，CUDA 检测必须在没有 torch 时也能工作。
faster-whisper 的实际推理后端是 ctranslate2，它自带 CUDA 支持：
get_cuda_device_count() 只依赖 NVIDIA 驱动，不依赖 cuDNN/cuBLAS（推理时才加载）。
因此探测分两层：
  - is_cuda_available：驱动层可见 GPU（torch 或 ctranslate2 任一探测到即算）
  - cuda_inference_ready：推理层就绪（CUDA 运行库能加载）
"""
import os
import shutil
import subprocess
from typing import Optional, Union


def is_torch_installed() -> bool:
    try:
        import torch
        return True
    except ImportError:
        return False


def is_ctranslate2_available() -> bool:
    try:
        import ctranslate2
        return True
    except ImportError:
        return False


def _torch_cuda_available() -> Optional[bool]:
    """torch 视角 CUDA 是否可用；未装 torch 返回 None。"""
    try:
        import torch
        return torch.cuda.is_available()
    except Exception:
        return None


def _ctranslate2_cuda_count() -> Optional[int]:
    """ctranslate2 视角可见的 CUDA 设备数；未装或驱动不可用返回 None。"""
    try:
        import ctranslate2
        return int(ctranslate2.get_cuda_device_count())
    except Exception:
        return None


def is_cuda_available() -> bool:
    torch_view = _torch_cuda_available()
    if torch_view is not None:
        return bool(torch_view)
    ct2_count = _ctranslate2_cuda_count()
    if ct2_count is not None:
        return ct2_count > 0
    return False


def cuda_backend() -> str:
    """当前 CUDA 探测来源：'torch' | 'ctranslate2' | ''（都不可用）。"""
    if _torch_cuda_available() is not None:
        return "torch"
    if _ctranslate2_cuda_count() is not None:
        return "ctranslate2"
    return ""


def cuda_inference_ready() -> bool:
    """GPU 推理所需的运行库（cuBLAS/cuDNN）是否可加载。

    Windows 上桌面端不带 torch，需要 CUDA 加速包（cublas/cudnn DLL，见
    runtime_paths.get_cuda_dll_dir / desktop/scripts/assemble_cuda_pack.bat）
    才能真正用 GPU 转写；没有加速包时返回 False，转写自动走 CPU。
    非 Windows 交给动态链接器，能探测到设备即视为就绪。
    """
    if not is_cuda_available():
        return False
    if os.name != "nt":
        return True
    if is_torch_installed():
        # torch 自带并注册 cuBLAS/cuDNN（import torch 时加载），跟着 torch 走即可
        return True
    import ctypes
    for name in ("cublas64_12.dll", "cudnn64_9.dll"):
        try:
            ctypes.WinDLL(name)
        except OSError:
            return False
    return True


def gpu_name() -> Optional[str]:
    torch_view = _torch_cuda_available()
    if torch_view:
        try:
            import torch
            return torch.cuda.get_device_name(0)
        except Exception:
            pass
    # 无 torch：退回 nvidia-smi（随 NVIDIA 驱动安装）
    smi = shutil.which("nvidia-smi")
    if smi:
        try:
            out = subprocess.run(
                [smi, "--query-gpu=name", "--format=csv,noheader"],
                capture_output=True, text=True, timeout=5,
            )
            name = (out.stdout or "").strip().splitlines()[0].strip() if out.stdout else ""
            return name or None
        except Exception:
            return None
    return None
