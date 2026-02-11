# Algorithm #4: Token Bucket (Xô chứa token)

## 1. Tổng quan

**Token Bucket** là thuật toán rate limiting phổ biến nhất trong production, được sử dụng bởi AWS API Gateway, Stripe, GitHub API, Google Cloud.

**Ý tưởng cốt lõi:** Hình dung một cái xô chứa token. Token được nạp vào xô với tốc độ cố định. Mỗi request lấy 1 token. Hết token → từ chối. Xô đầy → token mới bị bỏ đi.

**Đặc tính quan trọng nhất:** Cho phép **burst có kiểm soát** — client có thể gửi nhiều request ngay lập tức (tối đa = sức chứa xô), sau đó phải đợi token được nạp lại.

## 2. Cách thuật toán hoạt động (từng bước)

### Bước 1: Khởi tạo xô đầy token

```
bucketCapacity = 5, refillRate = 1 token/giây

Xô ban đầu (đầy): [●●●●●] = 5 token
                    ↑ burst tối đa = 5 request
```

### Bước 2: Mỗi request tiêu thụ 1 token

```
Request 1: [●●●●○] 4 token còn → ✅ CHO PHÉP
Request 2: [●●●○○] 3 token còn → ✅ CHO PHÉP
Request 3: [●●○○○] 2 token còn → ✅ CHO PHÉP
Request 4: [●○○○○] 1 token còn → ✅ CHO PHÉP
Request 5: [○○○○○] 0 token còn → ✅ CHO PHÉP (lấy token cuối)
Request 6: [○○○○○] 0 token      → ❌ TỪ CHỐI (hết token!)
```

### Bước 3: Token được nạp lại theo thời gian (Lazy Refill)

```
Sau 1 giây (refillRate = 1/s):
  [●○○○○] 1 token mới → 1 request OK

Sau thêm 2 giây:
  [●●●○○] 3 token → 3 request OK

Sau thêm 5 giây (đợi đủ lâu):
  [●●●●●] đầy lại! → burst 5 request lại!

Nạp thêm vẫn đầy:
  [●●●●●] vẫn 5 (không tràn, token thừa bị bỏ)
```

### Lazy Refill - Kỹ thuật nạp lười

```
Thay vì chạy timer background (tốn tài nguyên):
  Timer mỗi giây: +1 token, +1 token, +1 token, ...

Ta dùng "lazy refill" (tính khi cần):
  Khi request đến:
    elapsedTime = now - lastRefillTime
    tokensToAdd = elapsedTime × refillRate
    tokens = min(tokens + tokensToAdd, capacity)

Ví dụ: refillRate = 2/s, đã trôi 3.5 giây
  tokensToAdd = 3.5 × 2 = 7 token
  → Kết quả giống timer thật, nhưng KHÔNG cần background thread!
```

### Minh họa trực quan

```
capacity = 5, refillRate = 2 token/giây

T=0s:   [●●●●●] 5 token
        5 request đến → lấy hết
T=0s:   [○○○○○] 0 token, request thứ 6 → ❌

T=1s:   [●●○○○] +2 token (refill)
        2 request đến → lấy 2
T=1s:   [○○○○○] 0 token

T=2.5s: [●●●○○] +3 token (1.5s × 2/s = 3)
        1 request đến → lấy 1
T=2.5s: [●●○○○] 2 token còn

T=5s:   [●●●●●] đầy (2 + 2.5s × 2/s = 7, cap ở 5)
```

## 3. Cấu trúc dữ liệu

```
ConcurrentHashMap<String, Bucket>
│
├── "192.168.1.1" → Bucket { tokens=3.5, lastRefillTimestamp=1700000 }
├── "192.168.1.2" → Bucket { tokens=0.0, lastRefillTimestamp=1700500 }
└── "10.0.0.1"    → Bucket { tokens=5.0, lastRefillTimestamp=1699000 }

Mỗi key có Bucket riêng chứa:
- tokens (double): số token hiện có (hỗ trợ fractional)
- lastRefillTimestamp: thời điểm refill cuối (cho lazy refill)
```

**Tại sao tokens là double mà không phải long?**
- refillRate có thể là số thập phân (0.5 token/giây)
- Lazy refill tính tokensToAdd có thể là phân số (300ms × 2/s = 0.6 token)
- Nếu dùng long → mất phần lẻ → tích lũy sai số

**Độ phức tạp bộ nhớ:** `O(n)` với `n` là số lượng key. Mỗi key chỉ cần 1 double + 1 long = 16 bytes.

## 4. Phân tích độ phức tạp

| Thao tác | Thời gian | Giải thích |
|----------|-----------|------------|
| `allowRequest()` | `O(1)` | Tính refill + so sánh + trừ token |
| Bộ nhớ per key | `O(1)` | 1 double (tokens) + 1 long (timestamp) = 16 bytes |
| Tổng bộ nhớ | `O(n)` | n = số lượng client khác nhau |

## 5. Ưu điểm

| Ưu điểm | Giải thích |
|----------|------------|
| **Burst có kiểm soát** | Cho phép burst tối đa = capacity, sau đó throttle dần |
| **Đảm bảo tốc độ trung bình** | Dài hạn, throughput trung bình = refillRate |
| **Bộ nhớ O(1) per key** | Chỉ cần 16 bytes |
| **Hiệu năng O(1)** | Không cần dọn dẹp, không cần scan |
| **Phổ biến trong production** | AWS, Stripe, GitHub, Google Cloud đều dùng |

### Burst có kiểm soát - ƯU ĐIỂM CHÍNH

```
Tại sao burst quan trọng trong API thực tế?

Kịch bản: Mobile app khởi động, cần gọi 5 API cùng lúc:
  - GET /user/profile
  - GET /user/settings
  - GET /notifications
  - GET /feed
  - GET /messages

Window-based (giới hạn 5 req/10s):
  → 5 request cùng lúc → OK, nhưng đợi 10s cho batch tiếp

Token Bucket (capacity=10, refill=2/s):
  → 5 request cùng lúc → OK (burst từ token sẵn có)
  → 1 giây sau: 2 request nữa → OK (token mới)
  → Mượt mà hơn cho UX!
```

## 6. Nhược điểm

### Burst có thể gây áp lực đột ngột - NHƯỢC ĐIỂM CHÍNH

```
Nếu capacity quá lớn:
  capacity = 1000, refillRate = 10/s

  Client đợi lâu → xô đầy 1000 token
  → Gửi 1000 request cùng lúc → server chịu áp lực rất lớn!

Giải pháp: Chọn capacity hợp lý, không quá lớn so với refillRate
  Quy tắc chung: capacity = refillRate × vài giây
  Ví dụ: refillRate = 10/s → capacity = 20-50
```

### Bảng so sánh nhược điểm

| Nhược điểm | Mức độ | Giải pháp |
|------------|--------|-----------|
| Burst gây áp lực đột ngột | Trung bình | Chọn capacity hợp lý |
| Cần tuning 2 tham số | Nhẹ | capacity + refillRate thay vì 1 tham số |
| Khó đảm bảo "đúng N req trong M giây" | Trung bình | Dùng Sliding Window nếu cần |
| Không smooth traffic hoàn toàn | Nhẹ | Dùng Leaky Bucket nếu cần smooth |

## 7. Khi nào nên dùng Token Bucket?

**Nên dùng khi:**
- API cần cho phép burst hợp lý (mobile app, SPA khởi động)
- Cần đảm bảo throughput trung bình dài hạn
- Hệ thống production cần hiệu năng cao + bộ nhớ thấp
- Muốn dùng chuẩn công nghiệp (AWS, Stripe, GitHub đều dùng)

**Không nên dùng khi:**
- Cần đảm bảo chính xác "N request trong M giây" (dùng Sliding Window)
- Cần smooth traffic hoàn toàn, không burst (dùng Leaky Bucket)
- Burst có thể làm sập hệ thống downstream

## 8. Cấu trúc file

```
src/main/java/com/dncuong/ws/rate_limit/
├── algorithm/
│   ├── RateLimiter.java                              ← Interface chung
│   ├── fixedwindow/
│   │   └── FixedWindowCounterRateLimiter.java        ← Algorithm #1
│   ├── slidingwindowlog/
│   │   └── SlidingWindowLogRateLimiter.java          ← Algorithm #2
│   ├── slidingwindowcounter/
│   │   └── SlidingWindowCounterRateLimiter.java      ← Algorithm #3
│   └── tokenbucket/
│       └── TokenBucketRateLimiter.java               ← Algorithm #4
├── controller/
│   ├── FixedWindowDemoController.java
│   ├── SlidingWindowLogDemoController.java
│   ├── SlidingWindowCounterDemoController.java
│   └── TokenBucketDemoController.java                ← REST API demo

src/test/java/com/dncuong/ws/rate_limit/
└── algorithm/
    ├── fixedwindow/
    │   └── FixedWindowCounterRateLimiterTest.java
    ├── slidingwindowlog/
    │   └── SlidingWindowLogRateLimiterTest.java
    ├── slidingwindowcounter/
    │   └── SlidingWindowCounterRateLimiterTest.java
    └── tokenbucket/
        └── TokenBucketRateLimiterTest.java           ← Unit tests (13 test cases)
```

## 9. Cách test

### 9.1. Chạy Unit Tests

```bash
./mvnw test -pl . -Dtest=TokenBucketRateLimiterTest
```

### 9.2. Test thủ công qua API

**Bước 1:** Khởi động ứng dụng:
```bash
./mvnw spring-boot:run
```

**Bước 2:** Test burst (capacity = 5, refillRate = 1/s):
```bash
# Burst: gửi 7 request liên tiếp
for i in {1..7}; do
  echo "--- Request $i ---"
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/token-bucket/test
  echo ""
done
```

**Kết quả mong đợi:**
- Request 1-5: HTTP 200 (dùng 5 token burst)
- Request 6-7: HTTP 429 (hết token)

**Bước 3:** Đợi rồi gửi lại:
```bash
# Đợi 3 giây (nạp 3 token)
sleep 3
for i in {1..5}; do
  echo "--- Request $i ---"
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/token-bucket/test
  echo ""
done
```

**Kết quả:** Request 1-3: HTTP 200, Request 4-5: HTTP 429

## 10. Thư viện sử dụng

**Không cần thêm thư viện nào!** Thuật toán này chỉ sử dụng:
- `java.util.concurrent.ConcurrentHashMap` - có sẵn trong Java
- `synchronized` keyword - có sẵn trong Java
- `Math.min()` - có sẵn trong Java

Giống các thuật toán trước, Token Bucket triển khai hoàn toàn với Java thuần.
