package com.dncuong.ws.rate_limit.algorithm.fixedwindow;

import com.dncuong.ws.rate_limit.algorithm.RateLimiter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * =====================================================================
 * THUẬT TOÁN: FIXED WINDOW COUNTER (Bộ đếm cửa sổ cố định)
 * =====================================================================
 *
 * NGUYÊN LÝ HOẠT ĐỘNG:
 * ---------------------
 * 1. Chia trục thời gian thành các "cửa sổ" (window) có độ dài cố định.
 *    Ví dụ: nếu windowSizeInMillis = 60000 (1 phút), thì:
 *      - Cửa sổ 1: [00:00 - 01:00)
 *      - Cửa sổ 2: [01:00 - 02:00)
 *      - Cửa sổ 3: [02:00 - 03:00)
 *      - ...
 *
 * 2. Mỗi cửa sổ duy trì một bộ đếm (counter) cho mỗi key (ví dụ: mỗi IP).
 *    Bộ đếm bắt đầu từ 0 khi cửa sổ mới bắt đầu.
 *
 * 3. Khi một request đến:
 *    a. Xác định request thuộc cửa sổ nào: windowId = currentTime / windowSize
 *    b. Nếu cửa sổ đã thay đổi (windowId khác trước) → reset counter về 0
 *    c. Tăng counter lên 1
 *    d. Nếu counter <= maxRequests → CHO PHÉP request
 *    e. Nếu counter > maxRequests  → TỪ CHỐI request
 *
 * ƯU ĐIỂM:
 * ---------
 * - Rất đơn giản, dễ implement, dễ hiểu
 * - Sử dụng ít bộ nhớ (chỉ cần lưu counter + windowId cho mỗi key)
 * - Hiệu năng cao (O(1) cho mỗi request)
 *
 * NHƯỢC ĐIỂM:
 * ------------
 * - "Boundary Problem" (Vấn đề ranh giới): Tại ranh giới giữa 2 cửa sổ,
 *   lượng request thực tế có thể gấp đôi giới hạn cho phép.
 *   Ví dụ: giới hạn 10 req/phút
 *     - 10 request ở giây 59 của phút 1 (cuối cửa sổ 1) → OK
 *     - 10 request ở giây 0 của phút 2 (đầu cửa sổ 2) → OK
 *     → Trong 2 giây, hệ thống nhận 20 request - GẤP ĐÔI giới hạn!
 *
 * CẤU TRÚC DỮ LIỆU:
 * -------------------
 * - ConcurrentHashMap<String, WindowState>: lưu trạng thái cho mỗi key
 *   + key: định danh client (IP, userId, apiKey, ...)
 *   + value: WindowState chứa windowId hiện tại và counter
 *
 * THREAD-SAFETY:
 * ---------------
 * - Sử dụng ConcurrentHashMap để đảm bảo an toàn khi nhiều thread truy cập đồng thời
 * - Sử dụng synchronized block trên từng WindowState để đảm bảo
 *   việc kiểm tra + cập nhật counter là nguyên tử (atomic)
 *
 * @author dncuong
 */
public class FixedWindowCounterRateLimiter implements RateLimiter {

    /**
     * Số request tối đa được phép trong một cửa sổ thời gian.
     * Ví dụ: maxRequests = 10 nghĩa là tối đa 10 request/cửa sổ.
     */
    private final long maxRequests;

    /**
     * Kích thước cửa sổ thời gian, tính bằng milliseconds.
     * Ví dụ: 60_000 = 1 phút, 1_000 = 1 giây.
     */
    private final long windowSizeInMillis;

    /**
     * Bảng lưu trạng thái rate limit cho mỗi key.
     *
     * Tại sao dùng ConcurrentHashMap mà không dùng HashMap?
     * → Vì trong môi trường web server, nhiều request (nhiều thread) có thể
     *   đến cùng lúc và truy cập vào map này đồng thời.
     *   ConcurrentHashMap cho phép đọc/ghi an toàn từ nhiều thread
     *   mà không cần lock toàn bộ map.
     */
    private final ConcurrentHashMap<String, WindowState> windowStateMap;

    /**
     * Khởi tạo Fixed Window Counter Rate Limiter.
     *
     * @param maxRequests        số request tối đa cho phép trong mỗi cửa sổ
     * @param windowSizeInMillis kích thước cửa sổ tính bằng milliseconds
     * @throws IllegalArgumentException nếu tham số không hợp lệ
     */
    public FixedWindowCounterRateLimiter(long maxRequests, long windowSizeInMillis) {
        // === BƯỚC 1: Validate tham số đầu vào ===
        // Đảm bảo các giá trị phải dương, tránh lỗi logic khó debug
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
     * 1. Tính windowId hiện tại = currentTimeMillis / windowSizeInMillis
     *    → Tất cả request trong cùng một cửa sổ sẽ có cùng windowId
     *    → Ví dụ: windowSize = 60000ms (1 phút)
     *      - Request lúc 12:00:30 → windowId = timestamp_30s / 60000 = X
     *      - Request lúc 12:00:45 → windowId = timestamp_45s / 60000 = X (cùng cửa sổ)
     *      - Request lúc 12:01:05 → windowId = timestamp_65s / 60000 = X+1 (cửa sổ mới)
     *
     * 2. Lấy hoặc tạo WindowState cho key này
     *
     * 3. Trong synchronized block (đảm bảo thread-safety):
     *    a. Nếu windowId thay đổi → reset counter về 0 (cửa sổ mới)
     *    b. Tăng counter lên 1
     *    c. So sánh counter với maxRequests để quyết định
     *
     * @param key định danh của nguồn request
     * @return true nếu request được phép, false nếu bị từ chối
     */
    @Override
    public boolean allowRequest(String key) {
        // === BƯỚC 1: Tính ID của cửa sổ hiện tại ===
        // Chia thời gian hiện tại cho kích thước cửa sổ để xác định
        // request này thuộc cửa sổ nào.
        //
        // Phép chia nguyên đảm bảo tất cả timestamp trong cùng một khoảng
        // windowSize sẽ cho ra cùng một windowId.
        //
        // Ví dụ: windowSizeInMillis = 1000 (1 giây)
        //   - Thời điểm 1500ms → windowId = 1500/1000 = 1
        //   - Thời điểm 1999ms → windowId = 1999/1000 = 1 (cùng cửa sổ)
        //   - Thời điểm 2000ms → windowId = 2000/1000 = 2 (cửa sổ mới!)
        long currentWindowId = getCurrentTimeMillis() / windowSizeInMillis;

        // === BƯỚC 2: Lấy hoặc tạo mới trạng thái cho key ===
        // computeIfAbsent: nếu key chưa tồn tại → tạo WindowState mới
        //                   nếu key đã tồn tại → trả về WindowState hiện có
        // Đây là phép toán atomic của ConcurrentHashMap, thread-safe
        WindowState state = windowStateMap.computeIfAbsent(key,
                k -> new WindowState(currentWindowId));

        // === BƯỚC 3: Kiểm tra và cập nhật counter (thread-safe) ===
        // Phải dùng synchronized vì cần đảm bảo 3 thao tác sau là NGUYÊN TỬ:
        //   1. Kiểm tra windowId có thay đổi không
        //   2. Tăng counter
        //   3. So sánh counter với maxRequests
        //
        // Nếu không synchronized, race condition có thể xảy ra:
        //   Thread A đọc counter = 9, chưa kịp tăng
        //   Thread B đọc counter = 9, tăng lên 10
        //   Thread A tăng lên 10 → cả 2 đều được phép, nhưng thực tế đã 11 request!
        synchronized (state) {
            // Nếu windowId hiện tại khác với windowId đã lưu
            // → chúng ta đã bước sang một cửa sổ thời gian mới
            // → reset counter về 0 và cập nhật windowId
            if (state.windowId != currentWindowId) {
                state.windowId = currentWindowId;
                state.counter = 0;
            }

            // Tăng counter lên 1 cho request hiện tại
            state.counter++;

            // Nếu counter <= maxRequests → request được phép đi qua
            // Nếu counter > maxRequests → request bị từ chối
            return state.counter <= maxRequests;
        }
    }

    /**
     * Lấy thời gian hiện tại (milliseconds).
     *
     * Tại sao tách thành method riêng mà không gọi trực tiếp System.currentTimeMillis()?
     * → Để có thể OVERRIDE trong test! Khi test, ta cần kiểm soát thời gian
     *   (ví dụ: giả lập việc thời gian trôi qua 1 phút) mà không cần
     *   Thread.sleep() thật, giúp test chạy nhanh và ổn định.
     *
     * Đây là kỹ thuật phổ biến gọi là "Seam" trong testing -
     * tạo một điểm mà ta có thể can thiệp vào hành vi của class.
     *
     * @return thời gian hiện tại tính bằng milliseconds kể từ Unix epoch
     */
    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * Lớp nội bộ lưu trạng thái của một cửa sổ cho một key cụ thể.
     *
     * Mỗi key (ví dụ: mỗi IP) sẽ có một WindowState riêng, bao gồm:
     * - windowId: ID của cửa sổ hiện tại (dùng để phát hiện khi nào cửa sổ thay đổi)
     * - counter: số request đã nhận trong cửa sổ hiện tại
     *
     * Tại sao không dùng AtomicLong cho counter?
     * → Vì chúng ta đã dùng synchronized block bên ngoài rồi.
     *   Synchronized đảm bảo cả việc kiểm tra windowId + cập nhật counter
     *   là một khối nguyên tử. Nếu chỉ dùng AtomicLong cho counter,
     *   race condition vẫn xảy ra giữa bước kiểm tra windowId và bước tăng counter.
     */
    static class WindowState {
        /** ID của cửa sổ thời gian hiện tại, tính bằng currentTimeMillis / windowSize */
        long windowId;

        /** Số request đã đếm được trong cửa sổ hiện tại */
        long counter;

        WindowState(long windowId) {
            this.windowId = windowId;
            this.counter = 0;
        }
    }
}
