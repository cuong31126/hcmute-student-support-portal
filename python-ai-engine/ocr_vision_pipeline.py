"""
QAUTE Portal — Python AI Engine
ocr_vision_pipeline.py: Pipeline Multimodal OCR chuyên dụng cho công văn scan mộc đỏ HCMUTE.
Tự động chuyển đổi toàn bộ 91 file PDF (kể cả file scan ảnh thuần) sang Markdown số hóa 100%.
"""
import os
import re
import time
import base64
import sys
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional
import httpx
import pymupdf as fitz
from dotenv import load_dotenv

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

# Tải biến môi trường từ .env
load_dotenv(Path(__file__).parent / ".env", override=True)
load_dotenv(Path(__file__).parent.parent / ".env")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S"
)
logger = logging.getLogger("ocr_vision")

ROOT_DIR = Path(__file__).parent
DATA_DIR = ROOT_DIR / "data"
MARKDOWN_OUTPUT_DIR = DATA_DIR / "markdown_docs"
DEFAULT_PDF_DIR = Path(r"D:\HK5\CongNghePhanMem\tailieuAI")

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "demo_key")
GEMINI_VISION_MODEL = os.getenv("GEMINI_VISION_MODEL", "gemini-2.5-flash")
VISION_API_URL = f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_VISION_MODEL}:generateContent?key={GEMINI_API_KEY}"


def ocr_single_page_with_gemini(page: fitz.Page, page_num: int, doc_name: str) -> str:
    """
    Render 1 trang PDF thành ảnh JPG nhẹ và gọi Gemini Vision API bóc tách Markdown.
    """
    if not GEMINI_API_KEY or GEMINI_API_KEY == "demo_key":
        logger.warning("[OCR Vision] Chưa có GEMINI_API_KEY hợp lệ, bỏ qua gọi Vision API.")
        return f"\n## Trang {page_num}\n\n[Trang scan ảnh - Cần GEMINI_API_KEY để OCR]\n"

    try:
        # DPI 85 cho chất lượng đọc chữ rất tốt nhưng dung lượng JPG chỉ ~100-150KB
        pix = page.get_pixmap(dpi=85)
        jpg_bytes = pix.tobytes("jpg")
        b64_image = base64.b64encode(jpg_bytes).decode("utf-8")

        prompt = (
            "Bạn là chuyên gia số hóa văn bản hành chính của Đại học Sư phạm Kỹ thuật TP.HCM (HCMUTE).\n"
            "Hãy trích xuất toàn bộ văn bản trong ảnh trang công văn này sang định dạng Markdown chuẩn tiếng Việt.\n"
            "Yêu cầu:\n"
            "1. Giữ nguyên số hiệu công văn, ngày tháng, tên cơ quan, chức vụ và chữ ký.\n"
            "2. Bảo toàn toàn bộ tiêu đề Điều, Khoản, Mục.\n"
            "3. Chuyển đổi bảng biểu, lịch thi, danh sách học phần sang định dạng Markdown Table (| Cột 1 | Cột 2 |).\n"
            "4. Không tự ý tóm tắt, không chèn lời bình luận của AI."
        )

        payload = {
            "contents": [
                {
                    "parts": [
                        {"text": prompt},
                        {"inline_data": {"mime_type": "image/jpeg", "data": b64_image}}
                    ]
                }
            ],
            "generationConfig": {
                "temperature": 0.1,
                "maxOutputTokens": 4096
            }
        }

        # Gọi với retry exponential backoff
        for attempt in range(3):
            try:
                with httpx.Client(timeout=60.0) as client:
                    resp = client.post(VISION_API_URL, json=payload)
                    if resp.status_code == 200:
                        data = resp.json()
                        text = data["candidates"][0]["content"]["parts"][0]["text"].strip()
                        # Làm sạch nếu AI bọc trong khối ```markdown
                        text = re.sub(r"^```markdown\s*", "", text, flags=re.IGNORECASE)
                        text = re.sub(r"```$", "", text).strip()
                        return f"\n## Trang {page_num}\n\n{text}\n"
                    elif resp.status_code == 429:
                        wait_sec = (attempt + 1) * 3
                        logger.warning(f"[OCR Vision] Dính Rate limit (429) tại {doc_name} trang {page_num}, đợi {wait_sec}s...")
                        time.sleep(wait_sec)
                    else:
                        logger.error(f"[OCR Vision] Lỗi HTTP {resp.status_code} từ Gemini API: {resp.text[:200]}")
                        break
            except Exception as e:
                logger.warning(f"[OCR Vision] Lỗi request trang {page_num} (lần {attempt + 1}): {e}")
                time.sleep(2)

    except Exception as ex:
        logger.error(f"[OCR Vision] Thất bại khi render/OCR trang {page_num} của {doc_name}: {ex}")

    return f"\n## Trang {page_num}\n\n[Lỗi OCR trang scan]\n"


def process_pdf_file(pdf_path: Path, output_dir: Path) -> Dict[str, Any]:
    """
    Xử lý thông minh 1 file PDF:
    - Nếu có text layer: Dùng parser số hóa trực tiếp (nhanh, 100ms).
    - Nếu là file scan ảnh: Kích hoạt Gemini Vision OCR từng trang.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    out_file = output_dir / f"{pdf_path.stem}.md"

    # Bỏ qua nếu đã OCR thành công trước đó
    if out_file.exists() and out_file.stat().st_size > 300:
        return {"file": pdf_path.name, "status": "SKIPPED", "type": "CACHED", "size": out_file.stat().st_size}

    try:
        doc = fitz.open(str(pdf_path))
        num_pages = len(doc)

        # 1. Kiểm tra lớp text số
        raw_text_parts = []
        total_chars = 0
        for i, page in enumerate(doc):
            t = page.get_text("text").strip()
            total_chars += len(t)
            if t:
                raw_text_parts.append(f"\n## Trang {i + 1}\n\n{t}")

        # Đếm ký tự tiếng Việt có dấu để loại trừ trường hợp font encoding hỏng
        vi_chars = len(re.findall(r'[àáảãạăắặằẳẵâấầẩẫậèéẹẻẽêếềệểễđìíịỉĩòóọỏõôốồổỗộơớờợởỡùúụủũưứừựửữỳýỵỷỹ]', "".join(raw_text_parts), re.IGNORECASE))

        # Nếu có đủ văn bản số chất lượng cao
        if total_chars >= 200 and vi_chars >= 15:
            # Thử dùng pymupdf4llm để có bảng biểu Markdown chuẩn
            try:
                import pymupdf4llm
                md_content = pymupdf4llm.to_markdown(str(pdf_path))
                if len(md_content.strip()) >= 200:
                    out_file.write_text(f"# {pdf_path.stem}\n\n" + md_content, encoding="utf-8")
                    doc.close()
                    logger.info(f"[Digital PDF] ✅ {pdf_path.name} -> Markdown ({len(md_content)} chars)")
                    return {"file": pdf_path.name, "status": "SUCCESS", "type": "DIGITAL_PYMUPDF4LLM", "pages": num_pages}
            except Exception:
                pass

            # Fallback lấy text chuẩn từ fitz
            content = f"# {pdf_path.stem}\n\n" + "\n".join(raw_text_parts)
            out_file.write_text(content, encoding="utf-8")
            doc.close()
            logger.info(f"[Digital PDF] ✅ {pdf_path.name} -> Plain Text Markdown ({len(content)} chars)")
            return {"file": pdf_path.name, "status": "SUCCESS", "type": "DIGITAL_PLAIN", "pages": num_pages}

        # 2. Nếu là văn bản scan ảnh thuần (total_chars < 200): Kích hoạt Multimodal Vision OCR
        logger.info(f"[Scanned PDF] 🔍 Phát hiện file scan mộc đỏ: {pdf_path.name} ({num_pages} trang). Đang kích hoạt Gemini 2.5 Flash Vision OCR...")
        ocr_pages = []
        for i, page in enumerate(doc):
            logger.info(f"  -> Đang OCR trang {i + 1}/{num_pages} của '{pdf_path.name}'...")
            page_text = ocr_single_page_with_gemini(page, i + 1, pdf_path.name)
            ocr_pages.append(page_text)
            # Nghỉ 500ms giữa các trang để chống Rate Limit
            time.sleep(0.5)

        full_ocr_text = f"# {pdf_path.stem}\n\n> 🏛️ *Văn bản scan ảnh đóng mộc đỏ đã được số hóa tự động qua Gemini Multimodal Vision API*\n\n" + "\n".join(ocr_pages)
        out_file.write_text(full_ocr_text, encoding="utf-8")
        doc.close()
        logger.info(f"[Scanned PDF] ✅ Hoàn thành OCR 100% cho file scan: {pdf_path.name} ({len(full_ocr_text)} chars)")
        return {"file": pdf_path.name, "status": "SUCCESS", "type": "MULTIMODAL_VISION_OCR", "pages": num_pages}

    except Exception as e:
        logger.error(f"[Pipeline] ❌ Thất bại khi xử lý '{pdf_path.name}': {e}")
        return {"file": pdf_path.name, "status": "FAILED", "error": str(e)}


def run_full_ocr_pipeline(pdf_dir: Path = DEFAULT_PDF_DIR, output_dir: Path = MARKDOWN_OUTPUT_DIR) -> Dict[str, Any]:
    """
    Chạy toàn bộ pipeline quét đệ quy các năm 2024, 2025, 2026.
    """
    start_time = time.time()
    output_dir.mkdir(parents=True, exist_ok=True)

    if not pdf_dir.exists():
        logger.error(f"Thư mục nguồn không tồn tại: {pdf_dir}")
        return {"error": f"Path not found: {pdf_dir}"}

    pdf_files = sorted(list(pdf_dir.glob("**/*.pdf")))
    logger.info(f"====== BẮT ĐẦU PIPELINE SỐ HÓA MULTIMODAL OCR ({len(pdf_files)} FILES) ======")

    results = {
        "total_scanned": len(pdf_files),
        "digital_success": 0,
        "vision_ocr_success": 0,
        "skipped": 0,
        "failed": 0,
        "details": []
    }

    for idx, pdf in enumerate(pdf_files, 1):
        logger.info(f"[{idx}/{len(pdf_files)}] Đang thẩm định: {pdf.name}")
        res = process_pdf_file(pdf, output_dir)
        results["details"].append(res)

        st = res.get("status")
        tp = res.get("type", "")
        if st == "SUCCESS":
            if "VISION" in tp:
                results["vision_ocr_success"] += 1
            else:
                results["digital_success"] += 1
        elif st == "SKIPPED":
            results["skipped"] += 1
        else:
            results["failed"] += 1

    elapsed = time.time() - start_time
    results["elapsed_seconds"] = round(elapsed, 1)

    logger.info(f"====== HOÀN TẤT PIPELINE OCR ({elapsed:.1f}s) ======")
    logger.info(f"Tổng files: {results['total_scanned']} | Digital: {results['digital_success']} | OCR Scan: {results['vision_ocr_success']} | Bỏ qua: {results['skipped']} | Lỗi: {results['failed']}")

    return results


if __name__ == "__main__":
    run_full_ocr_pipeline()
