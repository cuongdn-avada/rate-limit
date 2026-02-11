package com.dncuong.ws.rate_limit.algorithm.tokenbucket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * =====================================================================
 * BỘ TEST CHO THUẬT TOÁN TOKEN BUCKET
 * =====================================================================
 *
 * Các test case bao phủ:
 * 1. Trường hợp cơ bản: request trong giới hạn → cho phép
 * 2. Burst: gửi hết token cùng lúc → cho phép tất cả
 * 3. Hết token → từ chối
 * 4. Lazy refill: token được nạp lại sau thời gian chờ
 * 5. Nạp từng phần (partial refill): không đủ 1 giây cũng nạp
 * 6. Xô không tràn: token không vượt quá capacity
 * 7. Nhiều key khác nhau → độc lập
 * 8. Tham số không hợp lệ → throw exception
 * 9. Thread-safe (concurrent)
 * 10. Refill rate nhỏ (fractional)
 * 11. Burst rồi chờ rồi burst lại
 *
 * @author dncuong
 */
class TokenBucketRateLimiterTest {

    // =====================================================================
    // LỚP HỖ TRỢ TEST: Cho phép kiểm soát thời gian
    // =====================================================================

    static class TestableTokenBucket extends TokenBucketRateLimiter {
        private long currentTime;

        TestableTokenBucket(long bucketCapacity, double refillRate, long startTime) {
            super(bucketCapacity, refillRate);
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
    // TEST 1: Request trong giới hạn → cho phép
    // =====================================================================

    @Test
    @DisplayName("Cho phep tat ca request khi con token")
    void allowRequest_withinLimit_shouldAllowAll() {
        // GIVEN: Xô chứa 5 token, nạp 1 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(5, 1.0, 0);

        // WHEN & THEN: 5 request đầu tiên phải đều được cho phép (dùng 5 token)
        assertTrue(limiter.allowRequest("user1"), "Request 1 - con 4 token");
        assertTrue(limiter.allowRequest("user1"), "Request 2 - con 3 token");
        assertTrue(limiter.allowRequest("user1"), "Request 3 - con 2 token");
        assertTrue(limiter.allowRequest("user1"), "Request 4 - con 1 token");
        assertTrue(limiter.allowRequest("user1"), "Request 5 - con 0 token");
    }

    // =====================================================================
    // TEST 2: Burst - gửi hết token cùng lúc
    // =====================================================================

    @Test
    @DisplayName("Burst: gui het token cung luc duoc cho phep")
    void allowRequest_burst_shouldAllowUpToCapacity() {
        // GIVEN: Xô chứa 10 token
        TestableTokenBucket limiter = new TestableTokenBucket(10, 1.0, 0);

        // WHEN: Gửi 10 request cùng lúc (burst)
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.allowRequest("user1")) {
                allowed++;
            }
        }

        // THEN: Tất cả 10 được cho phép (đây là burst tối đa = capacity)
        assertEquals(10, allowed, "Burst toi da = bucketCapacity = 10");

        // Request 11 bị từ chối (hết token)
        assertFalse(limiter.allowRequest("user1"), "Het token sau burst");
    }

    // =====================================================================
    // TEST 3: Hết token → từ chối
    // =====================================================================

    @Test
    @DisplayName("Tu choi request khi het token")
    void allowRequest_noTokens_shouldReject() {
        // GIVEN: Xô chứa 3 token
        TestableTokenBucket limiter = new TestableTokenBucket(3, 1.0, 0);

        // Dùng hết 3 token
        limiter.allowRequest("user1");
        limiter.allowRequest("user1");
        limiter.allowRequest("user1");

        // THEN: Request tiếp theo bị từ chối
        assertFalse(limiter.allowRequest("user1"), "Request 4 phai bi tu choi");
        assertFalse(limiter.allowRequest("user1"), "Request 5 cung bi tu choi");
    }

    // =====================================================================
    // TEST 4: Lazy refill - token được nạp lại sau thời gian chờ
    // =====================================================================

    @Test
    @DisplayName("Lazy refill: token duoc nap lai sau thoi gian cho")
    void allowRequest_afterWaiting_shouldRefillTokens() {
        // GIVEN: Xô chứa 5 token, nạp 2 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(5, 2.0, 0);

        // Dùng hết 5 token
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }
        assertFalse(limiter.allowRequest("user1"), "Het token");

        // Đợi 1 giây → nạp 2 token (refillRate = 2/s)
        limiter.advanceTime(1000);

        // Phải có đúng 2 token mới
        assertTrue(limiter.allowRequest("user1"), "Token 1 sau refill");
        assertTrue(limiter.allowRequest("user1"), "Token 2 sau refill");
        assertFalse(limiter.allowRequest("user1"), "Chi co 2 token moi");
    }

    // =====================================================================
    // TEST 5: Partial refill - nạp từng phần (fractional)
    // =====================================================================

    @Test
    @DisplayName("Partial refill: nap tung phan khi chua du 1 giay")
    void allowRequest_partialRefill_shouldAccumulateTokens() {
        // GIVEN: Xô chứa 5 token, nạp 2 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(5, 2.0, 0);

        // Dùng hết 5 token
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }

        // Đợi 500ms → nạp 2 × 0.5 = 1.0 token
        limiter.advanceTime(500);
        assertTrue(limiter.allowRequest("user1"), "1.0 token sau 500ms");
        assertFalse(limiter.allowRequest("user1"), "Het token (0.0 con lai)");

        // Đợi thêm 300ms → nạp 2 × 0.3 = 0.6 token (chưa đủ 1)
        limiter.advanceTime(300);
        assertFalse(limiter.allowRequest("user1"), "0.6 token < 1, chua du");

        // Đợi thêm 200ms → nạp 2 × 0.2 = 0.4 token → tổng = 0.6 + 0.4 = 1.0
        limiter.advanceTime(200);
        assertTrue(limiter.allowRequest("user1"), "Tich luy du 1.0 token");
    }

    // =====================================================================
    // TEST 6: Xô không tràn - token không vượt capacity
    // =====================================================================

    @Test
    @DisplayName("Xo khong tran: token khong vuot qua capacity")
    void allowRequest_overflowProtection_shouldCapAtCapacity() {
        // GIVEN: Xô chứa 3 token, nạp 10 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(3, 10.0, 0);

        // Đợi 10 giây → lý thuyết nạp 100 token, nhưng capacity = 3
        limiter.advanceTime(10_000);

        // Chỉ có 3 token (không phải 100+3)
        assertTrue(limiter.allowRequest("user1"), "Token 1");
        assertTrue(limiter.allowRequest("user1"), "Token 2");
        assertTrue(limiter.allowRequest("user1"), "Token 3");
        assertFalse(limiter.allowRequest("user1"), "Chi co 3 token (capacity), khong phai 103");
    }

    // =====================================================================
    // TEST 7: Nhiều key khác nhau → độc lập
    // =====================================================================

    @Test
    @DisplayName("Cac key khac nhau co bucket doc lap")
    void allowRequest_differentKeys_shouldBeIndependent() {
        TestableTokenBucket limiter = new TestableTokenBucket(2, 1.0, 0);

        // user1 dùng hết token
        assertTrue(limiter.allowRequest("user1"));
        assertTrue(limiter.allowRequest("user1"));
        assertFalse(limiter.allowRequest("user1"), "user1 het token");

        // user2 vẫn có token riêng
        assertTrue(limiter.allowRequest("user2"), "user2 doc lap voi user1");
        assertTrue(limiter.allowRequest("user2"), "user2 van con token");
        assertFalse(limiter.allowRequest("user2"), "user2 het token cua minh");
    }

    // =====================================================================
    // TEST 8: Tham số không hợp lệ → phải ném exception
    // =====================================================================

    @Test
    @DisplayName("Nem IllegalArgumentException khi bucketCapacity <= 0")
    void constructor_invalidCapacity_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new TokenBucketRateLimiter(0, 1.0),
                "capacity = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new TokenBucketRateLimiter(-5, 1.0),
                "capacity am phai throw exception");
    }

    @Test
    @DisplayName("Nem IllegalArgumentException khi refillRate <= 0")
    void constructor_invalidRefillRate_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
                        new TokenBucketRateLimiter(10, 0),
                "refillRate = 0 phai throw exception");

        assertThrows(IllegalArgumentException.class, () ->
                        new TokenBucketRateLimiter(10, -1.0),
                "refillRate am phai throw exception");
    }

    // =====================================================================
    // TEST 9: Thread-safe (concurrent)
    // =====================================================================

    @Test
    @DisplayName("Thread-safe: chi cho phep dung so request khi nhieu thread dong thoi")
    void allowRequest_concurrent_shouldBeThreadSafe() throws InterruptedException {
        // GIVEN: Xô chứa 100 token (burst = 100)
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100, 1.0);

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
                "Chi dung 100 request duoc cho phep (= bucket capacity)");
        assertEquals(100, rejectedCount.get(),
                "Dung 100 request bi tu choi");
    }

    // =====================================================================
    // TEST 10: Refill rate nhỏ (fractional)
    // =====================================================================

    @Test
    @DisplayName("Refill rate nho: 0.5 token/giay = 1 token moi 2 giay")
    void allowRequest_fractionalRefillRate_shouldWorkCorrectly() {
        // GIVEN: Xô chứa 3 token, nạp 0.5 token/giây (= 1 token mỗi 2 giây)
        TestableTokenBucket limiter = new TestableTokenBucket(3, 0.5, 0);

        // Dùng hết 3 token
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allowRequest("user1"));
        }
        assertFalse(limiter.allowRequest("user1"), "Het token");

        // Đợi 1 giây → 0.5 token (chưa đủ 1)
        limiter.advanceTime(1000);
        assertFalse(limiter.allowRequest("user1"), "0.5 token < 1, chua du");

        // Đợi thêm 1 giây → tổng 0.5 + 0.5 = 1.0 token
        limiter.advanceTime(1000);
        assertTrue(limiter.allowRequest("user1"), "1.0 token sau 2 giay");
        assertFalse(limiter.allowRequest("user1"), "Het token lai");
    }

    // =====================================================================
    // TEST 11: Burst → chờ → burst lại
    // =====================================================================

    @Test
    @DisplayName("Burst roi cho roi burst lai")
    void allowRequest_burstWaitBurst_shouldRefillFully() {
        // GIVEN: Xô chứa 5 token, nạp 2 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(5, 2.0, 0);

        // Burst 1: dùng hết 5 token
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"), "Burst 1, request " + (i + 1));
        }
        assertFalse(limiter.allowRequest("user1"), "Het token sau burst 1");

        // Đợi 3 giây → nạp 2 × 3 = 6 token, nhưng cap ở 5 (capacity)
        limiter.advanceTime(3000);

        // Burst 2: lại có 5 token
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allowRequest("user1"), "Burst 2, request " + (i + 1));
        }
        assertFalse(limiter.allowRequest("user1"), "Het token sau burst 2");
    }

    // =====================================================================
    // TEST 12: Nhiều giai đoạn liên tục
    // =====================================================================

    @Test
    @DisplayName("Nhieu giai doan: burst, cho, tieu thu dan, cho, burst lai")
    void allowRequest_multiplePhases_shouldWorkCorrectly() {
        // GIVEN: Xô chứa 4 token, nạp 1 token/giây
        TestableTokenBucket limiter = new TestableTokenBucket(4, 1.0, 0);

        // Phase 1: Dùng 2 token (còn 2)
        assertTrue(limiter.allowRequest("user1"), "Phase 1: dung token 1");
        assertTrue(limiter.allowRequest("user1"), "Phase 1: dung token 2");

        // Phase 2: Đợi 1 giây → +1 token → có 3 token
        limiter.advanceTime(1000);
        assertTrue(limiter.allowRequest("user1"), "Phase 2: token 1 (con 2)");
        assertTrue(limiter.allowRequest("user1"), "Phase 2: token 2 (con 1)");
        assertTrue(limiter.allowRequest("user1"), "Phase 2: token 3 (con 0)");
        assertFalse(limiter.allowRequest("user1"), "Phase 2: het token");

        // Phase 3: Đợi 10 giây → nạp 10 token, cap ở 4 → có 4 token
        limiter.advanceTime(10_000);
        int allowed = 0;
        for (int i = 0; i < 6; i++) {
            if (limiter.allowRequest("user1")) {
                allowed++;
            }
        }
        assertEquals(4, allowed, "Phase 3: chi 4 token (capacity)");
    }
}
