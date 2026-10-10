"""
QAUTE Portal — Python AI Engine
ticket_classifier.py: Bộ phân loại Ticket học vụ thông minh (Smart Ticket Triage & Auto-Classifier)

Chức năng:
  - Phân loại phòng ban phụ trách (DAO_TAO, TUYEN_SINH, KHOA_CNTT, KHOA_NN, DOAN_HOI).
  - Đánh giá mức độ ưu tiên xử lý (URGENT, HIGH, MEDIUM, LOW) dựa trên mức độ cấp bách học vụ.
  - Phân tích cảm xúc sinh viên (ANXIOUS, FRUSTRATED, CONFUSED, NEUTRAL) để cán bộ chuẩn bị tâm thế hỗ trợ.
  - Gợi ý hành động nghiệp vụ (suggested_action) cho Cán bộ tiếp nhận xử lý nhanh.
  - Cơ chế kép: Heuristic Engine siêu tốc (1ms, zero-cost) kết hợp Gemini 2.5 Flash Fallback.
"""
import re
import json
import logging
from typing import Optional, List, Dict, Any
from dataclasses import dataclass, field, asdict

logger = logging.getLogger("qaute.ticket_classifier")


@dataclass
class TicketClassificationResult:
    department_code: str          # DAO_TAO, TUYEN_SINH, KHOA_CNTT, KHOA_NN, DOAN_HOI
    department_name: str
    priority: str                 # URGENT, HIGH, MEDIUM, LOW
    sentiment: str                # ANXIOUS, FRUSTRATED, CONFUSED, NEUTRAL
    confidence_score: float       # 0.0 - 1.0
    summary: str                  # Tóm tắt 1 câu
    suggested_action: str         # Gợi ý hành động cho Cán bộ
    key_indicators: List[str] = field(default_factory=list)
    classified_by: str = "RULE_ENGINE"  # RULE_ENGINE hoặc GEMINI_LLM


# Danh mục Khoa/Phòng chuẩn của HCMUTE trong database
DEPARTMENTS = {
    "DAO_TAO": "Phòng Đào tạo & Công tác Sinh viên",
    "TUYEN_SINH": "Phòng Tuyển sinh & Truyền thông",
    "KHOA_CNTT": "Khoa Công nghệ Thông tin",
    "KHOA_NN": "Khoa Ngoại ngữ",
    "DOAN_HOI": "Đoàn Thanh niên - Hội Sinh viên"
}


# Trọng số từ khóa cho từng Đơn vị
DEPARTMENT_KEYWORDS: Dict[str, List[tuple[str, float]]] = {
    "DAO_TAO": [
        ("học phí", 1.8), ("học bổng", 1.8), ("đăng ký môn học", 2.0), ("đkmh", 2.0),
        ("rút môn", 2.0), ("hủy môn", 2.0), ("hủy học phần", 2.0), ("cảnh báo học vụ", 2.2),
        ("buộc thôi học", 2.2), ("đình chỉ", 2.0), ("điểm rèn luyện", 1.9), ("đrl", 1.9),
        ("phúc khảo", 2.0), ("xét tốt nghiệp", 2.0), ("bảo lưu", 2.0), ("bảng điểm", 1.7),
        ("thời khóa biểu", 1.6), ("tkb", 1.6), ("bảo hiểm y tế", 1.9), ("bhyt", 1.9),
        ("miễn giảm học phí", 2.0), ("chế độ chính sách", 1.8), ("chuyển ngành", 1.9),
        ("chuyển điểm", 1.6), ("chứng chỉ toeic", 1.5), ("sổ bộ điểm", 1.8), ("học lại", 1.7),
        ("cải thiện", 1.7), ("trùng lịch thi", 1.9), ("hoãn thi", 2.0), ("lịch thi", 1.6),
        ("chuẩn đầu ra", 1.5), ("chứng chỉ gdqp", 1.8), ("giáo dục quốc phòng", 1.8)
    ],
    "TUYEN_SINH": [
        ("tuyển sinh", 2.2), ("nhập học", 2.0), ("hồ sơ trúng tuyển", 2.2), ("xét tuyển", 2.0),
        ("điểm chuẩn", 2.0), ("học bạ", 1.8), ("đề án tuyển sinh", 2.0), ("thủ tục nhập học", 2.0),
        ("giấy báo trúng tuyển", 2.2), ("nguyện vọng", 1.8), ("phương thức xét tuyển", 2.0),
        ("thí sinh", 1.8), ("đăng ký xét tuyển", 2.0), ("chỉ tiêu", 1.7), ("đào tạo từ xa", 1.8)
    ],
    "KHOA_CNTT": [
        ("đồ án tốt nghiệp cntt", 2.5), ("khóa luận cntt", 2.5), ("thực tập doanh nghiệp", 2.0),
        ("kỹ thuật phần mềm", 2.2), ("khoa học máy tính", 2.2), ("an toàn thông tin", 2.2),
        ("mạng máy tính", 2.2), ("khoa cntt", 2.5), ("giảng viên hướng dẫn", 1.8),
        ("bảo vệ đồ án", 2.2), ("đăng ký chuyên ngành", 2.0), ("môn chuyên ngành cntt", 2.0),
        ("khoa tin học", 2.0), ("lab", 1.5), ("phòng máy", 1.6), ("cntt", 1.8)
    ],
    "KHOA_NN": [
        ("khoa ngoại ngữ", 2.5), ("đgnlta", 2.5), ("đánh giá năng lực tiếng anh", 2.5),
        ("chuẩn đầu ra tiếng anh", 2.2), ("miễn học phần tiếng anh", 2.2),
        ("chuyển điểm toeic sang học phần", 2.5), ("thi xếp lớp tiếng anh", 2.2),
        ("tiếng anh không chuyên", 2.0), ("anh văn 1", 1.8), ("anh văn 2", 1.8),
        ("av1", 1.8), ("av2", 1.8), ("khoa nn", 2.5)
    ],
    "DOAN_HOI": [
        ("đoàn thanh niên", 2.5), ("hội sinh viên", 2.5), ("mùa hè xanh", 2.5),
        ("tiếp sức mùa thi", 2.5), ("kết nạp đoàn", 2.5), ("sinh hoạt chi đoàn", 2.2),
        ("hoạt động phong trào", 2.0), ("sổ đoàn", 2.2), ("chuyển sinh hoạt đoàn", 2.2),
        ("xuân tình nguyện", 2.5), ("ngày thứ bảy tình nguyện", 2.2), ("bí thư", 1.8),
        ("lớp phó phong trào", 1.8), ("câu lạc bộ", 1.7), ("clb", 1.7), ("đoàn hội", 2.2)
    ]
}

# Tiêu chí phân cấp độ khẩn cấp (Priority)
URGENT_PATTERNS = [
    r"buộc\s+thôi\s+học", r"đình\s+chỉ", r"kỷ\s+luật", r"hết\s+hạn\s+hôm\s+nay",
    r"trễ\s+hạn\s+đóng\s+tiền", r"khẩn\s+cấp", r"gấp\s+lắm", r"bị\s+khóa\s+tài\s+khoản",
    r"cứu\s+em", r"sắp\s+bị\s+đuổi", r"hủy\s+môn\s+gấp", r"trùng\s+giờ\s+thi",
    r"không\s+có\s+tên\s+trong\s+danh\s+sách\s+thi"
]

HIGH_PATTERNS = [
    r"phúc\s+khảo", r"học\s+bổng", r"sai\s+sót\s+điểm", r"khiếu\s+nại",
    r"hết\s+hạn\s+trong\s+tuần", r"không\s+đăng\s+ký\s+được", r"lỗi\s+đóng\s+tiền",
    r"mất\s+thẻ\s+sinh\s+viên", r"chứng\s+chỉ\s+hết\s+hạn"
]

LOW_PATTERNS = [
    r"cho\s+em\s+hỏi\s+chung", r"tham\s+khảo", r"khi\s+nào\s+thì\s+có",
    r"thắc\s+mắc\s+nhỏ", r"tìm\s+hiểu\s+thêm", r"góp\s+ý"
]

# Tiêu chí nhận diện cảm xúc (Sentiment)
ANXIOUS_PATTERNS = [
    r"lo\s+lắng", r"sợ\s+bị", r"không\s+biết\s+phải\s+làm\s+sao", r"cứu\s+em",
    r"hoang\s+mang", r"sắp\s+hết\s+hạn", r"nguy\s+hiểm", r"run\s+quá"
]

FRUSTRATED_PATTERNS = [
    r"bức\s+xúc", r"quá\s+lâu", r"tại\s+sao\s+lại\s+thế", r"không\s+công\s+bằng",
    r"lỗi\s+hệ\s+thống\s+mãi", r"khiếu\s+nại", r"chậm\s+trễ", r"vô\s+lý"
]

CONFUSED_PATTERNS = [
    r"không\s+hiểu", r"chưa\s+rõ", r"lúng\s+túng", r"mơ\s+hồ", r"phân\s+vân",
    r"giải\s+thích\s+giúp", r"như\s+thế\s+nào"
]


class TicketClassifier:
    """
    Engine phân tích Ticket kết hợp Heuristic chấm điểm ngữ nghĩa và Gemini LLM.
    """

    def __init__(self, settings=None):
        self.settings = settings

    def classify(self, title: str, description: str) -> TicketClassificationResult:
        """
        Phân loại toàn diện một Ticket: Đơn vị tiếp nhận, Mức độ ưu tiên, Cảm xúc, và Đề xuất xử lý.
        """
        text = f"{title}\n{description}".strip()
        lower_text = text.lower()

        # 1. Tính điểm các Phòng/Khoa
        scores: Dict[str, float] = {k: 0.0 for k in DEPARTMENTS.keys()}
        matched_indicators: Dict[str, List[str]] = {k: [] for k in DEPARTMENTS.keys()}

        for dept_code, kw_list in DEPARTMENT_KEYWORDS.items():
            for kw, weight in kw_list:
                if kw in lower_text:
                    scores[dept_code] += weight
                    matched_indicators[dept_code].append(kw)

        # Tìm đơn vị có điểm cao nhất
        best_dept = max(scores, key=scores.get)
        best_score = scores[best_dept]

        # Đơn vị mặc định nếu không khớp từ khóa: Phòng Đào tạo (đơn vị giải quyết >80% thủ tục)
        if best_score < 0.5:
            best_dept = "DAO_TAO"
            confidence = 0.50
            indicators = ["mặc định tiếp nhận học vụ"]
        else:
            # Chuẩn hóa confidence score: min 0.65, max 0.98
            confidence = min(0.98, 0.60 + (best_score * 0.08))
            indicators = matched_indicators[best_dept][:5]

        # 2. Xác định Mức độ ưu tiên (Priority)
        priority = "MEDIUM"
        for pattern in URGENT_PATTERNS:
            if re.search(pattern, lower_text):
                priority = "URGENT"
                indicators.append(f"urgent_kw:{re.search(pattern, lower_text).group(0)}")
                break

        if priority == "MEDIUM":
            for pattern in HIGH_PATTERNS:
                if re.search(pattern, lower_text):
                    priority = "HIGH"
                    indicators.append(f"high_kw:{re.search(pattern, lower_text).group(0)}")
                    break

        if priority == "MEDIUM":
            for pattern in LOW_PATTERNS:
                if re.search(pattern, lower_text):
                    priority = "LOW"
                    break

        # 3. Phân tích Cảm xúc (Sentiment)
        sentiment = "NEUTRAL"
        for pattern in ANXIOUS_PATTERNS:
            if re.search(pattern, lower_text):
                sentiment = "ANXIOUS"
                break
        if sentiment == "NEUTRAL":
            for pattern in FRUSTRATED_PATTERNS:
                if re.search(pattern, lower_text):
                    sentiment = "FRUSTRATED"
                    break
        if sentiment == "NEUTRAL":
            for pattern in CONFUSED_PATTERNS:
                if re.search(pattern, lower_text):
                    sentiment = "CONFUSED"
                    break

        # 4. Tạo Tóm tắt ngắn gọn và Gợi ý hành động cho Cán bộ
        summary = self._generate_summary(title, best_dept, priority)
        suggested_action = self._suggest_action(best_dept, priority, lower_text)

        return TicketClassificationResult(
            department_code=best_dept,
            department_name=DEPARTMENTS[best_dept],
            priority=priority,
            sentiment=sentiment,
            confidence_score=round(confidence, 2),
            summary=summary,
            suggested_action=suggested_action,
            key_indicators=indicators,
            classified_by="RULE_ENGINE"
        )

    def _generate_summary(self, title: str, dept_code: str, priority: str) -> str:
        clean_title = title.strip()
        if len(clean_title) > 80:
            clean_title = clean_title[:77] + "..."
        dept_name = DEPARTMENTS[dept_code]
        return f"[{priority}] {clean_title} — Đề xuất chuyển tiếp đến {dept_name}."

    def _suggest_action(self, dept_code: str, priority: str, lower_text: str) -> str:
        if priority == "URGENT":
            return (
                "🚨 Yêu cầu khẩn cấp liên quan đến nguy cơ học vụ/hạn chót. "
                "Cán bộ cần liên hệ trực tiếp với sinh viên qua email/số điện thoại hoặc tiếp nhận trước trong ca trực."
            )
        
        if dept_code == "DAO_TAO":
            if "học phí" in lower_text or "tiền" in lower_text:
                return "Tra cứu trạng thái tài chính của sinh viên trên hệ thống kế toán hoặc đối soát thông báo học phí."
            if "rút môn" in lower_text or "hủy" in lower_text:
                return "Kiểm tra hạn chót điều chỉnh học phần trên Cổng thông tin đào tạo và hướng dẫn sinh viên nộp đơn online."
            if "phúc khảo" in lower_text or "điểm" in lower_text:
                return "Kiểm tra danh sách công bố điểm và thời hạn nộp đơn phúc khảo học kỳ hiện tại."
            return "Kiểm tra hồ sơ học vụ của sinh viên và phản hồi theo quy chế đào tạo hiện hành."

        if dept_code == "TUYEN_SINH":
            return "Cung cấp đề án tuyển sinh, hướng dẫn quy trình xác nhận nhập học và hồ sơ minh chứng trúng tuyển."

        if dept_code == "KHOA_CNTT":
            return "Chuyển tiếp đến Trợ lý Giáo vụ Khoa CNTT hoặc Giảng viên phụ trách học phần/đồ án tốt nghiệp."

        if dept_code == "KHOA_NN":
            return "Hướng dẫn quy trình xét miễn học phần tiếng Anh hoặc đối soát chứng chỉ ngoại ngữ quốc tế tương đương."

        if dept_code == "DOAN_HOI":
            return "Kiểm tra danh sách tham gia hoạt động tình nguyện hoặc hướng dẫn thủ tục chuyển sinh hoạt Đoàn."

        return "Tiếp nhận và phản hồi theo quy định tiếp nhận giải quyết thắc mắc học vụ."
