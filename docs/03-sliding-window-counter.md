# Algorithm #3: Sliding Window Counter (Bộ đếm cửa sổ trượt)

## 1. Tổng quan

**Sliding Window Counter** là thuật toán rate limiting **KẾT HỢP** (hybrid) giữa Fixed Window Counter và Sliding Window Log, lấy ưu điểm của cả hai:
- Từ Fixed Window: bộ nhớ O(1) per key, hiệu năng O(1) per request
- Từ Sliding Window: giảm thiểu boundary problem bằng cửa sổ "trượt"

**Ý tưởng cốt lõi:** Dùng **trung bình có trọng số** (weighted average) của counter cửa sổ trước và cửa sổ hiện tại để **ước lượng** số request trong cửa sổ trượt.

## 2. Cách thuật toán hoạt động (từng bước)

### Bước 1: Chia thời gian thành cửa sổ cố định (giống Fixed Window)

```
Thời gian: ──────────────────────────────────────────────────────────→
            |    Window 1    |    Window 2    |    Window 3    |
            |  [0s - 60s)   |  [60s - 120s)  | [120s - 180s)  |
            |  counter = 8   |  counter = 3   |  counter = ?   |
            |  (previous)    |  (current)     |                |
```

### Bước 2: Tính vị trí trong cửa sổ hiện tại và trọng số

```
Tại thời điểm T = 90s (đang ở 50% cửa sổ 2):

positionInWindow = (90 - 60) / 60 = 0.5   (đã đi được 50% cửa sổ)
overlapRatio     = 1 - 0.5 = 0.5          (50% cửa sổ trước chồng lấp)

   Cửa sổ trước          Cửa sổ hiện tại
   ─────────────────────|──────────────────────
   prev=8               | curr=3
                        |  ←50%→ ←50%→
                        |  overlap  elapsed
```

### Bước 3: Tính số request ước lượng bằng weighted average

```
estimatedCount = (previousCounter × overlapRatio) + currentCounter

Ví dụ tại T = 90s:
estimatedCount = (8 × 0.5) + 3 = 4.0 + 3 = 7.0

Nếu maxRequests = 10: 7.0 < 10 → CHO PHÉP ✅
```

### Minh họa trực quan: trọng số thay đổi theo vị trí

```
Giới hạn: 10 req / 60s, previousCounter = 8

Đầu cửa sổ (T = 60s, position = 0%):
  overlap = 1.0 → estimated = 8 × 1.0 + 0 = 8.0 → còn 2 slot
  [████████░░] 8.0/10 — counter cũ ảnh hưởng TỐI ĐA

25% cửa sổ (T = 75s, position = 25%):
  overlap = 0.75 → estimated = 8 × 0.75 + 0 = 6.0 → còn 4 slot
  [██████░░░░] 6.0/10 — counter cũ giảm dần

50% cửa sổ (T = 90s, position = 50%):
  overlap = 0.5 → estimated = 8 × 0.5 + 0 = 4.0 → còn 6 slot
  [████░░░░░░] 4.0/10 — counter cũ giảm một nửa

75% cửa sổ (T = 105s, position = 75%):
  overlap = 0.25 → estimated = 8 × 0.25 + 0 = 2.0 → còn 8 slot
  [██░░░░░░░░] 2.0/10 — counter cũ gần hết ảnh hưởng

Cuối cửa sổ (T = 119s, position ≈ 100%):
  overlap ≈ 0.0 → estimated ≈ 0.0 + 0 = 0.0 → còn 10 slot
  [░░░░░░░░░░] 0.0/10 — counter cũ không còn ảnh hưởng
```

## 3. Cấu trúc dữ liệu

```
ConcurrentHashMap<String, WindowState>
│
├── "192.168.1.1" → WindowState { windowId=100, currentCounter=3, previousCounter=8 }
├── "192.168.1.2" → WindowState { windowId=100, currentCounter=7, previousCounter=5 }
└── "10.0.0.1"    → WindowState { windowId=101, currentCounter=1, previousCounter=0 }

Mỗi key chỉ cần lưu 3 giá trị: windowId + currentCounter + previousCounter
→ O(1) bộ nhớ per key! (24 bytes)
```

**So sánh bộ nhớ per key:**

| Thuật toán | Dữ liệu lưu per key | Bytes |
|------------|---------------------|-------|
| Fixed Window | windowId + counter | 16 bytes |
| **Sliding Window Counter** | **windowId + current + previous** | **24 bytes** |
| Sliding Window Log | Deque chứa maxRequests timestamps | 8 × maxRequests bytes |

**Độ phức tạp bộ nhớ:** `O(n)` với `n` là số lượng key (client). Mỗi key chỉ cần 3 giá trị long = 24 bytes, không phụ thuộc vào maxRequests.

## 4. Phân tích độ phức tạp

| Thao tác | Thời gian | Giải thích |
|----------|-----------|------------|
| `allowRequest()` | `O(1)` | Chỉ cần: tính weighted average + so sánh + tăng counter |
| Bộ nhớ per key | `O(1)` | 3 giá trị long: windowId + current + previous = 24 bytes |
| Tổng bộ nhớ | `O(n)` | n = số lượng client khác nhau |

## 5. Ưu điểm

| Ưu điểm | Giải thích |
|----------|------------|
| **Bộ nhớ O(1) per key** | Chỉ cần 24 bytes, không phụ thuộc maxRequests |
| **Hiệu năng O(1)** | Tính toán đơn giản: nhân + cộng + so sánh |
| **Giảm thiểu boundary problem** | Trọng số giảm dần → chuyển tiếp mượt mà |
| **Dùng rộng rãi** | Cloudflare, nhiều CDN dùng thuật toán này |

### Giảm thiểu Boundary Problem - ƯU ĐIỂM CHÍNH

```
Fixed Window - CÓ Boundary Problem:

10 req cuối cửa sổ 1 + counter RESET + 10 req đầu cửa sổ 2
→ 20 requests trong ~2 giây! GẤP ĐÔI giới hạn!

Sliding Window Counter - GIẢM THIỂU Boundary Problem:

10 req cuối cửa sổ 1, bước sang đầu cửa sổ 2:
  overlapRatio = 1.0 (vừa qua ranh giới)
  estimated = 10 × 1.0 + 0 = 10.0 → >= 10, TỪ CHỐI! ❌
→ Không thể burst vì counter cũ vẫn "nặng" ngay tại ranh giới!
```

## 6. Nhược điểm

### Chỉ là ước lượng (Approximation) - NHƯỢC ĐIỂM CHÍNH

```
Thuật toán GIẢ ĐỊNH request phân bố ĐỀU trong mỗi cửa sổ.

Ví dụ: previousCounter = 10, tất cả 10 request ở giây đầu tiên
  → Thuật toán vẫn tính: 10 × overlapRatio (như thể request trải đều)
  → Có thể hơi CHẶT hoặc hơi LỎNG so với thực tế

Tuy nhiên trong thực tế, sai lệch rất nhỏ và chấp nhận được.
```

### Bảng so sánh nhược điểm

| Nhược điểm | Mức độ | Giải pháp |
|------------|--------|-----------|
| Chỉ là ước lượng, không chính xác tuyệt đối | Nhẹ | Dùng Sliding Window Log nếu cần chính xác |
| Phức tạp hơn Fixed Window | Nhẹ | Code vẫn đơn giản, chỉ thêm weighted average |
| Không smooth traffic | Trung bình | Dùng Token/Leaky Bucket |

## 7. Khi nào nên dùng Sliding Window Counter?

**Nên dùng khi:**
- Cần cân bằng giữa chính xác và hiệu năng (best trade-off)
- Hệ thống có nhiều client (bộ nhớ O(1) per key rất quan trọng)
- Chấp nhận ước lượng xấp xỉ (không cần chính xác tuyệt đối)
- Production system cần hiệu năng cao + giảm boundary problem

**Không nên dùng khi:**
- Cần chính xác tuyệt đối (API billing cần đếm đúng từng request → dùng Sliding Window Log)
- Cần smooth traffic (dùng Leaky Bucket)
- Ứng dụng đơn giản không cần quan tâm boundary problem (dùng Fixed Window)

## 8. Cấu trúc file

```
src/main/java/com/dncuong/ws/rate_limit/
├── algorithm/
│   ├── RateLimiter.java                              ← Interface chung
│   ├── fixedwindow/
│   │   └── FixedWindowCounterRateLimiter.java        ← Algorithm #1
│   ├── slidingwindowlog/
│   │   └── SlidingWindowLogRateLimiter.java          ← Algorithm #2
│   └── slidingwindowcounter/
│       └── SlidingWindowCounterRateLimiter.java      ← Algorithm #3
├── controller/
│   ├── FixedWindowDemoController.java
│   ├── SlidingWindowLogDemoController.java
│   └── SlidingWindowCounterDemoController.java       ← REST API demo

src/test/java/com/dncuong/ws/rate_limit/
└── algorithm/
    ├── fixedwindow/
    │   └── FixedWindowCounterRateLimiterTest.java
    ├── slidingwindowlog/
    │   └── SlidingWindowLogRateLimiterTest.java
    └── slidingwindowcounter/
        └── SlidingWindowCounterRateLimiterTest.java  ← Unit tests (12 test cases)
```

## 9. Cách test

### 9.1. Chạy Unit Tests

```bash
./mvnw test -pl . -Dtest=SlidingWindowCounterRateLimiterTest
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
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/sliding-window-counter/test
  echo ""
done
```

**Kết quả mong đợi:**
- Request 1-5: HTTP 200, `"status": "SUCCESS"`
- Request 6-7: HTTP 429, `"status": "RATE_LIMITED"`
- Đợi vài giây, gửi lại: quota được giải phóng DẦN DẦN (không reset đột ngột)

**Khác biệt với các thuật toán khác:**
- Fixed Window: counter reset đột ngột khi cửa sổ mới bắt đầu
- Sliding Window Log: quota giải phóng dần khi từng timestamp hết hạn
- Sliding Window Counter: quota giải phóng dần theo trọng số giảm dần

## 10. Thư viện sử dụng

**Không cần thêm thư viện nào!** Thuật toán này chỉ sử dụng:
- `java.util.concurrent.ConcurrentHashMap` - có sẵn trong Java
- `synchronized` keyword - có sẵn trong Java

Giống hai thuật toán trước, Sliding Window Counter triển khai hoàn toàn với Java thuần.
