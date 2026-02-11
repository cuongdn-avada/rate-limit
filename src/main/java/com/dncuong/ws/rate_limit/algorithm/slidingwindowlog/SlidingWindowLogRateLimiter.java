package com.dncuong.ws.rate_limit.algorithm.slidingwindowlog;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * =====================================================================
 * THUẬT TOÁN: SLIDING WINDOW LOG (Nhật ký cửa sổ trượt)
 * =====================================================================
 *
 * NGUYÊN LÝ HOẠT ĐỘNG:
 * ---------------------
 * 1. Mỗi key (IP, userId, ...) duy trì một danh sách (log) chứa
 *    timestamp của từng request đã được chấp nhận.
 *
 * 2. Khi một request mới đến:
 *    a. Xác định "cửa sổ" hiện tại: từ (now - windowSize) đến now
 *    b. Xóa tất cả timestamp cũ hơn (now - windowSize) khỏi log
 *       → Chỉ giữ lại các request trong cửa sổ hiện tại
 *    c. Đếm số request còn lại trong log
 *    d. Nếu count < maxRequests → CHO PHÉP, thêm timestamp mới vào log
 *    e. Nếu count >= maxRequests → TỪ CHỐI
 *
 * 3. "Cửa sổ" ở đây là cửa sổ TRƯỢT (sliding), không cố định:
 *    - Fixed Window: [0s-60s), [60s-120s), ... (ranh giới cố định)
 *    - Sliding Window: tại thời điểm T, cửa sổ là [T-60s, T]
 *      → Cửa sổ "trượt" theo thời gian thực!
 *
 * SO SÁNH VỚI FIXED WINDOW COUNTER:
 * ----------------------------------
 * Fixed Window Counter có "Boundary Problem" (vấn đề ranh giới):
 *   - 10 req cuối cửa sổ 1 + 10 req đầu cửa sổ 2 = 20 req trong ~2s
 *   - Nguyên nhân: counter reset đột ngột khi cửa sổ mới bắt đầu
 *
 * Sliding Window Log GIẢI QUYẾT vấn đề này:
 *   - Không có ranh giới cố định, cửa sổ luôn trượt theo thời gian
 *   - Tại BẤT KỲ thời điểm nào, nhìn lại windowSize vừa qua,
 *     luôn đảm bảo <= maxRequests
 *
 * ƯU ĐIỂM:
 * ---------
 * - Chính xác tuyệt đối: không có boundary problem
 * - Đảm bảo đúng maxRequests trong BẤT KỲ khoảng windowSize nào
 * - Logic đơn giản, dễ hiểu
 *
 * NHƯỢC ĐIỂM:
 * ------------
 * - Tốn bộ nhớ: O(n) cho mỗi key, với n = số request được chấp nhận
 *   trong cửa sổ hiện tại (tối đa = maxRequests timestamp)
 * - Mỗi request cần dọn dẹp log → chi phí O(k) với k = số entry hết hạn
 *
 * CẤU TRÚC DỮ LIỆU:
 * -------------------
 * - ConcurrentHashMap<String, RequestLog>: lưu log cho mỗi key
 *   + key: định danh client (IP, userId, apiKey, ...)
 *   + value: RequestLog chứa Deque<Long> các timestamp
 *
 * - Deque<Long> (ArrayDeque): danh sách timestamp, sắp xếp tự nhiên
 *   theo thứ tự thêm vào (timestamp cũ nhất ở đầu, mới nhất ở cuối)
 *   → Dọn dẹp từ đầu (pollFirst) rất hiệu quả: O(1) cho mỗi entry
 *
 * THREAD-SAFETY:
 * ---------------
 * - ConcurrentHashMap cho key → log mapping
 * - synchronized block trên từng RequestLog để đảm bảo
 *   việc dọn dẹp + đếm + thêm mới là nguyên tử (atomic)
 *
 * @author dncuong
 */
public class SlidingWindowLogRateLimiter implements RateLimiter {

    /**
     * Số request tối đa được phép trong một cửa sổ thời gian.
     * Ví dụ: maxRequests = 10 nghĩa là tối đa 10 request trong bất kỳ
     * khoảng thời gian windowSizeInMillis nào.
     */
    private final long maxRequests;

    /**
     * Kích thước cửa sổ trượt, tính bằng milliseconds.
     * Ví dụ: 60_000 = 1 phút, 1_000 = 1 giây.
     *
     * Khác với Fixed Window (cửa sổ cố định [0-60s), [60-120s), ...),
     * ở đây cửa sổ trượt liên tục: tại thời điểm T, cửa sổ là [T - windowSize, T].
     */
    private final long windowSizeInMillis;

    /**
     * Bảng lưu log request cho mỗi key.
     *
     * Mỗi key có một RequestLog riêng chứa danh sách timestamp
     * của các request đã được chấp nhận trong cửa sổ hiện tại.
     */
    private final ConcurrentHashMap<String, RequestLog> requestLogMap;

    /**
     * Khởi tạo Sliding Window Log Rate Limiter.
     *
     * @param maxRequests        số request tối đa cho phép trong mỗi cửa sổ trượt
     * @param windowSizeInMillis kích thước cửa sổ trượt tính bằng milliseconds
     * @throws IllegalArgumentException nếu tham số không hợp lệ
     */
    public SlidingWindowLogRateLimiter(long maxRequests, long windowSizeInMillis) {
        // === Validate tham số đầu vào ===
        if (maxRequests <= 0) {
            throw new IllegalArgumentException(
                    "maxRequests phải lớn hơn 0, nhận được: " + maxRequests);
        }
        if (windowSizeInMillis <= 0) {
            throw new IllegalArgumentException(
                    "windowSizeInMillis phải lớn hơn 0, nhận được: " + windowSizeInMillis);
        }

        this.maxRequests = maxRequests;
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestLogMap = new ConcurrentHashMap<>();
    }

    /**
     * Kiểm tra và quyết định xem request từ key có được phép hay không.
     *
     * LUỒNG XỬ LÝ CHI TIẾT:
     * 1. Lấy thời gian hiện tại (now)
     * 2. Tính điểm bắt đầu cửa sổ: windowStart = now - windowSizeInMillis
     * 3. Lấy hoặc tạo RequestLog cho key
     * 4. Trong synchronized block:
     *    a. Dọn dẹp: xóa tất cả timestamp <= windowStart (đã hết hạn)
     *    b. Đếm số request còn lại trong log
     *    c. Nếu count < maxRequests → thêm timestamp mới, trả về true
     *    d. Nếu count >= maxRequests → trả về false (không thêm vào log)
     *
     * VÍ DỤ MINH HỌA:
     *   maxRequests = 3, windowSize = 1000ms
     *   Log hiện tại: [500, 700, 900]
     *   Request mới tại thời điểm 1200ms:
     *     - windowStart = 1200 - 1000 = 200
     *     - Xóa tất cả timestamp <= 200 → không có gì để xóa
     *     - Count = 3 (500, 700, 900 đều > 200)
     *     - 3 >= 3 → TỪ CHỐI
     *
     *   Request mới tại thời điểm 1600ms:
     *     - windowStart = 1600 - 1000 = 600
     *     - Xóa timestamp <= 600 → xóa 500
     *     - Log còn: [700, 900], count = 2
     *     - 2 < 3 → CHO PHÉP, thêm 1600 vào log → [700, 900, 1600]
     *
     * @param key định danh của nguồn request
     * @return true nếu request được phép, false nếu bị từ chối
     */
    @Override
    public boolean allowRequest(String key) {
        // === BƯỚC 1: Lấy thời gian hiện tại ===
        long now = getCurrentTimeMillis();

        // === BƯỚC 2: Tính điểm bắt đầu cửa sổ trượt ===
        // Cửa sổ trượt: [now - windowSize, now]
        // Tất cả request có timestamp <= windowStart đã "hết hạn"
        long windowStart = now - windowSizeInMillis;

        // === BƯỚC 3: Lấy hoặc tạo mới log cho key ===
        // computeIfAbsent: atomic operation của ConcurrentHashMap
        RequestLog log = requestLogMap.computeIfAbsent(key, k -> new RequestLog());

        // === BƯỚC 4: Dọn dẹp + đếm + quyết định (thread-safe) ===
        // Phải dùng synchronized vì cần đảm bảo 3 thao tác sau là NGUYÊN TỬ:
        //   1. Dọn dẹp timestamp cũ
        //   2. Đếm số request hiện tại
        //   3. Thêm timestamp mới (nếu được phép)
        //
        // Nếu không synchronized, race condition:
        //   Thread A đếm count = 2, chưa kịp thêm
        //   Thread B đếm count = 2, cũng thêm
        //   → Cả 2 đều thêm, count thực tế = 4, vượt giới hạn 3!
        synchronized (log) {
            // --- Bước 4a: Dọn dẹp timestamp đã hết hạn ---
            // Vì timestamp được thêm theo thứ tự tăng dần (thời gian luôn tăng),
            // nên timestamp cũ nhất nằm ở đầu deque.
            // Chỉ cần xóa từ đầu cho đến khi gặp timestamp còn trong cửa sổ.
            // Hiệu quả: mỗi lần pollFirst() là O(1)
            while (!log.timestamps.isEmpty() && log.timestamps.peekFirst() <= windowStart) {
                log.timestamps.pollFirst();
            }

            // --- Bước 4b: Đếm số request trong cửa sổ hiện tại ---
            long currentCount = log.timestamps.size();

            // --- Bước 4c: Quyết định cho phép hay từ chối ---
            if (currentCount < maxRequests) {
                // Còn quota → thêm timestamp mới vào cuối log và cho phép
                log.timestamps.addLast(now);
                return true;
            } else {
                // Hết quota → từ chối, KHÔNG thêm timestamp vào log
                // (chỉ lưu request thành công, request bị từ chối không chiếm quota)
                return false;
            }
        }
    }

    /**
     * Lấy thời gian hiện tại (milliseconds).
     *
     * Tách thành method riêng để có thể override trong test,
     * giúp kiểm soát thời gian mà không cần Thread.sleep().
     * (Kỹ thuật "Seam" trong testing)
     *
     * @return thời gian hiện tại tính bằng milliseconds kể từ Unix epoch
     */
    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Lớp nội bộ lưu log các timestamp request cho một key cụ thể.
     *
     * Mỗi key (IP, userId, ...) có một RequestLog riêng chứa:
     * - timestamps: Deque<Long> lưu timestamp của các request đã được chấp nhận
     *
     * Tại sao dùng ArrayDeque mà không dùng LinkedList hay ArrayList?
     * - ArrayDeque: O(1) cho cả addLast() và pollFirst()
     *   → Hoàn hảo cho pattern "thêm cuối, xóa đầu" (FIFO)
     * - LinkedList: cũng O(1) nhưng tốn bộ nhớ hơn (mỗi node cần 2 con trỏ)
     * - ArrayList: pollFirst() là O(n) vì phải shift tất cả phần tử
     *
     * Tại sao không dùng TreeSet hay PriorityQueue?
     * - Không cần sắp xếp vì timestamp luôn tăng dần tự nhiên
     * - Deque đã tự động giữ thứ tự thêm vào
     */
    static class RequestLog {
        /**
         * Danh sách timestamp của các request đã được chấp nhận.
         * Sắp xếp tự nhiên theo thứ tự thêm vào (cũ nhất ở đầu, mới nhất ở cuối).
         *
         * Kích thước tối đa = maxRequests (vì chỉ thêm khi count < maxRequests)
         */
        final Deque<Long> timestamps;

        RequestLog() {
            this.timestamps = new ArrayDeque<>();
        }
    }
}
