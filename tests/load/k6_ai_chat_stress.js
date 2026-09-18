import http from 'k6/http';
import { check, sleep } from 'k6';

/**
 * Kịch bản kiểm thử chịu tải k6 cho Module AI RAG Chatbot (QAUTE Portal)
 * Thực thi: k6 run tests/load/k6_ai_chat_stress.js
 */
export const options = {
  stages: [
    { duration: '30s', target: 50 },   // 1. Khởi động tải: Tăng dần lên 50 VUs (Sinh viên)
    { duration: '1m', target: 200 },   // 2. Giờ cao điểm thông thường: 200 VUs
    { duration: '30s', target: 500 },  // 3. Đột biến (Spike): Vọt lên 500 VUs cùng lúc
    { duration: '1m', target: 500 },   // 4. Duy trì tải nặng đỉnh điểm
    { duration: '30s', target: 0 },    // 5. Hạ tải về 0
  ],
  thresholds: {
    // 95% request phải có thời gian phản hồi dưới 2 giây
    http_req_duration: ['p(95)<2000'],
    // Tỷ lệ lỗi (5xx) phải dưới 5%
    http_req_failed: ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const url = `${BASE_URL}/api/v1/ai/chat`;

  // Bộ câu hỏi thực tế đại diện cho các tầng xử lý của Phễu lọc
  const questions = [
    'Điều kiện xét học bổng khuyến khích học tập là gì?', // Tầng 1: Quy chế học vụ 2026 (PDF)
    'Văn phòng Khoa Công nghệ Thông tin ở toà nhà nào?',  // Tầng Cache / FAQ phổ biến
    'Thời hạn đóng học phí học kỳ 1 năm 2026 khi nào hết hạn?', // Tầng 1: Công văn đào tạo
    'Quy định về việc hoãn thi kết thúc học phần?',       // Tầng 1: Quy chế
    'Trường Đại học Sư phạm Kỹ thuật TP.HCM ở địa chỉ nào?', // Cấp 0: Thông tin tĩnh
  ];

  const randomQuestion = questions[Math.floor(Math.random() * questions.length)];

  const payload = JSON.stringify({
    message: randomQuestion,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '10s',
  };

  const res = http.post(url, payload, params);

  // Kiểm tra kết quả trả về:
  // - 200: Thành công
  // - 429: Bị chặn bởi Rate Limiter (hợp lệ trong kịch bản Spike Test)
  check(res, {
    'status is 200 or 429 (Controlled Rate Limit)': (r) => r.status === 200 || r.status === 429,
    'response time < 2000ms': (r) => r.timings.duration < 2000,
  });

  // Giả lập thời gian suy nghĩ / đọc phản hồi của sinh viên (Think time)
  sleep(1);
}
