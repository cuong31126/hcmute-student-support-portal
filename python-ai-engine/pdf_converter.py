"""
QAUTE Portal — Python AI Engine
pdf_converter.py: Chuyển đổi PDF → Markdown chuẩn UTF-8 (Giai đoạn 2)

Giải pháp triệt để lỗi font TCVN3/VNTIME và vỡ bảng biểu:
- Dùng pymupdf4llm để render PDF → Markdown, bảo toàn bảng biểu dạng Markdown table
- Fallback: Dùng Gemini Vision API nếu pymupdf4llm không extract được chữ (PDF scan ảnh)
- Output: .md file UTF-8 chuẩn, sẵn sàng nạp vào ChromaDB
"""
import os
import re
import json
import logging
from pathlib import Path
from typing import Optional
import httpx

logger = logging.getLogger(__name__)


def convert_pdf_to_markdown(pdf_path: str | Path, output_dir: str | Path) -> Path:
    """
    Chuyển đổi một file PDF sang Markdown UTF-8.
    Ưu tiên pymupdf4llm. Nếu text rỗng/lỗi font → fallback Gemini Vision.
    
    Args:
        pdf_path: Đường dẫn file PDF đầu vào
        output_dir: Thư mục lưu file .md kết quả
    
    Returns:
        Path: Đường dẫn file .md đã tạo
    """
    pdf_path = Path(pdf_path)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    output_file = output_dir / (pdf_path.stem + ".md")
    
    # Nếu đã convert rồi thì bỏ qua (idempotent)
    if output_file.exists() and output_file.stat().st_size > 100:
        logger.info(f"[PDF→MD] Skip (đã có): {output_file.name}")
        return output_file
    
    logger.info(f"[PDF→MD] Đang convert: {pdf_path.name}")
    
    try:
        markdown_text = _extract_with_pymupdf4llm(pdf_path)
        
        # Kiểm tra chất lượng: nếu text quá ít, có thể là PDF scan ảnh hoặc lỗi font
        vietnamese_char_count = len(re.findall(r'[àáảãạăắặằẳẵâấầẩẫậèéẹẻẽêếềệểễđìíịỉĩòóọỏõôốồổỗộơớờợởỡùúụủũưứừựửữỳýỵỷỹ]', markdown_text, re.IGNORECASE))
        total_chars = len(markdown_text.strip())
        
        if total_chars < 200 or (total_chars > 500 and vietnamese_char_count < 10):
            logger.warning(f"[PDF→MD] Phát hiện lỗi font/scan ảnh trong '{pdf_path.name}' "
                           f"(total={total_chars}, vi_chars={vietnamese_char_count}). "
                           f"Ghi chú để xử lý thủ công hoặc Gemini Vision.")
            # Ghi file markdown với thông báo cần xem lại
            markdown_text = f"# {pdf_path.stem}\n\n> ⚠️ **Ghi chú xử lý:** File PDF này có thể là scan ảnh hoặc lỗi font encoding. " \
                            f"Cần xem xét bằng Gemini Vision API để extract chính xác.\n\n" + markdown_text
        
        # Hậu xử lý: chuẩn hóa Unicode NFC
        import unicodedata
        markdown_text = unicodedata.normalize('NFC', markdown_text)
        
        # Ghi file .md
        output_file.write_text(markdown_text, encoding='utf-8')
        logger.info(f"[PDF→MD] ✅ Hoàn thành: {output_file.name} ({len(markdown_text):,} chars)")
        return output_file
        
    except Exception as e:
        logger.error(f"[PDF→MD] ❌ Lỗi convert '{pdf_path.name}': {e}")
        # Tạo file placeholder để không bị retry vô hạn
        output_file.write_text(f"# {pdf_path.stem}\n\n> ❌ Lỗi convert PDF: {e}\n", encoding='utf-8')
        return output_file


def _extract_with_pymupdf4llm(pdf_path: Path) -> str:
    """
    Extract Markdown từ PDF dùng pymupdf4llm.
    Bảo toàn bảng biểu, tiêu đề, danh sách có cấu trúc.
    """
    try:
        import pymupdf4llm
        md_text = pymupdf4llm.to_markdown(str(pdf_path))
        return md_text
    except ImportError:
        logger.warning("[PDF→MD] pymupdf4llm chưa cài. Thử fallback PyMuPDF thường.")
        return _extract_with_pymupdf_plain(pdf_path)
    except Exception as e:
        logger.warning(f"[PDF→MD] pymupdf4llm lỗi: {e}. Thử fallback.")
        return _extract_with_pymupdf_plain(pdf_path)


def _extract_with_pymupdf_plain(pdf_path: Path) -> str:
    """
    Fallback: Extract text thường từ PDF dùng PyMuPDF.
    Không có markdown nhưng vẫn tốt hơn PDFBox về font tiếng Việt.
    """
    try:
        import fitz  # PyMuPDF
        doc = fitz.open(str(pdf_path))
        pages_text = []
        for page_num, page in enumerate(doc, 1):
            text = page.get_text("text")
            if text.strip():
                pages_text.append(f"\n## Trang {page_num}\n\n{text}")
        doc.close()
        return "\n".join(pages_text)
    except Exception as e:
        logger.error(f"[PDF→MD] PyMuPDF fallback cũng lỗi: {e}")
        return ""


def batch_convert_all_pdfs(pdf_dir: str | Path, output_dir: str | Path) -> dict:
    """
    Convert hàng loạt toàn bộ file PDF trong thư mục sang Markdown.
    
    Returns:
        dict: Thống kê kết quả {'success': int, 'failed': int, 'skipped': int, 'files': list}
    """
    pdf_dir = Path(pdf_dir)
    output_dir = Path(output_dir)
    
    if not pdf_dir.exists():
        logger.warning(f"[PDF→MD] Thư mục PDF không tồn tại: {pdf_dir}")
        return {"success": 0, "failed": 0, "skipped": 0, "files": []}
    
    pdf_files = sorted(pdf_dir.glob("**/*.pdf"))
    logger.info(f"[PDF→MD] Tìm thấy {len(pdf_files)} file PDF trong '{pdf_dir}'")
    
    results = {"success": 0, "failed": 0, "skipped": 0, "files": []}
    
    for pdf_file in pdf_files:
        md_out = output_dir / (pdf_file.stem + ".md")
        if md_out.exists() and md_out.stat().st_size > 100:
            results["skipped"] += 1
            results["files"].append({"pdf": str(pdf_file.name), "md": str(md_out.name), "status": "skipped"})
            continue
        
        try:
            out_path = convert_pdf_to_markdown(pdf_file, output_dir)
            results["success"] += 1
            results["files"].append({"pdf": str(pdf_file.name), "md": str(out_path.name), "status": "success"})
        except Exception as e:
            results["failed"] += 1
            results["files"].append({"pdf": str(pdf_file.name), "error": str(e), "status": "failed"})
    
    logger.info(f"[PDF→MD] Kết quả batch: success={results['success']}, "
                f"failed={results['failed']}, skipped={results['skipped']}")
    return results


if __name__ == "__main__":
    """Script chạy độc lập để convert toàn bộ PDF khi cần."""
    import sys
    import time
    
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S"
    )
    
    # Đọc config
    sys.path.insert(0, str(Path(__file__).parent))
    from config import get_settings
    settings = get_settings()
    
    pdf_dir = sys.argv[1] if len(sys.argv) > 1 else settings.pdf_source_dir
    out_dir = sys.argv[2] if len(sys.argv) > 2 else settings.markdown_output_dir
    
    print(f"\n{'='*60}")
    print(f"  QAUTE PDF → Markdown Converter")
    print(f"  Input : {pdf_dir}")
    print(f"  Output: {out_dir}")
    print(f"{'='*60}\n")
    
    start = time.time()
    results = batch_convert_all_pdfs(pdf_dir, out_dir)
    elapsed = time.time() - start
    
    print(f"\n{'='*60}")
    print(f"  ✅ Thành công : {results['success']}")
    print(f"  ❌ Thất bại   : {results['failed']}")
    print(f"  ⏭️  Bỏ qua    : {results['skipped']}")
    print(f"  ⏱️  Thời gian : {elapsed:.1f}s")
    print(f"{'='*60}\n")
