#!/usr/bin/env python3
"""
QAUTE Portal — Python AI Engine
run_pipeline.py: Script tự động chạy toàn bộ pipeline từ PDF → Vector Store → Test

Sử dụng:
  python run_pipeline.py                  # Chạy tất cả giai đoạn
  python run_pipeline.py --phase 1        # Chỉ chạy Phase 1 (test core)
  python run_pipeline.py --phase 2        # Chỉ convert PDF
  python run_pipeline.py --phase 3        # Chỉ ingest markdown
  python run_pipeline.py --phase 4        # Chỉ start server
"""
import sys
import time
import subprocess
import argparse
import logging
from pathlib import Path

# Fix Windows console UTF-8 output
if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("pipeline")

ROOT = Path(__file__).parent

# ── ANSI Colors (Windows compat) ────────────────────────────────────────────────
try:
    import ctypes
    ctypes.windll.kernel32.SetConsoleMode(ctypes.windll.kernel32.GetStdHandle(-11), 7)
    GREEN = "\033[92m"
    RED = "\033[91m"
    YELLOW = "\033[93m"
    BLUE = "\033[94m"
    CYAN = "\033[96m"
    BOLD = "\033[1m"
    RESET = "\033[0m"
except Exception:
    GREEN = RED = YELLOW = BLUE = CYAN = BOLD = RESET = ""


def banner(title: str, color: str = BLUE):
    width = 60
    print(f"\n{color}{BOLD}{'='*width}{RESET}")
    print(f"{color}{BOLD}  {title}{RESET}")
    print(f"{color}{BOLD}{'='*width}{RESET}\n")


def step_ok(msg: str):
    print(f"  {GREEN}✅ {msg}{RESET}")


def step_warn(msg: str):
    print(f"  {YELLOW}⚠️  {msg}{RESET}")


def step_err(msg: str):
    print(f"  {RED}❌ {msg}{RESET}")


# ── Phase 0: Kiểm tra môi trường ─────────────────────────────────────────────

def phase0_check_env():
    banner("Phase 0: Kiểm tra môi trường", CYAN)
    
    ok = True
    
    # Python version
    import sys
    ver = sys.version_info
    if ver.major >= 3 and ver.minor >= 9:
        step_ok(f"Python {ver.major}.{ver.minor}.{ver.micro}")
    else:
        step_err(f"Python {ver.major}.{ver.minor} — Cần >= 3.9")
        ok = False
    
    # Kiểm tra packages
    packages = ["fastapi", "uvicorn", "chromadb", "httpx", "pydantic_settings"]
    for pkg in packages:
        try:
            __import__(pkg)
            step_ok(f"Package '{pkg}' OK")
        except ImportError:
            step_err(f"Package '{pkg}' chưa cài. Chạy: pip install -r requirements.txt")
            ok = False
    
    # pymupdf4llm (optional)
    try:
        import pymupdf4llm
        step_ok("pymupdf4llm (PDF converter) OK")
    except ImportError:
        step_warn("pymupdf4llm chưa cài — PDF conversion sẽ dùng fallback PyMuPDF")
    
    # .env file
    env_file = ROOT / ".env"
    if env_file.exists():
        step_ok(".env file tồn tại")
    else:
        step_warn(".env chưa có. Copy từ .env.example và điền GEMINI_API_KEY")
    
    # Đọc settings
    sys.path.insert(0, str(ROOT))
    from config import get_settings
    s = get_settings()
    
    if s.gemini_api_key == "demo_key":
        step_warn(f"GEMINI_API_KEY chưa cài — chạy Demo Mode (không cần key)")
    else:
        step_ok(f"GEMINI_API_KEY đã cấu hình (model: {s.gemini_chat_model})")
    
    print()
    return ok


# ── Phase 1: Unit Tests ──────────────────────────────────────────────────────

def phase1_run_tests() -> bool:
    banner("Phase 1: Chạy Unit Tests (pytest)", GREEN)
    
    result = subprocess.run(
        [sys.executable, "-m", "pytest", "tests/test_phase1_api.py", "-v", "--tb=short", "--no-header"],
        cwd=str(ROOT),
        capture_output=False,
        text=True
    )
    
    if result.returncode == 0:
        step_ok("Tất cả tests PASSED!")
        return True
    else:
        step_err(f"Tests FAILED (exit code: {result.returncode})")
        return False


# ── Phase 2: Convert PDF → Markdown ─────────────────────────────────────────

def phase2_convert_pdfs() -> bool:
    banner("Phase 2: Convert PDF → Markdown", YELLOW)
    
    sys.path.insert(0, str(ROOT))
    from config import get_settings
    from pdf_converter import batch_convert_all_pdfs
    
    settings = get_settings()
    pdf_dir = Path(settings.pdf_source_dir)
    out_dir = Path(settings.markdown_output_dir)
    
    if not pdf_dir.exists():
        step_warn(f"Thư mục PDF chưa tồn tại: {pdf_dir}")
        step_warn("Tạo thư mục và đặt file PDF vào đó để convert.")
        print(f"\n  👉 Cách thêm PDF:\n     1. Tạo thư mục: {pdf_dir.resolve()}\n     2. Copy các file PDF công văn vào đó\n     3. Chạy lại: python run_pipeline.py --phase 2\n")
        return True  # Không phải lỗi, chỉ là chưa có PDF
    
    pdf_count = len(list(pdf_dir.glob("**/*.pdf")))
    if pdf_count == 0:
        step_warn(f"Không có file PDF nào trong: {pdf_dir}")
        return True
    
    print(f"  📂 Tìm thấy {pdf_count} file PDF → convert sang Markdown...")
    start = time.time()
    results = batch_convert_all_pdfs(str(pdf_dir), str(out_dir))
    elapsed = time.time() - start
    
    step_ok(f"Convert xong: {results['success']} thành công, {results['failed']} lỗi, {results['skipped']} bỏ qua ({elapsed:.1f}s)")
    return results['failed'] == 0


# ── Phase 3: Ingest Markdown → ChromaDB ─────────────────────────────────────

def phase3_ingest_knowledge() -> bool:
    banner("Phase 3: Nạp Markdown → ChromaDB", YELLOW)
    
    sys.path.insert(0, str(ROOT))
    from config import get_settings
    from rag_engine import RagEngine
    
    settings = get_settings()
    md_dir = Path(settings.markdown_output_dir)
    
    md_count = len(list(md_dir.glob("*.md"))) if md_dir.exists() else 0
    if md_count == 0:
        step_warn(f"Không có file .md trong: {md_dir}")
        step_warn("Chạy Phase 2 trước để convert PDF.")
        return True
    
    print(f"  📄 Tìm thấy {md_count} file .md → nạp vào ChromaDB...")
    
    engine = RagEngine(settings)
    if not engine.initialize():
        step_err("ChromaDB khởi tạo thất bại!")
        return False
    
    start = time.time()
    results = engine.ingest_markdown_dir(str(md_dir))
    elapsed = time.time() - start
    
    stats = engine.get_collection_stats()
    step_ok(f"Ingest xong: {results['ingested']} chunks mới, tổng {stats['count']} chunks ({elapsed:.1f}s)")
    
    # Quick smoke test sau ingest
    if stats['count'] > 0:
        print("\n  🔍 Smoke test: hỏi thử câu hỏi mẫu...")
        result = engine.ask("học phí ngành CNTT năm 2026")
        print(f"  📊 Confidence: {result.confidence_score:.3f}")
        print(f"  💬 Trả lời mẫu: {result.answer[:150]}...")
        step_ok("Smoke test OK")
    
    return True


# ── Phase 4: Start Server ────────────────────────────────────────────────────

def phase4_start_server():
    banner("Phase 4: Khởi động FastAPI Server (cổng 8001)", BLUE)
    
    print("  🚀 Đang khởi động Python AI Engine...")
    print("  📌 Địa chỉ: http://127.0.0.1:8001")
    print("  📌 Swagger: http://127.0.0.1:8001/docs")
    print("  📌 Health : http://127.0.0.1:8001/health")
    print(f"\n  {YELLOW}Bấm Ctrl+C để dừng server{RESET}\n")
    
    import uvicorn
    uvicorn.run(
        "main:app",
        host="127.0.0.1",
        port=8001,
        reload=False,
        log_level="info"
    )


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="QAUTE Python AI Engine Pipeline")
    parser.add_argument("--phase", type=int, choices=[0, 1, 2, 3, 4],
                        help="Chỉ chạy 1 giai đoạn cụ thể (0-4)")
    args = parser.parse_args()
    
    print(f"\n{CYAN}{BOLD}")
    print("  ╔═══════════════════════════════════════════════════╗")
    print("  ║     QAUTE Portal — Python AI Engine Pipeline     ║")
    print("  ║         RAG Migration: Java → Python              ║")
    print("  ╚═══════════════════════════════════════════════════╝")
    print(RESET)
    
    start_total = time.time()
    
    if args.phase is not None:
        # Chỉ chạy 1 phase
        phases = {
            0: ("Kiểm tra môi trường", phase0_check_env),
            1: ("Unit Tests", phase1_run_tests),
            2: ("Convert PDF", phase2_convert_pdfs),
            3: ("Ingest Knowledge", phase3_ingest_knowledge),
            4: ("Start Server", phase4_start_server),
        }
        name, fn = phases[args.phase]
        print(f"  Chạy đơn lẻ: Phase {args.phase} — {name}")
        fn()
    else:
        # Chạy tất cả (trừ Phase 4 — server, phải start thủ công)
        ok = True
        
        ok = phase0_check_env() and ok
        
        if not ok:
            step_err("Môi trường không đáp ứng. Sửa lỗi trên rồi chạy lại.")
            sys.exit(1)
        
        ok = phase1_run_tests() and ok
        phase2_convert_pdfs()
        phase3_ingest_knowledge()
        
        elapsed = time.time() - start_total
        banner(f"Pipeline hoàn thành ({elapsed:.1f}s)", GREEN if ok else RED)
        
        if ok:
            print(f"  {GREEN}✅ Sẵn sàng! Khởi động server bằng:{RESET}")
            print(f"     python run_pipeline.py --phase 4")
            print(f"  hoặc:")
            print(f"     python main.py")
        else:
            print(f"  {RED}❌ Có lỗi cần sửa. Xem chi tiết bên trên.{RESET}")
            sys.exit(1)


if __name__ == "__main__":
    main()
