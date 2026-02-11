# Algorithm #2: Sliding Window Log (Nhật ký cửa sổ trượt)

## 1. Tổng quan

**Sliding Window Log** là thuật toán rate limiting giải quyết **Boundary Problem** (vấn đề ranh giới) của Fixed Window Counter bằng cách sử dụng cửa sổ **trượt** thay vì cửa sổ cố định.

**Ý tưởng cốt lõi:** Lưu timestamp chính xác của mỗi request vào một danh sách (log). Khi có request mới, nhìn lại khoảng thời gian `windowSize` vừa qua để đếm và quyết định.

## 2. Cách thuật toán hoạt động (từng bước)

### Bước 1: Hiểu sự khác biệt "cửa sổ cố định" vs "cửa sổ trượt"

```
Fixed Window (cửa sổ CỐ ĐỊNH):
  Ranh giới cố định, counter reset đột ngột
  ──────|────────────|────────────|──────────→
        0s          60s         120s
        Window 1     Window 2     Window 3

Sliding Window (cửa sổ TRƯỢT):
  Cửa sổ "trượt" theo thời gian, không có ranh giới cố định
  Tại thời điểm T, cửa sổ = [T - windowSize, T]

  Tại T=75s, cửa sổ = [15s, 75s]:
        ──────|===========|──────────────────→
             15s         75s
              ↑ cửa sổ trượt ↑

  Tại T=90s, cửa sổ = [30s, 90s]:
        ──────────|===========|──────────────→
                 30s         90s
                  ↑ cửa sổ trượt ↑
```

### Bước 2: Lưu timestamp mỗi request vào log

```
Mỗi key có một log (danh sách) chứa timestamp:

"192.168.1.1" → [1000, 1200, 1400, 1700, 1900]
                  ↑     ↑     ↑     ↑     ↑
                req1  req2  req3  req4  req5
```

### Bước 3: Khi request mới đến, dọn dẹp + đếm + quyết định

```
Request mới tại thời điểm 2300ms, windowSize = 1000ms:

1. Tính windowStart = 2300 - 1000 = 1300
2. Dọn dẹp: xóa tất cả timestamp <= 1300
   Log trước: [1000, 1200, 1400, 1700, 1900]
   Log sau:   [1400, 1700, 1900]  (đã xóa 1000, 1200)
3. Đếm: count = 3
4. Nếu maxRequests = 5: 3 < 5 → CHO PHÉP ✅, thêm 2300 vào log
   Log cuối: [1400, 1700, 1900, 2300]
```

### Minh họa trực quan

```
Giới hạn: 3 requests / 1 giây (1000ms)

Thời điểm 1000ms: Request → log = [1000]             → count=1 ✅
Thời điểm 1200ms: Request → log = [1000, 1200]        → count=2 ✅
Thời điểm 1400ms: Request → log = [1000, 1200, 1400]  → count=3 ✅
Thời điểm 1500ms: Request → count=3, 3 >= 3           → ❌ TỪ CHỐI
Thời điểm 1800ms: Request → count=3, 3 >= 3           → ❌ TỪ CHỐI
Thời điểm 2001ms: Dọn dẹp → xóa 1000 (hết hạn)
                   log = [1200, 1400], count=2
                   2 < 3 → ✅ CHO PHÉP, log = [1200, 1400, 2001]
```

## 3. Cấu trúc dữ liệu

```
ConcurrentHashMap<String, RequestLog>
│
├── "192.168.1.1" → RequestLog { timestamps: Deque[1000, 1200, 1400] }
├── "192.168.1.2" → RequestLog { timestamps: Deque[1100, 1500, 1800, 1900] }
└── "10.0.0.1"    → RequestLog { timestamps: Deque[2000] }

Mỗi key có RequestLog riêng chứa Deque<Long> các timestamp.
Deque (ArrayDeque): thêm cuối O(1), xóa đầu O(1) - hoàn hảo cho pattern FIFO.
```

**Tại sao dùng ArrayDeque mà không dùng LinkedList hay ArrayList?**

| Cấu trúc | addLast | pollFirst | Bộ nhớ |
|-----------|---------|-----------|--------|
| **ArrayDeque** | O(1) | O(1) | Thấp (array liên tục) |
| LinkedList | O(1) | O(1) | Cao (2 con trỏ/node) |
| ArrayList | O(1) | O(n) | Thấp nhưng shift chậm |

**Độ phức tạp bộ nhớ:** `O(n × maxRequests)` với `n` là số lượng key (client). Mỗi key lưu tối đa `maxRequests` timestamp (8 bytes mỗi cái).

## 4. Phân tích độ phức tạp

| Thao tác | Thời gian | Giải thích |
|----------|-----------|------------|
| `allowRequest()` | `O(k)` amortized | k = số entry hết hạn cần dọn dẹp |
| Bộ nhớ per key | `O(maxRequests)` | Tối đa lưu maxRequests timestamp × 8 bytes |
| Tổng bộ nhớ | `O(n × maxRequests)` | n = số key, mỗi key tối đa maxRequests entry |

## 5. Ưu điểm

| Ưu điểm | Giải thích |
|----------|------------|
| **Chính xác tuyệt đối** | Không có boundary problem |
| **Đảm bảo giới hạn** | Trong BẤT KỲ khoảng windowSize nào, luôn <= maxRequests |
| **Logic đơn giản** | Dễ hiểu: lưu timestamp, dọn dẹp, đếm |
| **Request từ chối không chiếm quota** | Chỉ lưu request thành công vào log |

### Giải quyết Boundary Problem - ƯU ĐIỂM CHÍNH

```
Fixed Window - CÓ Boundary Problem:

Giới hạn: 10 req / 1 phút

            Cửa sổ 1                    Cửa sổ 2
   ─────────────────────────────|──────────────────────────────
                    10 requests ↗ ↖ 10 requests
                     (giây 59)    (giây 60)

Counter cửa sổ 1 = 10 → OK
Counter cửa sổ 2 reset = 0, +10 = 10 → OK
→ 20 requests trong ~2 giây! GẤP ĐÔI giới hạn!
```

```
Sliding Window Log - KHÔNG CÓ Boundary Problem:

Giới hạn: 10 req / 1 phút

Tại giây 59: gửi 10 request → log chứa 10 timestamp (tại giây 59)
Tại giây 60: request mới đến
  → Cửa sổ trượt: [0, 60] → 10 request tại giây 59 VẪN CÒN trong cửa sổ
  → count = 10, 10 >= 10 → TỪ CHỐI ❌

Phải đợi đến giây 120 (59 + 61) để request cũ hết hạn!
→ Luôn đảm bảo <= 10 request trong BẤT KỲ 60 giây nào.
```

## 6. Nhược điểm

### Tốn bộ nhớ hơn Fixed Window - NHƯỢC ĐIỂM CHÍNH

```
Ví dụ: 1 triệu user, mỗi user giới hạn 1000 req/phút

Sliding Window Log: 1,000,000 × 1000 × 8 bytes = ~8 GB
Fixed Window:       1,000,000 × 16 bytes        = ~16 MB

→ Gấp 500 lần bộ nhớ!
```

**Giải thích:** Fixed Window chỉ cần lưu 2 giá trị (windowId + counter) cho mỗi key, trong khi Sliding Window Log phải lưu timestamp của từng request (tối đa maxRequests timestamp/key).

### Bảng so sánh nhược điểm

| Nhược điểm | Mức độ | Giải pháp |
|------------|--------|-----------|
| Tốn bộ nhớ (O(maxRequests)/key) | Trung bình | Dùng Sliding Window Counter (hybrid) |
| Chi phí dọn dẹp O(k) | Nhẹ | Amortized O(1), chấp nhận được |
| Không smooth traffic | Trung bình | Dùng Token/Leaky Bucket |

## 7. Khi nào nên dùng Sliding Window Log?

**Nên dùng khi:**
- Cần chính xác tuyệt đối (API billing, payment, tài chính)
- maxRequests nhỏ (ít timestamp cần lưu)
- Số lượng key (client) không quá lớn
- Boundary problem không thể chấp nhận được

**Không nên dùng khi:**
- maxRequests rất lớn (hàng ngàn) → tốn bộ nhớ
- Hàng triệu client cùng lúc → bộ nhớ bùng nổ
- Cần smooth traffic (dùng Leaky Bucket thay thế)
- Cần giải pháp phân tán (cần thêm Redis)

## 8. Cấu trúc file

```
src/main/java/com/dncuong/ws/rate_limit/
├── algorithm/
│   ├── RateLimiter.java                              ← Interface chung
│   ├── fixedwindow/
│   │   └── FixedWindowCounterRateLimiter.java        ← Algorithm #1
│   └── slidingwindowlog/
│       └── SlidingWindowLogRateLimiter.java          ← Algorithm #2
├── controller/
│   ├── FixedWindowDemoController.java
│   └── SlidingWindowLogDemoController.java           ← REST API demo

src/test/java/com/dncuong/ws/rate_limit/
└── algorithm/
    ├── fixedwindow/
    │   └── FixedWindowCounterRateLimiterTest.java
    └── slidingwindowlog/
        └── SlidingWindowLogRateLimiterTest.java      ← Unit tests (12 test cases)
```

## 9. Cách test

### 9.1. Chạy Unit Tests

```bash
./mvnw test -pl . -Dtest=SlidingWindowLogRateLimiterTest
```

### 9.2. Test thủ công qua API

**Bước 1:** Khởi động ứng dụng:
```bash
./mvnw spring-boot:run
```

**Bước 2:** Gửi request liên tục (giới hạn 5 req / 10 giây):
```bash
# Gửi 7 request liên tiếp
for i in {1..7}; do
  echo "--- Request $i ---"
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/sliding-window-log/test
  echo ""
done
```

**Kết quả mong đợi:**
- Request 1-5: HTTP 200, `"status": "SUCCESS"`
- Request 6-7: HTTP 429, `"status": "RATE_LIMITED"`
- Đợi 10 giây, gửi lại: HTTP 200 (request cũ hết hạn, quota được giải phóng DẦN DẦN)

**Khác biệt với Fixed Window:**
- Fixed Window: sau 10 giây, TẤT CẢ quota reset cùng lúc
- Sliding Window Log: quota được giải phóng DẦN DẦN khi từng request cũ hết hạn

## 10. Thư viện sử dụng

**Không cần thêm thư viện nào!** Thuật toán này chỉ sử dụng:
- `java.util.ArrayDeque` - có sẵn trong Java
- `java.util.concurrent.ConcurrentHashMap` - có sẵn trong Java
- `synchronized` keyword - có sẵn trong Java

Giống như Fixed Window Counter, Sliding Window Log triển khai hoàn toàn với Java thuần.
