package com.dncuong.ws.rate_limit.algorithm.slidingwindowlog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =====================================================================
 * BỘ TEST CHO THUẬT TOÁN SLIDING WINDOW LOG
 * =====================================================================
 *
 * Các test case bao phủ:
 * 1. Trường hợp cơ bản: request trong giới hạn → cho phép
 * 2. Trường hợp vượt giới hạn → từ chối
 * 3. Trường hợp cửa sổ trượt: request cũ hết hạn → giải phóng quota
 * 4. Trường hợp nhiều key khác nhau → độc lập với nhau
 * 5. Trường hợp tham số không hợp lệ → throw exception
 * 6. Trường hợp đồng thời (concurrent) → thread-safe
 * 7. Chứng minh GIẢI QUYẾT Boundary Problem mà Fixed Window mắc phải
 * 8. Request bị từ chối không chiếm quota
 * 9. Dọn dẹp chính xác: chỉ xóa entry hết hạn
 *
 * KỸ THUẬT TEST:
 * - Sử dụng lớp TestableSlidingWindowLog (kế thừa từ lớp chính)
 *   để kiểm soát thời gian thay vì dùng Thread.sleep()
 *
 * @author dncuong
 */
class SlidingWindowLogRateLimiterTest {

    // =====================================================================
    // LỚP HỖ TRỢ TEST: Cho phép kiểm soát thời gian
    // =====================================================================

    /**
     * Lớp con kế thừa SlidingWindowLogRateLimiter, override getCurrentTimeMillis()
     * để trả về thời gian do chúng ta kiểm soát thay vì thời gian thật.
     */
    static class TestableSlidingWindowLog extends SlidingWindowLogRateLimiter {
        private long currentTime;

        TestableSlidingWindowLog(long maxRequests, long windowSizeInMillis, long startTime) {
            super(maxRequests, windowSizeInMillis);
            this.currentTime = startTime;
        }

        @Override
        protected long getCurrentTimeMillis() {
            return currentTime;
        }

        /** "Tua" thời gian về phía trước */
        void advanceTime(long millis) {
            this.currentTime += millis;
        }

        /** Đặt thời gian về một giá trị cụ thể */
        void setCurrentTime(long time) {
            this.currentTime = time;
        }
    }

    // =====================================================================
    // TEST 1: Request trong giới hạn → tất cả phải được cho phép
    // =====================================================================

    @Test
    @DisplayName("Cho phep tat ca request khi chua vuot gioi han")
    void allowRequest_withinLimit_shouldAllowAll() {
        // GIVEN: Rate limiter cho phép tối đa 3 request trong cửa sổ 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(3, 1000, 1000);

        // WHEN & THEN: 3 request đầu tiên phải đều được cho phép
        assertTrue(limiter.allowRequest("user1"), "Request 1 phai duoc cho phep");
        assertTrue(limiter.allowRequest("user1"), "Request 2 phai duoc cho phep");
        assertTrue(limiter.allowRequest("user1"), "Request 3 phai duoc cho phep");
    }

    // =====================================================================
    // TEST 2: Vượt giới hạn → request thứ N+1 phải bị từ chối
    // =====================================================================

    @Test
    @DisplayName("Tu choi request khi vuot qua gioi han")
    void allowRequest_exceedLimit_shouldReject() {
        // GIVEN: Rate limiter cho phép tối đa 3 request trong cửa sổ 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(3, 1000, 1000);

        // WHEN: Gửi 3 request (đủ giới hạn)
        limiter.allowRequest("user1"); // request 1 → OK
        limiter.allowRequest("user1"); // request 2 → OK
        limiter.allowRequest("user1"); // request 3 → OK

        // THEN: Request thứ 4 trở đi phải bị từ chối
        assertFalse(limiter.allowRequest("user1"), "Request 4 phai bi tu choi");
        assertFalse(limiter.allowRequest("user1"), "Request 5 cung phai bi tu choi");
    }

    // =====================================================================
    // TEST 3: Cửa sổ trượt - request cũ hết hạn → giải phóng quota
    // =====================================================================

    @Test
    @DisplayName("Giai phong quota khi request cu het han (cua so truot)")
    void allowRequest_slidingWindow_shouldExpireOldRequests() {
        // GIVEN: Tối đa 3 request / 1000ms, bắt đầu tại 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(3, 1000, 1000);

        // Gửi 3 request tại thời điểm 1000ms → hết quota
        assertTrue(limiter.allowRequest("user1"), "Request 1 tai 1000ms");
        assertTrue(limiter.allowRequest("user1"), "Request 2 tai 1000ms");
        assertTrue(limiter.allowRequest("user1"), "Request 3 tai 1000ms");
        assertFalse(limiter.allowRequest("user1"), "Da het quota tai 1000ms");

        // Tua đến 1500ms (chỉ 500ms sau) → request cũ chưa hết hạn
        // Cửa sổ: [500, 1500] → 3 request tại 1000ms vẫn trong cửa sổ
        limiter.setCurrentTime(1500);
        assertFalse(limiter.allowRequest("user1"), "1500ms: request cu van trong cua so");

        // Tua đến 2001ms (hơn 1000ms sau request đầu tiên)
        // Cửa sổ: [1001, 2001] → 3 request tại 1000ms đã hết hạn (1000 <= 1001? Đúng!)
        limiter.setCurrentTime(2001);
        assertTrue(limiter.allowRequest("user1"), "2001ms: request cu da het han, co quota moi");
    }

    // =====================================================================
    // TEST 4: Request hết hạn từng cái một (không phải tất cả cùng lúc)
    // =====================================================================

    @Test
    @DisplayName("Request het han tung cai mot theo thoi gian")
    void allowRequest_gradualExpiry_shouldFreeUpSlots() {
        // GIVEN: Tối đa 3 request / 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(3, 1000, 1000);

        // Gửi 3 request ở các thời điểm khác nhau
        limiter.setCurrentTime(1000);
        assertTrue(limiter.allowRequest("user1"), "Request 1 tai 1000ms");

        limiter.setCurrentTime(1200);
        assertTrue(limiter.allowRequest("user1"), "Request 2 tai 1200ms");

        limiter.setCurrentTime(1400);
        assertTrue(limiter.allowRequest("user1"), "Request 3 tai 1400ms");

        // Hết quota
        assertFalse(limiter.allowRequest("user1"), "Da het quota");

        // Tua đến 2001ms → request 1 (tại 1000ms) hết hạn, nhưng 2 và 3 vẫn còn
        // Cửa sổ: [1001, 2001] → chỉ còn request tại 1200ms và 1400ms
        limiter.setCurrentTime(2001);
        assertTrue(limiter.allowRequest("user1"), "2001ms: 1 slot da giai phong");
        assertFalse(limiter.allowRequest("user1"), "Van het quota (2 cu + 1 moi = 3)");

        // Tua đến 2201ms → request 2 (tại 1200ms) cũng hết hạn
        // Cửa sổ: [1201, 2201] → chỉ còn request tại 1400ms và 2001ms
        limiter.setCurrentTime(2201);
        assertTrue(limiter.allowRequest("user1"), "2201ms: them 1 slot giai phong");
    }

    // =====================================================================
    // TEST 5: Nhiều key khác nhau → hoàn toàn độc lập
    // =====================================================================

    @Test
    @DisplayName("Cac key khac nhau co log doc lap")
    void allowRequest_differentKeys_shouldBeIndependent() {
        // GIVEN: Rate limiter cho phép tối đa 2 request/cửa sổ
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(2, 1000, 1000);

        // WHEN: user1 dùng hết giới hạn
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "user1 da het gioi han");

        // THEN: user2 vẫn có giới hạn riêng
        assertTrue(limiter.allowRequest("user2"), "user2 phai doc lap voi user1");
        assertTrue(limiter.allowRequest("user2"), "user2 van con quota");
        assertFalse(limiter.allowRequest("user2"), "user2 da het gioi han cua minh");
    }

    // =====================================================================
    // TEST 6: Tham số không hợp lệ → phải ném exception
    // =====================================================================

    @Test
    @DisplayName("Nem IllegalArgumentException khi maxRequests <= 0")
    void constructor_invalidMaxRequests_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowLogRateLimiter(0, 1000),
                "maxRequests = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowLogRateLimiter(-5, 1000),
                "maxRequests am phai throw exception");
    }

    @Test
    @DisplayName("Nem IllegalArgumentException khi windowSize <= 0")
    void constructor_invalidWindowSize_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowLogRateLimiter(10, 0),
                "windowSize = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowLogRateLimiter(10, -1000),
                "windowSize am phai throw exception");
    }

    // =====================================================================
    // TEST 7: Kiểm tra tính thread-safe (đồng thời)
    // =====================================================================

    @Test
    @DisplayName("Thread-safe: chi cho phep dung so request khi nhieu thread dong thoi")
    void allowRequest_concurrent_shouldBeThreadSafe() throws InterruptedException {
        // GIVEN: Rate limiter cho phép tối đa 100 request trong cửa sổ 10 giây
        SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(100, 10_000);

        int totalThreads = 200;
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        // WHEN: Tạo 200 thread, mỗi thread gửi 1 request
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (limiter.allowRequest("concurrent-test-key")) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // THEN: Chính xác 100 request được phép, 100 bị từ chối
        assertEquals(100, allowedCount.get(),
                "Chi dung 100 request duoc cho phep");
        assertEquals(100, rejectedCount.get(),
                "Dung 100 request bi tu choi");
        assertEquals(totalThreads, allowedCount.get() + rejectedCount.get(),
                "Tong request phai bang tong so thread");
    }

    // =====================================================================
    // TEST 8: CHỨNG MINH giải quyết Boundary Problem
    // (So sánh trực tiếp với Fixed Window Counter)
    // =====================================================================

    @Test
    @DisplayName("GIAI QUYET Boundary Problem - uu diem chinh so voi Fixed Window")
    void allowRequest_noBoundaryProblem_demonstration() {
        // GIVEN: Tối đa 10 request / 1000ms, bắt đầu tại 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(10, 1000, 1000);

        // === KỊCH BẢN GIỐNG TEST BOUNDARY PROBLEM CỦA FIXED WINDOW ===
        // Trong Fixed Window: 10 req cuối cửa sổ 1 + 10 req đầu cửa sổ 2 = 20 req → BAD!
        // Trong Sliding Window Log: cửa sổ TRƯỢT, không có ranh giới cố định

        // Gửi 10 request tại thời điểm 1900ms (tương tự "cuối cửa sổ 1" trong Fixed Window)
        limiter.setCurrentTime(1900);
        int allowedFirstBatch = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedFirstBatch++;
            }
        }
        assertEquals(10, allowedFirstBatch, "10 request dau tien duoc cho phep");

        // Tua đến 2000ms (chỉ cách 100ms - tương tự "đầu cửa sổ 2" trong Fixed Window)
        // Trong Fixed Window: counter reset → cho phép thêm 10 request → BOUNDARY PROBLEM!
        // Trong Sliding Window Log: cửa sổ [1000, 2000] → 10 request tại 1900ms vẫn còn
        limiter.setCurrentTime(2000);
        int allowedSecondBatch = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedSecondBatch++;
            }
        }

        // === KẾT QUẢ: Sliding Window Log KHÔNG cho phép thêm request ===
        // Vì 10 request tại 1900ms vẫn nằm trong cửa sổ [1000, 2000]
        assertEquals(0, allowedSecondBatch,
                "KHONG CO Boundary Problem! 10 request cu van trong cua so truot");

        // Tổng chỉ 10, không phải 20 như Fixed Window!
        int totalInShortPeriod = allowedFirstBatch + allowedSecondBatch;
        assertEquals(10, totalInShortPeriod,
                "Tong chi 10 request (khong phai 20 nhu Fixed Window)");
    }

    // =====================================================================
    // TEST 9: Request bị từ chối KHÔNG chiếm quota
    // =====================================================================

    @Test
    @DisplayName("Request bi tu choi khong chiem quota trong log")
    void allowRequest_rejectedRequests_shouldNotConsumeQuota() {
        // GIVEN: Tối đa 2 request / 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(2, 1000, 1000);

        // Dùng hết quota
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));

        // Gửi thêm 100 request bị từ chối
        for (int i = 0; i < 100; i++) {
            assertFalse(limiter.allowRequest("user1"), "Request bi tu choi #" + (i + 1));
        }

        // Tua thời gian để request cũ hết hạn
        limiter.advanceTime(1001);

        // Phải có đúng 2 slot trống (100 request bị từ chối không chiếm quota)
        assertTrue(limiter.allowRequest("user1"), "Slot 1 phai available");
        assertTrue(limiter.allowRequest("user1"), "Slot 2 phai available");
        assertFalse(limiter.allowRequest("user1"), "Het quota lai");
    }

    // =====================================================================
    // TEST 10: Cửa sổ trượt liên tục qua nhiều giai đoạn
    // =====================================================================

    @Test
    @DisplayName("Cua so truot lien tuc qua nhieu giai doan")
    void allowRequest_continuousSlidingWindow_shouldWorkCorrectly() {
        // GIVEN: Tối đa 2 request / 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(2, 1000, 1000);

        // Giai đoạn 1: 2 request tại 1000ms
        limiter.setCurrentTime(1000);
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));

        // Giai đoạn 2: thêm 1 request tại 1500ms → hết quota
        // Cửa sổ [500, 1500] chứa 2 request tại 1000ms
        limiter.setCurrentTime(1500);
        assertFalse(limiter.allowRequest("user1"), "Van het quota");

        // Giai đoạn 3: tại 2001ms → request 1000ms hết hạn
        // Cửa sổ [1001, 2001] → không còn request nào
        limiter.setCurrentTime(2001);
        assertTrue(limiter.allowRequest("user1"), "Request cu het han");
        assertTrue(limiter.allowRequest("user1"), "Slot 2");
        assertFalse(limiter.allowRequest("user1"), "Het quota lai");

        // Giai đoạn 4: nhảy xa đến 10001ms
        // Cửa sổ [9001, 10001] → request tại 2001ms đã hết hạn từ lâu
        limiter.setCurrentTime(10001);
        assertTrue(limiter.allowRequest("user1"), "Cua so moi hoan toan");
    }

    // =====================================================================
    // TEST 11: Chính xác ở ranh giới thời gian (edge case)
    // =====================================================================

    @Test
    @DisplayName("Chinh xac tai ranh gioi thoi gian: request het han dung luc")
    void allowRequest_exactExpiry_shouldExpireCorrectly() {
        // GIVEN: Tối đa 1 request / 1000ms
        TestableSlidingWindowLog limiter = new TestableSlidingWindowLog(1, 1000, 1000);

        // Gửi 1 request tại 1000ms
        limiter.setCurrentTime(1000);
        assertTrue(limiter.allowRequest("user1"), "Request tai 1000ms");

        // Tại 1999ms: cửa sổ [999, 1999] → request tại 1000ms vẫn còn
        limiter.setCurrentTime(1999);
        assertFalse(limiter.allowRequest("user1"), "1999ms: request 1000ms van trong cua so");

        // Tại 2000ms: cửa sổ [1000, 2000] → request tại 1000ms hết hạn
        // (vì điều kiện xóa là timestamp <= windowStart, tức 1000 <= 1000)
        limiter.setCurrentTime(2000);
        assertTrue(limiter.allowRequest("user1"), "2000ms: request 1000ms vua het han");
    }
}
