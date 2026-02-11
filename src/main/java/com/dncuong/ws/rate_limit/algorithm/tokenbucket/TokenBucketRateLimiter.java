package com.dncuong.ws.rate_limit.algorithm.tokenbucket;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * =====================================================================
 * THUẬT TOÁN: TOKEN BUCKET (Xô chứa token)
 * =====================================================================
 *
 * NGUYÊN LÝ HOẠT ĐỘNG:
 * ---------------------
 * Hình dung một cái xô (bucket) chứa các token (thẻ):
 *
 * 1. Xô có sức chứa tối đa = bucketCapacity token.
 *    Ban đầu, xô đầy token.
 *
 * 2. Token được thêm vào xô với tốc độ cố định = refillRate token/giây.
 *    - Nếu xô đã đầy → token mới bị bỏ đi (không tràn)
 *    - Quá trình thêm token diễn ra LIÊN TỤC (không phải theo batch)
 *
 * 3. Khi request đến:
 *    a. Kiểm tra xô còn token không
 *    b. Nếu còn ≥ 1 token → lấy 1 token ra, CHO PHÉP request
 *    c. Nếu hết token (= 0) → TỪ CHỐI request
 *
 * VÍ DỤ TRỰC QUAN:
 * -----------------
 *   bucketCapacity = 10, refillRate = 2 token/giây
 *
 *   Thời điểm 0s: Xô đầy [●●●●●●●●●●] = 10 token
 *   5 request đến → lấy 5 token: [●●●●●○○○○○] = 5 token còn lại
 *   Sau 1 giây: thêm 2 token: [●●●●●●●○○○] = 7 token
 *   3 request đến → lấy 3 token: [●●●●○○○○○○] = 4 token
 *   ...
 *
 * BURST (ĐẶCTÍNH QUAN TRỌNG NHẤT):
 * ----------------------------------
 * Token Bucket cho phép BURST có kiểm soát:
 * - Nếu xô đầy (10 token), client có thể gửi 10 request NGAY LẬP TỨC
 * - Sau đó phải đợi token được nạp lại (2/giây)
 * - Burst tối đa = bucketCapacity
 *
 * Đây là khác biệt lớn so với các thuật toán window-based:
 * - Fixed/Sliding Window: giới hạn trong KHOẢNG THỜI GIAN
 * - Token Bucket: giới hạn TỐC ĐỘ TRUNG BÌNH, cho phép burst ngắn hạn
 *
 * LAZY REFILL (KỸ THUẬT NẠP LƯỜI):
 * ----------------------------------
 * Thay vì chạy timer thật để thêm token mỗi giây (tốn tài nguyên),
 * ta dùng kỹ thuật "lazy refill":
 * - Khi request đến, tính số token cần thêm dựa trên thời gian đã trôi qua
 * - tokensToAdd = (now - lastRefillTime) / 1000.0 × refillRate
 * - Cập nhật tokens = min(tokens + tokensToAdd, bucketCapacity)
 *
 * Kết quả giống hệt timer thật nhưng không cần background thread!
 *
 * SO SÁNH VỚI CÁC THUẬT TOÁN TRƯỚC:
 * ------------------------------------
 * | Thuật toán              | Cách giới hạn           | Burst?              |
 * |-------------------------|-------------------------|---------------------|
 * | Fixed Window            | Đếm trong cửa sổ cố định| Không kiểm soát     |
 * | Sliding Window Log      | Đếm trong cửa sổ trượt  | Không kiểm soát     |
 * | Sliding Window Counter  | Ước lượng cửa sổ trượt   | Không kiểm soát     |
 * | Token Bucket            | Tốc độ trung bình       | Burst có kiểm soát! |
 *
 * SỬ DỤNG TRONG THỰC TẾ:
 * ------------------------
 * - AWS API Gateway: dùng Token Bucket
 * - Stripe API: dùng Token Bucket
 * - GitHub API: dùng Token Bucket
 * - Google Cloud: dùng Token Bucket
 *
 * ƯU ĐIỂM:
 * ---------
 * - Cho phép burst có kiểm soát (rất phù hợp API thực tế)
 * - Bộ nhớ O(1) per key
 * - Hiệu năng O(1) per request
 * - Đảm bảo tốc độ trung bình dài hạn = refillRate
 *
 * NHƯỢC ĐIỂM:
 * ------------
 * - Cần cấu hình 2 tham số (capacity + refillRate) thay vì 1
 * - Burst có thể gây áp lực đột ngột lên hệ thống
 * - Khó đảm bảo "chính xác N request trong M giây"
 *
 * THREAD-SAFETY:
 * ---------------
 * - ConcurrentHashMap cho key → bucket mapping
 * - synchronized block trên từng Bucket để đảm bảo refill + consume là atomic
 *
 * @author dncuong
 */
public class TokenBucketRateLimiter implements RateLimiter {

    /**
     * Sức chứa tối đa của xô (số token tối đa).
     * Đây cũng chính là kích thước burst tối đa:
     * client có thể gửi tối đa bucketCapacity request ngay lập tức.
     */
    private final long bucketCapacity;

    /**
     * Tốc độ nạp token, tính bằng số token mỗi GIÂY.
     * Ví dụ: refillRate = 2.0 → mỗi giây thêm 2 token vào xô.
     *
     * Tốc độ nạp quyết định throughput trung bình dài hạn:
     * - Nếu refillRate = 10 → trung bình xử lý 10 request/giây
     * - Burst ngắn hạn có thể > 10, nhưng dài hạn trung bình = 10
     */
    private final double refillRate;

    /**
     * Bảng lưu trạng thái bucket cho mỗi key.
     */
    private final ConcurrentHashMap<String, Bucket> bucketMap;

    /**
     * Khởi tạo Token Bucket Rate Limiter.
     *
     * @param bucketCapacity sức chứa tối đa của xô (burst tối đa)
     * @param refillRate     tốc độ nạp token (số token mỗi giây)
     * @throws IllegalArgumentException nếu tham số không hợp lệ
     */
    public TokenBucketRateLimiter(long bucketCapacity, double refillRate) {
        // === Validate tham số đầu vào ===
        if (bucketCapacity <= 0) {
            throw new IllegalArgumentException(
                    "bucketCapacity phải lớn hơn 0, nhận được: " + bucketCapacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException(
                    "refillRate phải lớn hơn 0, nhận được: " + refillRate);
        }

        this.bucketCapacity = bucketCapacity;
        this.refillRate = refillRate;
        this.bucketMap = new ConcurrentHashMap<>();
    }

    /**
     * Kiểm tra và quyết định xem request từ key có được phép hay không.
     *
     * LUỒNG XỬ LÝ CHI TIẾT:
     * 1. Lấy hoặc tạo Bucket cho key (xô bắt đầu ĐẦY token)
     * 2. Tính số token cần nạp thêm (lazy refill)
     * 3. Nạp token (không vượt quá capacity)
     * 4. Nếu còn ≥ 1 token → lấy 1 token, trả về true
     * 5. Nếu hết token → trả về false
     *
     * @param key định danh của nguồn request
     * @return true nếu request được phép, false nếu bị từ chối
     */
    @Override
    public boolean allowRequest(String key) {
        long now = getCurrentTimeMillis();

        // === BƯỚC 1: Lấy hoặc tạo mới bucket cho key ===
        // Xô mới bắt đầu ĐẦY token (bucketCapacity)
        // → Client mới có thể burst ngay lập tức
        Bucket bucket = bucketMap.computeIfAbsent(key,
                k -> new Bucket(bucketCapacity, now));

        // === BƯỚC 2 + 3 + 4: Refill + kiểm tra + consume (thread-safe) ===
        synchronized (bucket) {
            // --- Bước 2: Tính số token cần nạp (lazy refill) ---
            // Thay vì chạy timer background, ta tính số token dựa trên
            // thời gian đã trôi qua kể từ lần refill cuối
            //
            // Ví dụ: refillRate = 2 token/s, đã trôi 3.5 giây
            //   tokensToAdd = 3.5 × 2 = 7 token
            long elapsedMillis = now - bucket.lastRefillTimestamp;
            double tokensToAdd = (elapsedMillis / 1000.0) * refillRate;

            // --- Bước 3: Nạp token vào xô ---
            // Cập nhật số token, nhưng không vượt quá sức chứa (capacity)
            // Math.min đảm bảo xô không bao giờ tràn
            if (tokensToAdd > 0) {
                bucket.tokens = Math.min(bucketCapacity, bucket.tokens + tokensToAdd);
                bucket.lastRefillTimestamp = now;
            }

            // --- Bước 4: Kiểm tra và consume token ---
            if (bucket.tokens >= 1) {
                // Còn token → lấy 1 token và cho phép request
                bucket.tokens -= 1;
                return true;
            } else {
                // Hết token → từ chối request
                return false;
            }
        }
    }

    /**
     * Lấy thời gian hiện tại (milliseconds).
     * Override trong test để kiểm soát thời gian (kỹ thuật "Seam").
     *
     * @return thời gian hiện tại tính bằng milliseconds kể từ Unix epoch
     */
    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Lớp nội bộ đại diện cho một "xô" chứa token.
     *
     * Mỗi key (IP, userId, ...) có một Bucket riêng:
     * - tokens: số token hiện có trong xô (dạng double để hỗ trợ nạp fractional)
     * - lastRefillTimestamp: thời điểm nạp token lần cuối (cho lazy refill)
     *
     * Tại sao tokens là double mà không phải long?
     * → Vì refillRate có thể là số thập phân (ví dụ: 0.5 token/giây)
     *   và lazy refill tính tokensToAdd có thể là phân số
     *   (ví dụ: trôi 300ms, refillRate = 2 → tokensToAdd = 0.6)
     *   Nếu dùng long, ta mất phần lẻ → tích lũy sai số theo thời gian
     */
    static class Bucket {
        /** Số token hiện có trong xô (có thể là số thập phân) */
        double tokens;

        /** Thời điểm nạp token lần cuối (milliseconds) */
        long lastRefillTimestamp;

        Bucket(long initialTokens, long timestamp) {
            this.tokens = initialTokens;
            this.lastRefillTimestamp = timestamp;
        }
    }
}
