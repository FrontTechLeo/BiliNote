"""测试 Whisper CUDA GPU 转写加速是否生效（含优化参数验证）。

本测试是集成测试（非 mock），需要：
  - CUDA 版 PyTorch（torch.cuda.is_available() == True）
  - faster-whisper 及对应模型（首次运行会自动下载）
  - 测试音频文件

运行方式（在 backend/ 目录下）：
  python -m unittest tests.test_whisper_cuda.TestWhisperCuda.test_transcribe_cuda -v
"""

import os
import time
import unittest

from app.transcriber.whisper import WhisperTranscriber
from app.utils.env_checker import is_cuda_available, is_torch_installed

TEST_AUDIO = os.path.join(
    os.path.dirname(__file__), "..", "data", "data", "BV1oMJ76bE73.mp3"
)


class TestWhisperCuda(unittest.TestCase):
    """验证 CUDA GPU 转写加速是否生效。"""

    @classmethod
    def setUpClass(cls):
        assert os.path.exists(TEST_AUDIO), f"测试音频文件不存在: {TEST_AUDIO}"
        file_size_mb = os.path.getsize(TEST_AUDIO) / (1024 * 1024)
        print(f"\n测试音频: {TEST_AUDIO} ({file_size_mb:.1f} MB)")

    def test_cuda_available(self):
        """前置检查：CUDA 可用性"""
        self.assertTrue(
            is_torch_installed(),
            "PyTorch 未安装，请先执行: pip install torch --index-url https://download.pytorch.org/whl/cu126",
        )
        self.assertTrue(
            is_cuda_available(),
            "torch.cuda.is_available() 返回 False，CUDA 未就绪。请检查驱动 / CUDA 版 PyTorch 安装",
        )
        import torch

        print(f"  PyTorch 版本: {torch.__version__}")
        print(f"  CUDA 版本:    {torch.version.cuda}")
        print(f"  GPU:          {torch.cuda.get_device_name(0)}")

    def test_transcribe_cpu(self):
        """CPU 基线：记录纯 CPU 转写耗时"""
        print(f"\n  [CPU] 开始转写，模型: small")
        transcriber = WhisperTranscriber(model_size="small", device="cpu")
        self.assertEqual(transcriber.device, "cpu")
        self.assertEqual(transcriber.compute_type, "int8")

        start = time.perf_counter()
        result = transcriber.transcript(TEST_AUDIO)
        elapsed = time.perf_counter() - start

        print(f"  [CPU] 耗时: {elapsed:.2f}s")
        print(f"  [CPU] 语言: {result.language}")
        print(f"  [CPU] 文本长度: {len(result.full_text)} 字")
        print(f"  [CPU] 段落: {len(result.segments)} 段")
        self.assertIsNotNone(result)
        self.assertGreater(len(result.full_text), 0)

    def test_transcribe_cuda(self):
        """CUDA 转写：验证 device=cuda、float32 精度且结果正确"""
        print(f"\n  [CUDA] 开始转写，模型: small")
        transcriber = WhisperTranscriber(model_size="small", device="cuda")
        self.assertEqual(transcriber.device, "cuda")
        self.assertEqual(transcriber.compute_type, "float32")

        start = time.perf_counter()
        result = transcriber.transcript(TEST_AUDIO)
        elapsed = time.perf_counter() - start

        print(f"  [CUDA] 耗时: {elapsed:.2f}s")
        print(f"  [CUDA] 语言: {result.language}")
        print(f"  [CUDA] 文本长度: {len(result.full_text)} 字")
        print(f"  [CUDA] 段落: {len(result.segments)} 段")
        print(f"  [CUDA] 前 200 字: {result.full_text[:200]}")
        self.assertIsNotNone(result)
        self.assertGreater(len(result.full_text), 0)

    def test_cuda_faster_than_cpu(self):
        """CPU vs CUDA 对比（不严格断言，只输出对比数据）"""
        cpu_transcriber = WhisperTranscriber(model_size="small", device="cpu")
        cuda_transcriber = WhisperTranscriber(model_size="small", device="cuda")

        start = time.perf_counter()
        cpu_transcriber.transcript(TEST_AUDIO)
        cpu_time = time.perf_counter() - start

        start = time.perf_counter()
        cuda_transcriber.transcript(TEST_AUDIO)
        cuda_time = time.perf_counter() - start

        ratio = cpu_time / cuda_time if cuda_time > 0 else float("inf")
        print(f"\n  CPU 耗时:   {cpu_time:.2f}s")
        print(f"  CUDA 耗时:  {cuda_time:.2f}s")
        print(f"  加速比:     {ratio:.1f}x")
        print(f"  CPU 参数:   device=cpu, compute=int8, batch_size=1")
        print(f"  CUDA 参数:  device=cuda, compute=float16, batch_size=16, cpu_threads=4, num_workers=2")

        if ratio < 1.0:
            print("  ⚠ CUDA 未快于 CPU")


if __name__ == "__main__":
    unittest.main()
