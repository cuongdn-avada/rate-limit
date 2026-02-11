package com.dncuong.ws.rate_limit.algorithm.slidingwindowcounter;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * =====================================================================
 * THUẬT TOÁN: SLIDING WINDOW COUNTER (Bộ đếm cửa sổ trượt)
 * =====================================================================
 *
 * NGUYÊN LÝ HOẠT ĐỘNG:
 * ---------------------
 * Đây là thuật toán KẾT HỢP (hybrid) giữa Fixed Window Counter và
 * Sliding Window Log, lấy ưu điểm của cả hai:
 *   - Từ Fixed Window: bộ nhớ O(1) per key, hiệu năng O(1)
 *   - Từ Sliding Window: giảm thiểu boundary problem bằng cửa sổ "trượt"
 *
 * CÁCH HOẠT ĐỘNG:
 * ----------------
 * 1. Chia thời gian thành các cửa sổ cố định (giống Fixed Window).
 *    Ví dụ: windowSize = 60s → [0-60s), [60-120s), ...
 *
 * 2. Lưu counter của CẢ cửa sổ hiện tại VÀ cửa sổ trước đó.
 *
 * 3. Khi request đến, tính SỐ REQUEST ƯỚC LƯỢNG trong cửa sổ trượt
 *    bằng công thức trung bình có trọng số (weighted average):
 *
 *    estimatedCount = (previousCounter × overlapRatio) + currentCounter
 *
 *    Trong đó:
 *    - previousCounter: số request trong cửa sổ trước
 *    - currentCounter: số request trong cửa sổ hiện tại
 *    - overlapRatio: phần trăm cửa sổ trước CHỒNG LẤP với cửa sổ trượt
 *      = 1 - (elapsedTimeInCurrentWindow / windowSize)
 *      = 1 - positionInWindow
 *
 * VÍ DỤ MINH HỌA:
 * ----------------
 *   windowSize = 60s, maxRequests = 100
 *   Cửa sổ trước [0-60s): previousCounter = 80
 *   Cửa sổ hiện tại [60-120s): currentCounter = 30
 *   Thời điểm hiện tại: 75s (đã đi được 15s = 25% trong cửa sổ hiện tại)
 *
 *   overlapRatio = 1 - 15/60 = 1 - 0.25 = 0.75
 *   (75% cửa sổ trước vẫn "chồng lấp" với cửa sổ trượt)
 *
 *   estimatedCount = (80 × 0.75) + 30 = 60 + 30 = 90
 *   90 < 100 → CHO PHÉP ✅
 *
 *   Nếu ở thời điểm 90s (50% cửa sổ hiện tại):
 *   overlapRatio = 1 - 30/60 = 0.5
 *   estimatedCount = (80 × 0.5) + 30 = 40 + 30 = 70 → CHO PHÉP ✅
 *
 * TẠI SAO GIẢM THIỂU BOUNDARY PROBLEM?
 * --------------------------------------
 * Fixed Window: counter RESET đột ngột → burst gấp đôi tại ranh giới
 * Sliding Window Counter: trọng số giảm DẦN DẦN → chuyển tiếp mượt mà
 *
 *   Tại đầu cửa sổ mới (vừa qua ranh giới):
 *     overlapRatio ≈ 1.0 → gần như TOÀN BỘ counter cửa sổ trước được tính
 *     → Không thể burst vì counter cũ vẫn "nặng"
 *
 *   Tại cuối cửa sổ hiện tại:
 *     overlapRatio ≈ 0.0 → counter cửa sổ trước gần như không còn ảnh hưởng
 *
 * SO SÁNH 3 THUẬT TOÁN:
 * ----------------------
 * | Thuật toán          | Bộ nhớ/key | Thời gian | Chính xác        |
 * |---------------------|------------|-----------|------------------|
 * | Fixed Window        | O(1)       | O(1)      | Có boundary prob |
 * | Sliding Window Log  | O(max)     | O(k)      | Tuyệt đối        |
 * | Sliding Window Counter | O(1)    | O(1)      | Xấp xỉ, rất tốt |
 *
 * ƯU ĐIỂM:
 * ---------
 * - Bộ nhớ O(1) per key (chỉ cần 2 counter + 1 windowId)
 * - Hiệu năng O(1) per request
 * - Giảm thiểu boundary problem đáng kể (không hoàn toàn triệt tiêu)
 * - Được dùng rộng rãi trong production (Cloudflare, nhiều CDN)
 *
 * NHƯỢC ĐIỂM:
 * ------------
 * - Chỉ là ước lượng (approximation), không chính xác tuyệt đối
 * - Trong trường hợp xấu nhất, có thể sai lệch nhỏ so với giới hạn thực
 * - Phức tạp hơn Fixed Window (cần hiểu weighted average)
 *
 * CẤU TRÚC DỮ LIỆU:
 * -------------------
 * - ConcurrentHashMap<String, WindowState>: lưu trạng thái cho mỗi key
 *   + key: định danh client (IP, userId, apiKey, ...)
 *   + value: WindowState chứa windowId, counter hiện tại, counter trước
 *
 * THREAD-SAFETY:
 * ---------------
 * - ConcurrentHashMap cho key → state mapping
 * - synchronized block trên từng WindowState
 *
 * @author dncuong
 */
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    /**
     * Số request tối đa được phép trong một cửa sổ thời gian.
     */
    private final long maxRequests;

    /**
     * Kích thước cửa sổ thời gian, tính bằng milliseconds.
     * Ví dụ: 60_000 = 1 phút, 1_000 = 1 giây.
     */
    private final long windowSizeInMillis;

    /**
     * Bảng lưu trạng thái rate limit cho mỗi key.
     */
    private final ConcurrentHashMap<String, WindowState> windowStateMap;

    /**
     * Khởi tạo Sliding Window Counter Rate Limiter.
     *
     * @param maxRequests        số request tối đa cho phép trong mỗi cửa sổ
     * @param windowSizeInMillis kích thước cửa sổ tính bằng milliseconds
     * @throws IllegalArgumentException nếu tham số không hợp lệ
     */
    public SlidingWindowCounterRateLimiter(long maxRequests, long windowSizeInMillis) {
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
        this.windowStateMap = new ConcurrentHashMap<>();
    }

    /**
     * Kiểm tra và quyết định xem request từ key có được phép hay không.
     *
     * LUỒNG XỬ LÝ CHI TIẾT:
     * 1. Tính windowId hiện tại (giống Fixed Window)
     * 2. Tính vị trí trong cửa sổ hiện tại (positionInWindow)
     * 3. Tính overlapRatio = 1 - positionInWindow
     * 4. Tính estimatedCount = (previousCounter × overlapRatio) + currentCounter
     * 5. Nếu estimatedCount < maxRequests → CHO PHÉP, tăng currentCounter
     * 6. Nếu estimatedCount >= maxRequests → TỪ CHỐI
     *
     * @param key định danh của nguồn request
     * @return true nếu request được phép, false nếu bị từ chối
     */
    @Override
    public boolean allowRequest(String key) {
        // === BƯỚC 1: Lấy thời gian hiện tại và tính windowId ===
        long now = getCurrentTimeMillis();
        long currentWindowId = now / windowSizeInMillis;

        // === BƯỚC 2: Tính vị trí trong cửa sổ hiện tại ===
        // elapsedInCurrentWindow: số milliseconds đã trôi qua trong cửa sổ hiện tại
        // Ví dụ: windowSize = 1000ms, now = 2300ms
        //   currentWindowId = 2300/1000 = 2
        //   windowStart = 2 × 1000 = 2000ms
        //   elapsed = 2300 - 2000 = 300ms (đã đi được 300ms trong cửa sổ này)
        long elapsedInCurrentWindow = now - (currentWindowId * windowSizeInMillis);

        // positionInWindow: tỉ lệ thời gian đã trôi qua trong cửa sổ (0.0 → 1.0)
        // Ví dụ: 300/1000 = 0.3 (đã đi được 30% cửa sổ)
        double positionInWindow = (double) elapsedInCurrentWindow / windowSizeInMillis;

        // === BƯỚC 3: Tính overlapRatio ===
        // overlapRatio: phần trăm cửa sổ trước chồng lấp với cửa sổ trượt
        // Khi vừa bước vào cửa sổ mới (position ≈ 0): overlap ≈ 1.0 (cửa sổ trước rất quan trọng)
        // Khi gần cuối cửa sổ (position ≈ 1): overlap ≈ 0.0 (cửa sổ trước không còn ảnh hưởng)
        double overlapRatio = 1.0 - positionInWindow;

        // === BƯỚC 4: Lấy hoặc tạo mới trạng thái cho key ===
        WindowState state = windowStateMap.computeIfAbsent(key,
                k -> new WindowState(currentWindowId));

        // === BƯỚC 5: Tính toán và quyết định (thread-safe) ===
        synchronized (state) {
            // --- Bước 5a: Cập nhật cửa sổ nếu cần ---
            // Nếu windowId thay đổi, cần "trượt" cửa sổ:
            //   - Counter hiện tại → trở thành counter trước
            //   - Counter mới → bắt đầu từ 0
            if (state.currentWindowId != currentWindowId) {
                if (currentWindowId == state.currentWindowId + 1) {
                    // Chuyển sang cửa sổ KỀ NGAY SAU → giữ counter cũ làm previous
                    state.previousCounter = state.currentCounter;
                } else {
                    // Nhảy xa hơn 1 cửa sổ → counter cũ đã quá lâu, không còn ý nghĩa
                    state.previousCounter = 0;
                }
                state.currentCounter = 0;
                state.currentWindowId = currentWindowId;
            }

            // --- Bước 5b: Tính số request ước lượng ---
            // Công thức weighted average:
            //   estimated = (previousCounter × overlapRatio) + currentCounter
            //
            // Ý nghĩa: "Trong cửa sổ trượt hiện tại, ước lượng có bao nhiêu request?"
            //   - Phần từ cửa sổ trước: previousCounter × overlap (chỉ tính phần chồng lấp)
            //   - Phần từ cửa sổ hiện tại: currentCounter (tính hết)
            double estimatedCount = (state.previousCounter * overlapRatio) + state.currentCounter;

            // --- Bước 5c: Quyết định ---
            if (estimatedCount < maxRequests) {
                // Còn quota → tăng counter cửa sổ hiện tại và cho phép
                state.currentCounter++;
                return true;
            } else {
                // Hết quota → từ chối
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
     * Lớp nội bộ lưu trạng thái cửa sổ cho một key cụ thể.
     *
     * So sánh với Fixed Window Counter (chỉ cần windowId + counter):
     * - Sliding Window Counter cần THÊM previousCounter
     * - Vẫn chỉ O(1) bộ nhớ per key (3 giá trị thay vì 2)
     *
     * So sánh với Sliding Window Log (cần lưu maxRequests timestamps):
     * - Tiết kiệm bộ nhớ rất nhiều: 24 bytes vs 8 × maxRequests bytes
     * - Ví dụ: maxRequests = 1000 → 24 bytes vs 8000 bytes
     */
    static class WindowState {
        /** ID của cửa sổ thời gian hiện tại */
        long currentWindowId;

        /** Số request đã đếm được trong cửa sổ HIỆN TẠI */
        long currentCounter;

        /**
         * Số request đã đếm được trong cửa sổ TRƯỚC ĐÓ.
         * Dùng để tính weighted average khi ước lượng số request
         * trong cửa sổ trượt.
         */
        long previousCounter;

        WindowState(long windowId) {
            this.currentWindowId = windowId;
            this.currentCounter = 0;
            this.previousCounter = 0;
        }
    }
}
