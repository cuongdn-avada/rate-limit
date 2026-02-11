package com.dncuong.ws.rate_limit.algorithm.slidingwindowcounter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =====================================================================
 * BỘ TEST CHO THUẬT TOÁN SLIDING WINDOW COUNTER
 * =====================================================================
 *
 * Các test case bao phủ:
 * 1. Trường hợp cơ bản: request trong giới hạn → cho phép
 * 2. Trường hợp vượt giới hạn → từ chối
 * 3. Weighted average: counter cửa sổ trước ảnh hưởng lên cửa sổ hiện tại
 * 4. Trọng số giảm dần theo thời gian trong cửa sổ
 * 5. Nhiều key khác nhau → độc lập
 * 6. Tham số không hợp lệ → throw exception
 * 7. Thread-safe (concurrent)
 * 8. Giảm thiểu Boundary Problem (so sánh với Fixed Window)
 * 9. Nhảy xa hơn 1 cửa sổ → previousCounter reset
 * 10. Cửa sổ hoàn toàn mới (không có lịch sử)
 *
 * @author dncuong
 */
class SlidingWindowCounterRateLimiterTest {

    // =====================================================================
    // LỚP HỖ TRỢ TEST: Cho phép kiểm soát thời gian
    // =====================================================================

    static class TestableSlidingWindowCounter extends SlidingWindowCounterRateLimiter {
        private long currentTime;

        TestableSlidingWindowCounter(long maxRequests, long windowSizeInMillis, long startTime) {
            super(maxRequests, windowSizeInMillis);
            this.currentTime = startTime;
        }

        @Override
        protected long getCurrentTimeMillis() {
            return currentTime;
        }

        void advanceTime(long millis) {
            this.currentTime += millis;
        }

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
        // GIVEN: Tối đa 3 request / 1000ms, bắt đầu tại đầu cửa sổ
        // startTime = 0: đầu cửa sổ, không có previousCounter
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(3, 1000, 0);

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
        // GIVEN: Tối đa 3 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(3, 1000, 0);

        // WHEN: Gửi 3 request (đủ giới hạn)
        limiter.allowRequest("user1");
        limiter.allowRequest("user1");
        limiter.allowRequest("user1");

        // THEN: Request thứ 4 trở đi phải bị từ chối
        assertFalse(limiter.allowRequest("user1"), "Request 4 phai bi tu choi");
        assertFalse(limiter.allowRequest("user1"), "Request 5 cung phai bi tu choi");
    }

    // =====================================================================
    // TEST 3: Weighted average - counter cửa sổ trước ảnh hưởng
    // =====================================================================

    @Test
    @DisplayName("Weighted average: counter cua so truoc anh huong len cua so hien tai")
    void allowRequest_weightedAverage_shouldConsiderPreviousWindow() {
        // GIVEN: Tối đa 10 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(10, 1000, 0);

        // Gửi 8 request trong cửa sổ 1 [0-999ms]
        for (int i = 0; i < 8; i++) {
            assertTrue(limiter.allowRequest("user1"), "Request " + (i + 1) + " cua so 1");
        }

        // Chuyển sang cửa sổ 2, tại vị trí 25% (250ms sau đầu cửa sổ)
        // overlapRatio = 1 - 0.25 = 0.75
        // estimated (trước khi tăng counter) = (8 × 0.75) + currentCounter
        limiter.setCurrentTime(1250);

        // Request 1: estimated = 6.0 + 0 = 6.0 < 10 → OK, counter → 1
        // Request 2: estimated = 6.0 + 1 = 7.0 < 10 → OK, counter → 2
        // Request 3: estimated = 6.0 + 2 = 8.0 < 10 → OK, counter → 3
        // Request 4: estimated = 6.0 + 3 = 9.0 < 10 → OK, counter → 4
        assertTrue(limiter.allowRequest("user1"), "estimated = 6.0 < 10");
        assertTrue(limiter.allowRequest("user1"), "estimated = 7.0 < 10");
        assertTrue(limiter.allowRequest("user1"), "estimated = 8.0 < 10");
        assertTrue(limiter.allowRequest("user1"), "estimated = 9.0 < 10");

        // Request 5: estimated = 6.0 + 4 = 10.0 → >= 10, TỪ CHỐI
        assertFalse(limiter.allowRequest("user1"), "estimated = 10.0 >= 10, tu choi");
    }

    // =====================================================================
    // TEST 4: Trọng số giảm dần → quota dần được giải phóng
    // =====================================================================

    @Test
    @DisplayName("Trong so giam dan: cang xa ranh gioi, counter cu cang it anh huong")
    void allowRequest_decreasingWeight_shouldGraduallyFreeQuota() {
        // GIVEN: Tối đa 10 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(10, 1000, 0);

        // Dùng hết 10 request trong cửa sổ 1
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }
        assertFalse(limiter.allowRequest("user1"), "Cua so 1 da day");

        // Tại 25% cửa sổ 2: base = 10 × 0.75 = 7.5
        // estimated (trước increment) = 7.5 + currentCounter
        // Request 1: 7.5 + 0 = 7.5 < 10 → OK, counter → 1
        // Request 2: 7.5 + 1 = 8.5 < 10 → OK, counter → 2
        // Request 3: 7.5 + 2 = 9.5 < 10 → OK, counter → 3 (vẫn OK vì check trước increment!)
        // Request 4: 7.5 + 3 = 10.5 >= 10 → TỪ CHỐI
        limiter.setCurrentTime(1250);
        assertTrue(limiter.allowRequest("user1"), "25%: estimated = 7.5 < 10");
        assertTrue(limiter.allowRequest("user1"), "25%: estimated = 8.5 < 10");
        assertTrue(limiter.allowRequest("user1"), "25%: estimated = 9.5 < 10");
        assertFalse(limiter.allowRequest("user1"), "25%: estimated = 10.5 >= 10");

        // Tại 50% cửa sổ 2: base = 10 × 0.5 = 5.0, currentCounter = 3 (từ lúc 25%)
        // Request 1: 5.0 + 3 = 8.0 < 10 → OK, counter → 4
        // Request 2: 5.0 + 4 = 9.0 < 10 → OK, counter → 5
        // Request 3: 5.0 + 5 = 10.0 >= 10 → TỪ CHỐI
        limiter.setCurrentTime(1500);
        assertTrue(limiter.allowRequest("user1"), "50%: estimated = 8.0 < 10");
        assertTrue(limiter.allowRequest("user1"), "50%: estimated = 9.0 < 10");
        assertFalse(limiter.allowRequest("user1"), "50%: estimated = 10.0 >= 10");
    }

    // =====================================================================
    // TEST 5: Nhiều key khác nhau → hoàn toàn độc lập
    // =====================================================================

    @Test
    @DisplayName("Cac key khac nhau co counter doc lap")
    void allowRequest_differentKeys_shouldBeIndependent() {
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(2, 1000, 0);

        // user1 dùng hết giới hạn
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "user1 da het gioi han");

        // user2 vẫn có giới hạn riêng
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
                        new SlidingWindowCounterRateLimiter(0, 1000),
                "maxRequests = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowCounterRateLimiter(-5, 1000),
                "maxRequests am phai throw exception");
    }

    @Test
    @DisplayName("Nem IllegalArgumentException khi windowSize <= 0")
    void constructor_invalidWindowSize_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowCounterRateLimiter(10, 0),
                "windowSize = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new SlidingWindowCounterRateLimiter(10, -1000),
                "windowSize am phai throw exception");
    }

    // =====================================================================
    // TEST 7: Kiểm tra tính thread-safe (đồng thời)
    // =====================================================================

    @Test
    @DisplayName("Thread-safe: chi cho phep dung so request khi nhieu thread dong thoi")
    void allowRequest_concurrent_shouldBeThreadSafe() throws InterruptedException {
        // GIVEN: Tối đa 100 request trong cửa sổ 10 giây
        // Dùng rate limiter thật (không fake time) cho concurrent test
        SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(100, 10_000);

        int totalThreads = 200;
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

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

        // THEN: Chính xác 100 được phép, 100 bị từ chối
        assertEquals(100, allowedCount.get(),
                "Chi dung 100 request duoc cho phep");
        assertEquals(100, rejectedCount.get(),
                "Dung 100 request bi tu choi");
        assertEquals(totalThreads, allowedCount.get() + rejectedCount.get(),
                "Tong request phai bang tong so thread");
    }

    // =====================================================================
    // TEST 8: Giảm thiểu Boundary Problem (so sánh với Fixed Window)
    // =====================================================================

    @Test
    @DisplayName("GIAM THIEU Boundary Problem - uu diem chinh so voi Fixed Window")
    void allowRequest_reducedBoundaryProblem_demonstration() {
        // GIVEN: Tối đa 10 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(10, 1000, 0);

        // === KỊCH BẢN GIỐNG BOUNDARY PROBLEM TEST CỦA FIXED WINDOW ===

        // Gửi 10 request cuối cửa sổ 1 (tại 900ms)
        limiter.setCurrentTime(900);
        int allowedInWindow1 = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedInWindow1++;
            }
        }
        assertEquals(10, allowedInWindow1, "10 request cuoi cua so 1");

        // Ngay đầu cửa sổ 2 (tại 1000ms)
        // overlapRatio = 1 - 0/1000 = 1.0
        // estimated = (10 × 1.0) + 0 = 10.0 → >= 10, TỪ CHỐI!
        //
        // Fixed Window ở đây sẽ cho phép thêm 10 request (boundary problem!)
        // Sliding Window Counter: estimated = 10.0 → KHÔNG cho phép thêm!
        limiter.setCurrentTime(1000);
        int allowedInWindow2 = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedInWindow2++;
            }
        }

        // === KẾT QUẢ: Giảm thiểu đáng kể Boundary Problem ===
        // Fixed Window: 10 + 10 = 20 (gấp đôi!)
        // Sliding Window Counter: 10 + 0 = 10 (không burst!)
        assertEquals(0, allowedInWindow2,
                "KHONG cho phep them request vi estimated = 10.0 (counter cu van anh huong)");

        int total = allowedInWindow1 + allowedInWindow2;
        assertEquals(10, total,
                "Tong chi 10 request, khong phai 20 nhu Fixed Window");
    }

    // =====================================================================
    // TEST 9: Nhảy xa hơn 1 cửa sổ → previousCounter reset về 0
    // =====================================================================

    @Test
    @DisplayName("Nhay xa hon 1 cua so: previousCounter reset ve 0")
    void allowRequest_skipMultipleWindows_shouldResetPrevious() {
        // GIVEN: Tối đa 10 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(10, 1000, 0);

        // Dùng hết 10 request trong cửa sổ 1
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }

        // Nhảy xa đến cửa sổ 5 (thời điểm 4500ms)
        // Vì nhảy xa hơn 1 cửa sổ → previousCounter = 0
        // overlapRatio = 1 - 0.5 = 0.5
        // estimated = (0 × 0.5) + 0 = 0 → có đủ 10 slot
        limiter.setCurrentTime(4500);

        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.allowRequest("user1")) {
                allowed++;
            }
        }
        assertEquals(10, allowed,
                "Nhay xa: previousCounter = 0, co du 10 slot moi");
    }

    // =====================================================================
    // TEST 10: Nhiều cửa sổ liên tiếp hoạt động đúng
    // =====================================================================

    @Test
    @DisplayName("Nhieu cua so lien tiep hoat dong chinh xac")
    void allowRequest_consecutiveWindows_shouldWorkCorrectly() {
        // GIVEN: Tối đa 5 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(5, 1000, 0);

        // === Cửa sổ 1: dùng hết 5 slot ===
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"), "Cua so 1, request " + (i + 1));
        }
        assertFalse(limiter.allowRequest("user1"), "Cua so 1 da day");

        // === Giữa cửa sổ 2 (50%): base = 5 × 0.5 = 2.5 ===
        // estimated (trước increment) = 2.5 + currentCounter
        // Request 1: 2.5 + 0 = 2.5 < 5 → OK, counter → 1
        // Request 2: 2.5 + 1 = 3.5 < 5 → OK, counter → 2
        // Request 3: 2.5 + 2 = 4.5 < 5 → OK, counter → 3
        // Request 4: 2.5 + 3 = 5.5 >= 5 → TỪ CHỐI
        limiter.setCurrentTime(1500);
        assertTrue(limiter.allowRequest("user1"), "Cua so 2 tai 50%, estimated = 2.5");
        assertTrue(limiter.allowRequest("user1"), "Cua so 2 tai 50%, estimated = 3.5");
        assertTrue(limiter.allowRequest("user1"), "Cua so 2 tai 50%, estimated = 4.5");
        assertFalse(limiter.allowRequest("user1"), "Cua so 2 tai 50%, estimated = 5.5 >= 5");

        // === Cuối cửa sổ 2 (90%): base = 5 × 0.1 = 0.5, currentCounter = 3 ===
        // Request 1: 0.5 + 3 = 3.5 < 5 → OK, counter → 4
        // Request 2: 0.5 + 4 = 4.5 < 5 → OK, counter → 5
        // Request 3: 0.5 + 5 = 5.5 >= 5 → TỪ CHỐI
        limiter.setCurrentTime(1900);
        assertTrue(limiter.allowRequest("user1"), "Cua so 2 tai 90%, estimated = 3.5");
        assertTrue(limiter.allowRequest("user1"), "Cua so 2 tai 90%, estimated = 4.5");
        assertFalse(limiter.allowRequest("user1"), "Cua so 2 tai 90%, estimated = 5.5 >= 5");
    }

    // =====================================================================
    // TEST 11: Tại đúng ranh giới cửa sổ (edge case)
    // =====================================================================

    @Test
    @DisplayName("Request dung tai ranh gioi cua so duoc xu ly chinh xac")
    void allowRequest_exactlyAtWindowBoundary_shouldTransitionSmoothly() {
        // GIVEN: Tối đa 5 request / 1000ms
        TestableSlidingWindowCounter limiter = new TestableSlidingWindowCounter(5, 1000, 0);

        // Gửi 5 request trong cửa sổ 1
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }

        // Tại 999ms: vẫn ở cửa sổ 1 (windowId = 999/1000 = 0), counter đã = 5
        // estimated = 5 >= 5 → TỪ CHỐI
        limiter.setCurrentTime(999);
        assertFalse(limiter.allowRequest("user1"),
                "999ms: van o cua so 1, counter = 5, het quota");

        // Tại đúng ranh giới 1000ms: cửa sổ mới! overlapRatio = 1 - 0/1000 = 1.0
        // estimated = 5 × 1.0 + 0 = 5.0 → >= 5, TỪ CHỐI
        // Counter cửa sổ trước vẫn ảnh hưởng tối đa ngay tại ranh giới
        limiter.setCurrentTime(1000);
        assertFalse(limiter.allowRequest("user1"),
                "Tai ranh gioi: estimated = 5.0, counter cu van anh huong toi da");
    }
}
