package com.dncuong.ws.rate_limit.algorithm.fixedwindow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =====================================================================
 * BỘ TEST CHO THUẬT TOÁN FIXED WINDOW COUNTER
 * =====================================================================
 *
 * Các test case bao phủ:
 * 1. Trường hợp cơ bản: request trong giới hạn → cho phép
 * 2. Trường hợp vượt giới hạn → từ chối
 * 3. Trường hợp cửa sổ reset → counter về 0
 * 4. Trường hợp nhiều key khác nhau → độc lập với nhau
 * 5. Trường hợp tham số không hợp lệ → throw exception
 * 6. Trường hợp đồng thời (concurrent) → thread-safe
 * 7. Trường hợp ranh giới (boundary problem) → minh họa nhược điểm
 *
 * KỸ THUẬT TEST:
 * - Sử dụng lớp TestableFixedWindowCounter (kế thừa từ lớp chính)
 *   để kiểm soát thời gian thay vì dùng Thread.sleep()
 * - Điều này giúp test chạy nhanh, ổn định, không phụ thuộc vào
 *   tốc độ CPU hay scheduling của OS
 *
 * @author dncuong
 */
class FixedWindowCounterRateLimiterTest {

    // =====================================================================
    // LỚP HỖ TRỢ TEST: Cho phép kiểm soát thời gian
    // =====================================================================

    /**
     * Lớp con kế thừa FixedWindowCounterRateLimiter, override getCurrentTimeMillis()
     * để trả về thời gian do chúng ta kiểm soát thay vì thời gian thật.
     *
     * Kỹ thuật này gọi là "Test Double" - cụ thể là "Fake" object.
     * Giúp test không cần Thread.sleep() → chạy nhanh và đáng tin cậy.
     */
    static class TestableFixedWindowCounter extends FixedWindowCounterRateLimiter {
        /** Thời gian giả lập, ta có thể thay đổi tùy ý trong test */
        private long currentTime;

        TestableFixedWindowCounter(long maxRequests, long windowSizeInMillis, long startTime) {
            super(maxRequests, windowSizeInMillis);
            this.currentTime = startTime;
        }

        /** Override để trả về thời gian giả lập thay vì System.currentTimeMillis() */
        @Override
        protected long getCurrentTimeMillis() {
            return currentTime;
        }

        /** Phương thức tiện ích: "tua" thời gian về phía trước */
        void advanceTime(long millis) {
            this.currentTime += millis;
        }

        /** Phương thức tiện ích: đặt thời gian về một giá trị cụ thể */
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
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(3, 1000, 0);

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
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(3, 1000, 0);

        // WHEN: Gửi 3 request (đủ giới hạn)
        limiter.allowRequest("user1"); // request 1 → OK
        limiter.allowRequest("user1"); // request 2 → OK
        limiter.allowRequest("user1"); // request 3 → OK

        // THEN: Request thứ 4 trở đi phải bị từ chối
        assertFalse(limiter.allowRequest("user1"), "Request 4 phai bi tu choi");
        assertFalse(limiter.allowRequest("user1"), "Request 5 cung phai bi tu choi");
    }

    // =====================================================================
    // TEST 3: Khi cửa sổ thời gian mới bắt đầu → counter phải reset
    // =====================================================================

    @Test
    @DisplayName("Reset counter khi cua so moi bat dau")
    void allowRequest_newWindow_shouldResetCounter() {
        // GIVEN: Rate limiter cho phép tối đa 2 request trong cửa sổ 1000ms
        // Bắt đầu tại thời điểm 0ms
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(2, 1000, 0);

        // WHEN: Dùng hết 2 request trong cửa sổ đầu tiên [0-999ms]
        assertTrue(limiter.allowRequest("user1"), "Request 1, cua so 1");
        assertTrue(limiter.allowRequest("user1"), "Request 2, cua so 1");
        assertFalse(limiter.allowRequest("user1"), "Request 3 bi tu choi, cua so 1 da day");

        // Tua thời gian đến cửa sổ thứ 2 [1000-1999ms]
        limiter.advanceTime(1000);

        // THEN: Counter đã reset, request mới phải được cho phép
        assertTrue(limiter.allowRequest("user1"), "Request 1, cua so 2 - counter da reset");
        assertTrue(limiter.allowRequest("user1"), "Request 2, cua so 2");
        assertFalse(limiter.allowRequest("user1"), "Request 3, cua so 2 - da day lai");
    }

    // =====================================================================
    // TEST 4: Nhiều key khác nhau → hoàn toàn độc lập
    // =====================================================================

    @Test
    @DisplayName("Cac key khac nhau co counter doc lap")
    void allowRequest_differentKeys_shouldBeIndependent() {
        // GIVEN: Rate limiter cho phép tối đa 2 request/cửa sổ
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(2, 1000, 0);

        // WHEN: user1 dùng hết giới hạn
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "user1 da het gioi han");

        // THEN: user2 vẫn có giới hạn riêng, không bị ảnh hưởng bởi user1
        assertTrue(limiter.allowRequest("user2"), "user2 phai doc lap voi user1");
        assertTrue(limiter.allowRequest("user2"), "user2 van con quota");
        assertFalse(limiter.allowRequest("user2"), "user2 da het gioi han cua minh");
    }

    // =====================================================================
    // TEST 5: Tham số không hợp lệ → phải ném exception
    // =====================================================================

    @Test
    @DisplayName("Ném IllegalArgumentException khi maxRequests <= 0")
    void constructor_invalidMaxRequests_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new FixedWindowCounterRateLimiter(0, 1000),
                "maxRequests = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new FixedWindowCounterRateLimiter(-5, 1000),
                "maxRequests am phai throw exception");
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi windowSize <= 0")
    void constructor_invalidWindowSize_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new FixedWindowCounterRateLimiter(10, 0),
                "windowSize = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new FixedWindowCounterRateLimiter(10, -1000),
                "windowSize am phai throw exception");
    }

    // =====================================================================
    // TEST 6: Kiểm tra tính thread-safe (đồng thời)
    // =====================================================================

    @Test
    @DisplayName("Thread-safe: chi cho phep dung so request khi nhieu thread dong thoi")
    void allowRequest_concurrent_shouldBeThreadSafe() throws InterruptedException {
        // GIVEN: Rate limiter cho phép tối đa 100 request trong cửa sổ 10 giây
        // Dùng rate limiter thật (không fake time) vì test concurrent cần real timing
        FixedWindowCounterRateLimiter limiter = new FixedWindowCounterRateLimiter(100, 10_000);

        // Số thread đồng thời: 200 (gấp đôi giới hạn)
        int totalThreads = 200;

        // Bộ đếm atomic: đếm số request được cho phép và bị từ chối
        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        // CountDownLatch: đảm bảo tất cả thread bắt đầu cùng lúc
        // Giống như hàng rào: tất cả thread đợi ở đây cho đến khi được phép chạy
        CountDownLatch startLatch = new CountDownLatch(1);

        // Latch để đợi tất cả thread hoàn thành
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);

        // WHEN: Tạo 200 thread, mỗi thread gửi 1 request
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    // Đợi cho đến khi tất cả thread sẵn sàng
                    startLatch.await();

                    // Gửi request
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

        // Mở "cổng" → tất cả 200 thread chạy đồng thời
        startLatch.countDown();

        // Đợi tất cả thread hoàn thành
        doneLatch.await();
        executor.shutdown();

        // THEN: Chính xác 100 request được phép, 100 bị từ chối
        assertEquals(100, allowedCount.get(),
                "Chi dung 100 request duoc cho phep (khong hon, khong kem)");
        assertEquals(100, rejectedCount.get(),
                "Dung 100 request bi tu choi");

        // Tổng phải bằng số thread
        assertEquals(totalThreads, allowedCount.get() + rejectedCount.get(),
                "Tong request phai bang tong so thread");
    }

    // =====================================================================
    // TEST 7: Minh họa Boundary Problem (nhược điểm chính của Fixed Window)
    // =====================================================================

    @Test
    @DisplayName("Minh hoa Boundary Problem - nhuoc diem chinh cua Fixed Window")
    void allowRequest_boundaryProblem_demonstration() {
        // GIVEN: Rate limiter cho phép tối đa 10 request/cửa sổ 1000ms
        // Bắt đầu tại thời điểm 0ms
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(10, 1000, 0);

        // === KỊCH BẢN: Gửi 10 request cuối cửa sổ 1 + 10 request đầu cửa sổ 2 ===

        // Tua đến cuối cửa sổ 1 (thời điểm 900ms, tức 100ms trước khi cửa sổ kết thúc)
        limiter.setCurrentTime(900);

        // Gửi 10 request ở cuối cửa sổ 1 → tất cả OK vì counter mới bắt đầu đếm
        int allowedInWindow1 = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedInWindow1++;
            }
        }
        assertEquals(10, allowedInWindow1, "10 request cuoi cua so 1 deu duoc cho phep");

        // Tua đến đầu cửa sổ 2 (thời điểm 1000ms, chỉ cách 100ms!)
        limiter.setCurrentTime(1000);

        // Gửi 10 request ở đầu cửa sổ 2 → tất cả OK vì counter đã reset!
        int allowedInWindow2 = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowedInWindow2++;
            }
        }
        assertEquals(10, allowedInWindow2, "10 request dau cua so 2 deu duoc cho phep");

        // === KẾT LUẬN: Boundary Problem ===
        // Trong khoảng thời gian chỉ 100ms (từ 900ms đến 1000ms),
        // hệ thống đã cho phép 20 request - GẤP ĐÔI giới hạn 10 req/giây!
        // Đây chính là nhược điểm lớn nhất của Fixed Window Counter.
        int totalInShortPeriod = allowedInWindow1 + allowedInWindow2;
        assertEquals(20, totalInShortPeriod,
                "BOUNDARY PROBLEM: 20 request trong 100ms, gap doi gioi han 10/s!");
    }

    // =====================================================================
    // TEST 8: Nhiều cửa sổ liên tiếp hoạt động đúng
    // =====================================================================

    @Test
    @DisplayName("Nhieu cua so lien tiep hoat dong chinh xac")
    void allowRequest_multipleConsecutiveWindows_shouldWorkCorrectly() {
        // GIVEN: Tối đa 3 request/cửa sổ 1000ms
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(3, 1000, 0);

        // === Cửa sổ 1 [0-999ms] ===
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "Cua so 1 da day");

        // === Cửa sổ 2 [1000-1999ms] ===
        limiter.advanceTime(1000);
        assertTrue(limiter.allowRequest("user1"), "Cua so 2, counter reset");
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "Cua so 2 da day");

        // === Cửa sổ 3 [2000-2999ms] ===
        limiter.advanceTime(1000);
        assertTrue(limiter.allowRequest("user1"), "Cua so 3, counter reset");

        // === Nhảy xa đến cửa sổ 100 ===
        limiter.advanceTime(97_000);
        assertTrue(limiter.allowRequest("user1"), "Cua so 100, counter reset");
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "Cua so 100 da day");
    }

    // =====================================================================
    // TEST 9: Request đúng ở ranh giới cửa sổ (edge case)
    // =====================================================================

    @Test
    @DisplayName("Request dung tai ranh gioi cua so duoc xu ly chinh xac")
    void allowRequest_exactlyAtWindowBoundary_shouldStartNewWindow() {
        // GIVEN: 2 request/cửa sổ 1000ms, bắt đầu tại 0ms
        TestableFixedWindowCounter limiter = new TestableFixedWindowCounter(2, 1000, 0);

        // Cửa sổ 1: windowId = 0/1000 = 0
        assertTrue(limiter.allowRequest("user1")); // counter = 1
        assertTrue(limiter.allowRequest("user1")); // counter = 2

        // Thời điểm 999ms: vẫn ở cửa sổ 1 (windowId = 999/1000 = 0)
        limiter.setCurrentTime(999);
        assertFalse(limiter.allowRequest("user1"), "999ms van o cua so 1, da day");

        // Thời điểm 1000ms: cửa sổ mới! (windowId = 1000/1000 = 1)
        limiter.setCurrentTime(1000);
        assertTrue(limiter.allowRequest("user1"), "1000ms la cua so moi, counter reset");
    }
}
