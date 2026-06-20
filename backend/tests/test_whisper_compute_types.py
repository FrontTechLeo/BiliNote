"""Test compute_types for GTX 1660 Ti (one at a time)."""
import sys, os, time, gc
sys.path.insert(0, os.path.dirname(__file__))

from faster_whisper import WhisperModel

target = "Systran/faster-whisper-small"
model_dir = os.path.join(os.path.dirname(__file__), "models", "whisper")
audio = os.path.join(os.path.dirname(__file__), "data", "data", "BV1fHQdYoEq5_p56.mp3")
import torch

# Pick ONE compute_type to test (change this line)
CT = sys.argv[1] if len(sys.argv) > 1 else "int8_float16"

print(f"=== compute_type={CT}, GPU={torch.cuda.get_device_name(0)} ===", flush=True)
try:
    model = WhisperModel(target, device="cuda", compute_type=CT, download_root=model_dir, cpu_threads=4)
    start = time.time()
    segs_gen, info = model.transcribe(audio)
    segs = list(segs_gen)
    elapsed = time.time() - start
    text = " ".join(s.text.strip() for s in segs)
    print(f"lang={info.language}, chars={len(text)}, segs={len(segs)}, time={elapsed:.1f}s", flush=True)
    if text:
        print(f"first 100: {text[:100]}", flush=True)
    else:
        print("EMPTY RESULT", flush=True)
except Exception as e:
    print(f"FAILED: {e}", flush=True)
    import traceback; traceback.print_exc()
