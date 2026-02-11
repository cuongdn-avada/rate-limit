package com.dncuong.ws.rate_limit.controller;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;
import com.dncuong.ws.rate_limit.algorithm.slidingwindowcounter.SlidingWindowCounterRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller demo cho thuật toán Sliding Window Counter Rate Limiting.
 *
 * Mục đích: Cho phép test thủ công qua trình duyệt hoặc công cụ như curl, Postman.
 *
 * Cấu hình mặc định:
 * - Tối đa 5 requests mỗi 10 giây cho mỗi IP
 * - Khi vượt giới hạn → trả về HTTP 429 (Too Many Requests)
 *
 * So sánh với các controller khác:
 * - Fixed Window: counter reset đột ngột, có boundary problem
 * - Sliding Window Log: chính xác tuyệt đối, tốn bộ nhớ
 * - Sliding Window Counter: ước lượng chính xác, tiết kiệm bộ nhớ (hybrid)
 *
 * @author dncuong
 */
@RestController
@RequestMapping("/api/sliding-window-counter")
public class SlidingWindowCounterDemoController {

    /**
     * Rate limiter được khởi tạo với:
     * - maxRequests = 5: tối đa 5 request
     * - windowSizeInMillis = 10_000: trong mỗi cửa sổ 10 giây
     *
     * Dùng weighted average của counter cửa sổ trước và hiện tại
     * để ước lượng số request trong cửa sổ trượt.
     */
    private final RateLimiter rateLimiter = new SlidingWindowCounterRateLimiter(5, 10_000);

    /**
     * Endpoint demo: GET /api/sliding-window-counter/test
     *
     * Luồng xử lý:
     * 1. Lấy IP address của client làm key để rate limit
     * 2. Gọi rateLimiter.allowRequest(ip) để kiểm tra
     * 3. Nếu được phép → trả về 200 OK với thông báo thành công
     * 4. Nếu bị từ chối → trả về 429 Too Many Requests
     *
     * @param request HttpServletRequest để lấy thông tin IP của client
     * @return ResponseEntity chứa kết quả (200 OK hoặc 429 Too Many Requests)
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testRateLimit(HttpServletRequest request) {
        // Lấy IP address của client
        String clientIp = request.getRemoteAddr();

        // Hỏi rate limiter: request này có được phép không?
        boolean allowed = rateLimiter.allowRequest(clientIp);

        if (allowed) {
            // === REQUEST ĐƯỢC PHÉP ===
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Request duoc chap nhan!",
                    "algorithm", "Sliding Window Counter",
                    "clientIp", clientIp
            ));
        } else {
            // === REQUEST BỊ TỪ CHỐI ===
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "10")
                    .body(Map.of(
                            "status", "RATE_LIMITED",
                            "message", "Ban da vuot qua gioi han! Toi da 5 requests / 10 giay.",
                            "algorithm", "Sliding Window Counter",
                            "clientIp", clientIp
                    ));
        }
    }
}
