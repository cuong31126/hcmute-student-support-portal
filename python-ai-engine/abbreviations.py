"""
QAUTE Portal — Python AI Engine
abbreviations.py: Chuẩn hóa teencode và từ viết tắt học vụ HCMUTE
Đồng bộ 100% với AcademicAbbreviationUtils.java
"""
import re

# Danh sách quy tắc mở rộng (regex pattern, replacement)
_RULES = [
    (r"avđv|avdv", "anh văn đầu vào"),
    (r"avđr|avdr", "anh văn đầu ra"),
    (r"đrl|drl", "điểm rèn luyện"),
    (r"đkhp|dkhp|dkmh|đkmh", "đăng ký học phần"),
    (r"hb", "học bổng"),
    (r"tn", "tốt nghiệp"),
    (r"kltn", "khóa luận tốt nghiệp"),
    (r"đatn", "đồ án tốt nghiệp"),
    (r"ctsv", "công tác sinh viên"),
    (r"pđt|pdt", "phòng đào tạo"),
    (r"hp", "học phần"),
    (r"tc", "tín chỉ"),
    (r"gpa", "điểm trung bình tích lũy"),
    (r"nam hc|nam hoc", "năm học"),
    (r"shdk|shđk|sinh hoat dau nam|sinh hoạt đầu năm", "sinh hoạt đầu khóa tân sinh viên"),
    (r"khoa it|khoa cntt", "khoa công nghệ thông tin"),
    (r"it", "công nghệ thông tin"),
    (r"cntt", "công nghệ thông tin"),
    (r"gv|cbgd", "giảng viên"),
    (r"sv", "sinh viên"),
    (r"ccta|cc ta", "chứng chỉ tiếng anh"),
    (r"đgnlta|dgnlta", "đánh giá năng lực tiếng anh"),
    (r"dot 1 va 2|đợt 1 và 2", "đợt 1 và đợt 2"),
]

# Biên dịch pattern ranh giới từ tiếng Việt Unicode
_COMPILED_PATTERNS = [
    (re.compile(rf"(?i)(?<!\w)({pattern})(?!\w)", re.UNICODE), replacement)
    for pattern, replacement in _RULES
]


def expand(text: str) -> str:
    """
    Mở rộng các từ viết tắt trong câu hỏi học vụ của sinh viên.
    Ví dụ: 'cách tính đrl k22' -> 'cách tính điểm rèn luyện k22'
    """
    if not text:
        return text

    result = text
    for pattern, replacement in _COMPILED_PATTERNS:
        result = pattern.sub(replacement, result)

    return result
