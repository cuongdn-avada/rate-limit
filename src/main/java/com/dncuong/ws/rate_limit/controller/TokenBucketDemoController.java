package com.dncuong.ws.rate_limit.controller;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;
import com.dncuong.ws.rate_limit.algorithm.tokenbucket.TokenBucketRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller demo cho thuật toán Token Bucket Rate Limiting.
 *
 * Mục đích: Cho phép test thủ công qua trình duyệt hoặc công cụ như curl, Postman.
 *
 * Cấu hình mặc định:
 * - Sức chứa xô: 5 token (burst tối đa = 5 request)
 * - Tốc độ nạp: 1 token/giây (throughput trung bình = 1 req/s)
 * - Khi hết token → trả về HTTP 429 (Too Many Requests)
 *
 * So sánh với các controller khác:
 * - Window-based (Fixed, Sliding): giới hạn theo cửa sổ thời gian
 * - Token Bucket: giới hạn tốc độ trung bình, cho phép burst ngắn hạn
 *
 * @author dncuong
 */
@RestController
@RequestMapping("/api/token-bucket")
public class TokenBucketDemoController {

    /**
     * Rate limiter được khởi tạo với:
     * - bucketCapacity = 5: tối đa 5 token trong xô (burst = 5)
     * - refillRate = 1.0: nạp 1 token mỗi giây
     *
     * Hành vi:
     * - Gửi 5 request liên tiếp → tất cả OK (dùng hết burst)
     * - Request 6 → bị từ chối (hết token)
     * - Đợi 1 giây → có 1 token mới → 1 request OK
     * - Đợi 5 giây → xô đầy lại → burst 5 request
     */
    private final RateLimiter rateLimiter = new TokenBucketRateLimiter(5, 1.0);

    /**
     * Endpoint demo: GET /api/token-bucket/test
     *
     * @param request HttpServletRequest để lấy thông tin IP của client
     * @return ResponseEntity chứa kết quả (200 OK hoặc 429 Too Many Requests)
     */
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testRateLimit(HttpServletRequest request) {
        String clientIp = request.getRemoteAddr();
        boolean allowed = rateLimiter.allowRequest(clientIp);

        if (allowed) {
            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "message", "Request duoc chap nhan!",
                    "algorithm", "Token Bucket",
                    "clientIp", clientIp
            ));
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .body(Map.of(
                            "status", "RATE_LIMITED",
                            "message", "Het token! Doi 1 giay de co token moi.",
                            "algorithm", "Token Bucket",
                            "clientIp", clientIp
                    ));
        }
    }
}
