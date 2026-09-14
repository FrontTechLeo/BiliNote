"""回归：转写器单例并发击穿 + GPU 转写并发限流。

历史事故：批量任务 3 个 worker 同时首次取转写器，check-then-set 无锁导致创建 3 份
whisper-medium，6GB 显存被顶满后转写静默卡死（任务永远停在 TRANSCRIBING）。
"""
import importlib.util
import pathlib
import shutil
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any, List
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]


def _load(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    if spec is None or spec.loader is None:
        raise ImportError(f"module spec not found: {relative}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


transcriber_provider = _load("transcriber_provider_under_test", "app/transcriber/transcriber_provider.py")
whisper_module = _load("whisper_under_test", "app/transcriber/whisper.py")


class _SlowTranscriber:
    instances = 0
    instances_lock = threading.Lock()

    def __init__(self, *args, **kwargs):
        with _SlowTranscriber.instances_lock:
            _SlowTranscriber.instances += 1
        time.sleep(0.2)


class TestTranscriberSingleton(unittest.TestCase):
    def setUp(self):
        _SlowTranscriber.instances = 0

    def test_concurrent_init_creates_single_instance(self):
        with patch.object(transcriber_provider, "_transcribers", {
            transcriber_provider.TranscriberType.FAST_WHISPER: None,
        }), patch.object(transcriber_provider, "WhisperTranscriber", _SlowTranscriber):
            results: List[Any] = []
            threads = [
                threading.Thread(
                    target=lambda: results.append(
                        transcriber_provider.get_whisper_transcriber("base", device="cpu")
                    )
                )
                for _ in range(3)
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

            self.assertEqual(_SlowTranscriber.instances, 1)
            self.assertEqual(len(results), 3)
            self.assertIs(results[0], results[1])
            self.assertIs(results[1], results[2])

    def test_failed_init_does_not_cache_broken_instance(self):
        class _Boom:
            def __init__(self, *args, **kwargs):
                raise RuntimeError("load failed")

        with patch.object(transcriber_provider, "_transcribers", {
            transcriber_provider.TranscriberType.FAST_WHISPER: None,
        }):
            with patch.object(transcriber_provider, "WhisperTranscriber", _Boom):
                with self.assertRaises(RuntimeError):
                    transcriber_provider.get_whisper_transcriber("base", device="cpu")
                self.assertIsNone(
                    transcriber_provider._transcribers[transcriber_provider.TranscriberType.FAST_WHISPER]
                )


class _FakeSegment:
    def __init__(self, text: str, start: float, end: float):
        self.text = text
        self.start = start
        self.end = end


class _RecordingModel:
    """记录推理时的并发峰值，模拟 GPU 上耗时 0.2s 的转写。"""

    def __init__(self):
        self.lock = threading.Lock()
        self.active = 0
        self.peak = 0

    def transcribe(self, file_path: str):
        with self.lock:
            self.active += 1
            self.peak = max(self.peak, self.active)
        start = time.time()
        while time.time() - start < 0.2:
            time.sleep(0.02)
        with self.lock:
            self.active -= 1

        def segments():
            yield _FakeSegment("hello", 0.0, 1.0)

        return segments(), type("Info", (), {"language": "en"})()


def _make_transcriber(device: str) -> whisper_module.WhisperTranscriber:
    instance = whisper_module.WhisperTranscriber.__new__(whisper_module.WhisperTranscriber)
    instance.device = device
    instance.compute_type = "float32" if device == "cuda" else "int8"
    instance.model_size = "base"
    instance.model = _RecordingModel()
    return instance


class TestTranscribeGate(unittest.TestCase):
    def setUp(self):
        while whisper_module._transcribe_slots._value < whisper_module._TRANSCRIBE_CONCURRENCY:
            whisper_module._transcribe_slots.release()
        while whisper_module._transcribe_slots._value > whisper_module._TRANSCRIBE_CONCURRENCY:
            whisper_module._transcribe_slots.acquire(blocking=False)

    def test_transcriptions_are_serialized(self):
        model = _RecordingModel()
        transcriber = _make_transcriber("cuda")
        transcriber.model = model

        threads = [
            threading.Thread(target=lambda: transcriber.transcript("fake.wav"))
            for _ in range(3)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(model.peak, 1)

    def test_cpu_transcriptions_are_serialized_too(self):
        model = _RecordingModel()
        transcriber = _make_transcriber("cpu")
        transcriber.model = model

        threads = [
            threading.Thread(target=lambda: transcriber.transcript("fake.wav"))
            for _ in range(3)
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(model.peak, 1)

    def test_serial_across_instances(self):
        # 不同批量任务（合集）各自拿到的是不同实例时，转写依旧全局串行。
        first_model = _RecordingModel()
        second_model = _RecordingModel()
        transcribers = [_make_transcriber("cuda"), _make_transcriber("cuda")]
        transcribers[0].model = first_model
        transcribers[1].model = second_model
        threads = [threading.Thread(target=lambda t=t: t.transcript("fake.wav")) for t in transcribers]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(first_model.peak + second_model.peak, 2)


class TestWhisperCachePurge(unittest.TestCase):
    """加载失败时的自愈清理只允许删「不完整」的 cache。

    回归背景：批量任务 3 个 worker 并发首次创建转写器时踩中 huggingface_hub 的
    disabled_tqdm 竞争 bug，旧逻辑把任何异常都当 cache 损坏，rmtree 整个模型目录，
    把用户手动拷贝的完整 whisper-medium 删掉（refs/main 都没有 snapshots）。
    """

    SNAPSHOT_HASH = "08e178d48790749d25932bbc082711ddcfdfbc4f"

    def setUp(self):
        self.tmpdir = Path(tempfile.mkdtemp(prefix="bili_whisper_purge_"))
        self.repo_dir = self.tmpdir / "models--Systran--faster-whisper-medium"

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def _broken_model_patch(self, exc):
        class _BrokenModel:
            def __init__(self, *args, **kwargs):
                raise exc

        return (
            patch.object(whisper_module, "get_model_dir", return_value=str(self.tmpdir)),
            patch.object(whisper_module, "WhisperModel", _BrokenModel),
        )

    def test_complete_cache_is_never_purged(self):
        self._write_complete_cache()
        exc = AttributeError("type object 'disabled_tqdm' has no attribute '_lock'")
        patch_dir, patch_model = self._broken_model_patch(exc)
        with patch_dir, patch_model:
            with self.assertRaises(RuntimeError):
                whisper_module.WhisperTranscriber(model_size="medium", device="cpu")
        model_bin = self.repo_dir / "snapshots" / self.SNAPSHOT_HASH / "model.bin"
        self.assertTrue(model_bin.exists(), "完整模型目录被误删")

    def test_refs_only_cache_is_purged_with_actionable_error(self):
        self._write_refs_only_cache()
        try:
            from huggingface_hub.errors import LocalEntryNotFoundError
        except ImportError:
            self.skipTest("huggingface_hub 未安装")
        exc = LocalEntryNotFoundError(
            "Cannot find an appropriate cached snapshot folder for the specified revision "
            "on the local disk and outgoing traffic has been disabled."
        )
        patch_dir, patch_model = self._broken_model_patch(exc)
        with patch_dir, patch_model:
            with self.assertRaises(RuntimeError) as ctx:
                whisper_module.WhisperTranscriber(model_size="medium", device="cpu")
        self.assertFalse(self.repo_dir.exists(), "半成品 cache 应被清理以便重下")
        self.assertIn("未下载且联网下载失败", str(ctx.exception))

    def _write_complete_cache(self):
        snap = self.repo_dir / "snapshots" / self.SNAPSHOT_HASH
        snap.mkdir(parents=True, exist_ok=True)
        (snap / "model.bin").write_bytes(b"fake-model")

    def _write_refs_only_cache(self):
        refs = self.repo_dir / "refs"
        refs.mkdir(parents=True, exist_ok=True)
        (refs / "main").write_text(self.SNAPSHOT_HASH)


class TestInitFailureNegativeCache(unittest.TestCase):
    """创建失败负缓存：N 个 worker 并发取转写器时，失败只允许发生一轮。"""

    def _patched(self, boom_cls):
        return (
            patch.object(transcriber_provider, "_transcribers", {
                transcriber_provider.TranscriberType.FAST_WHISPER: None,
            }),
            patch.object(transcriber_provider, "_init_failures", {}),
            patch.object(transcriber_provider, "WhisperTranscriber", boom_cls),
        )

    def test_failure_is_shared_across_workers_within_ttl(self):
        class _Boom:
            calls = 0
            lock = threading.Lock()

            def __init__(self, *args, **kwargs):
                with _Boom.lock:
                    _Boom.calls += 1
                raise RuntimeError("load failed")

        errors: List[BaseException] = []

        def worker():
            try:
                transcriber_provider.get_whisper_transcriber("base", device="cpu")
            except RuntimeError as exc:
                errors.append(exc)

        patch_transcribers, patch_failures, patch_cls = self._patched(_Boom)
        with patch_transcribers, patch_failures, patch_cls:
            threads = [threading.Thread(target=worker) for _ in range(3)]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()

        self.assertEqual(_Boom.calls, 1, "TTL 内的重复创建应被失败负缓存短路")
        self.assertEqual(len(errors), 3)
        self.assertIs(errors[0], errors[2], "各 worker 应复用同一个失败错误")

    def test_reset_transcriber_allows_retry_after_failure(self):
        class _Boom:
            calls = 0

            def __init__(self, *args, **kwargs):
                _Boom.calls += 1
                raise RuntimeError("load failed")

        patch_transcribers, patch_failures, patch_cls = self._patched(_Boom)
        with patch_transcribers, patch_failures, patch_cls:
            with self.assertRaises(RuntimeError):
                transcriber_provider.get_whisper_transcriber("base", device="cpu")
            transcriber_provider.reset_transcriber(transcriber_provider.TranscriberType.FAST_WHISPER)
            with self.assertRaises(RuntimeError):
                transcriber_provider.get_whisper_transcriber("base", device="cpu")

        self.assertEqual(_Boom.calls, 2, "reset 后应清掉失败标记，允许重新创建")


if __name__ == "__main__":
    unittest.main()
