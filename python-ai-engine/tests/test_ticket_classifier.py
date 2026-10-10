"""
QAUTE Portal — Python AI Engine
test_ticket_classifier.py: Unit tests cho bộ phân loại Ticket học vụ và 3D Latent Space API
"""
import pytest
from fastapi.testclient import TestClient
from ticket_classifier import TicketClassifier, DEPARTMENTS
from main import app


@pytest.fixture
def classifier():
    return TicketClassifier()


@pytest.fixture
def client():
    return TestClient(app)


class TestTicketClassifierRules:
    """Kiểm thử độ chính xác của Rule Engine phân loại Ticket"""

    def test_classify_dao_tao_urgent(self, classifier):
        title = "Khẩn cấp: Nguy cơ bị buộc thôi học do cảnh báo học vụ"
        desc = "Em bị trễ hạn đóng tiền học phí và nhận cảnh báo học vụ lần 2. Em rất lo lắng cứu em với thầy cô ơi!"
        res = classifier.classify(title, desc)

        assert res.department_code == "DAO_TAO"
        assert res.priority == "URGENT"
        assert res.sentiment == "ANXIOUS"
        assert res.confidence_score >= 0.70
        assert len(res.suggested_action) > 10

    def test_classify_dao_tao_phuc_khao(self, classifier):
        title = "Đơn xin phúc khảo điểm thi cuối kỳ môn Giải tích 1"
        desc = "Em thấy điểm thi của em bị sai sót so với bài làm. Em muốn xin phúc khảo lại bài thi."
        res = classifier.classify(title, desc)

        assert res.department_code == "DAO_TAO"
        assert res.priority in ["HIGH", "MEDIUM"]
        assert "phúc khảo" in res.suggested_action.lower() or "kiểm tra" in res.suggested_action.lower()

    def test_classify_tuyen_sinh(self, classifier):
        title = "Hỏi về thủ tục nhập học và hồ sơ trúng tuyển đại học 2026"
        desc = "Em là thí sinh trúng tuyển diện học bạ, cho em hỏi thời gian nộp giấy báo trúng tuyển bản chính khi nào ạ?"
        res = classifier.classify(title, desc)

        assert res.department_code == "TUYEN_SINH"
        assert res.department_name == "Phòng Tuyển sinh & Truyền thông"
        assert res.confidence_score >= 0.75

    def test_classify_khoa_cntt(self, classifier):
        title = "Đăng ký giảng viên hướng dẫn Đồ án tốt nghiệp CNTT"
        desc = "Em là sinh viên ngành Kỹ thuật phần mềm khóa 2022, cần nộp phiếu đăng ký đề tài đồ án tốt nghiệp Khoa CNTT."
        res = classifier.classify(title, desc)

        assert res.department_code == "KHOA_CNTT"
        assert res.department_name == "Khoa Công nghệ Thông tin"
        assert res.confidence_score >= 0.75

    def test_classify_khoa_nn(self, classifier):
        title = "Thủ tục nộp chứng chỉ TOEIC xét chuẩn đầu ra tiếng Anh"
        desc = "Em muốn nộp chứng chỉ tiếng Anh quốc tế để xét miễn học phần tiếng Anh và thi ĐGNLTA."
        res = classifier.classify(title, desc)

        assert res.department_code == "KHOA_NN"
        assert res.department_name == "Khoa Ngoại ngữ"

    def test_classify_doan_hoi(self, classifier):
        title = "Cộng điểm rèn luyện chiến dịch Mùa Hè Xanh 2026"
        desc = "Em đã tham gia chiến dịch tình nguyện Mùa Hè Xanh của Đoàn Thanh niên - Hội Sinh viên nhưng chưa thấy cập nhật điểm."
        res = classifier.classify(title, desc)

        assert res.department_code == "DOAN_HOI"
        assert res.department_name == "Đoàn Thanh niên - Hội Sinh viên"

    def test_sentiment_frustrated(self, classifier):
        title = "Hệ thống đăng ký môn học quá lỗi và chậm trễ"
        desc = "Tại sao hệ thống cứ lag mãi không vào được? Quá bức xúc vì không công bằng cho sinh viên năm cuối!"
        res = classifier.classify(title, desc)

        assert res.sentiment == "FRUSTRATED"

    def test_sentiment_confused(self, classifier):
        title = "Thắc mắc điều kiện bảo lưu kết quả học tập"
        desc = "Em chưa rõ quy định về thời gian tối đa được bảo lưu, thầy cô giải thích giúp em như thế nào với ạ."
        res = classifier.classify(title, desc)

        assert res.sentiment == "CONFUSED"

    def test_fallback_default_department(self, classifier):
        title = "Chào thầy cô"
        desc = "Em có một câu hỏi chung chung không rõ liên quan đến đâu."
        res = classifier.classify(title, desc)

        assert res.department_code == "DAO_TAO"
        assert res.confidence_score >= 0.50


class TestTicketClassificationAPI:
    """Kiểm thử qua HTTP TestClient gọi API endpoint"""

    def test_post_classify_ticket_api(self, client):
        payload = {
            "title": "Học phí học kỳ 2 năm 2026",
            "description": "Em muốn hỏi về hạn chót đóng tiền học phí và miễn giảm học phí cho sinh viên khó khăn."
        }
        resp = client.post("/ai/classify-ticket", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["department_code"] == "DAO_TAO"
        assert "Phòng Đào tạo" in data["department_name"]
        assert "confidence_score" in data
        assert "priority" in data
        assert "suggested_action" in data

    def test_get_latent_space_3d_api(self, client):
        resp = client.get("/admin/latent-space-3d?limit=20")
        assert resp.status_code == 200
        data = resp.json()
        assert "points" in data
        assert "total" in data
        if data["total"] > 0:
            first_pt = data["points"][0]
            assert "x" in first_pt
            assert "y" in first_pt
            assert "z" in first_pt
            assert "doc_code" in first_pt
