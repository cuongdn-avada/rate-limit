# Algorithm #1: Fixed Window Counter (Bộ đếm cửa sổ cố định)

## 1. Tổng quan

**Fixed Window Counter** là thuật toán rate limiting đơn giản nhất và cũng là nền tảng để hiểu các thuật toán phức tạp hơn.

**Ý tưởng cốt lõi:** Chia trục thời gian thành các khoảng (window) có độ dài bằng nhau, đếm số request trong mỗi khoảng, và từ chối khi vượt ngưỡng.

## 2. Cách thuật toán hoạt động (từng bước)

### Bước 1: Chia trục thời gian thành các cửa sổ cố định

```
Thời gian: ──────────────────────────────────────────────────────────→
            |    Window 1    |    Window 2    |    Window 3    |
            |  [0s - 60s)   |  [60s - 120s)  | [120s - 180s)  |
            |   counter = 0  |   counter = 0  |   counter = 0  |
```

Mỗi cửa sổ bắt đầu với counter = 0. Kích thước cửa sổ là cố định (ví dụ: 1 phút, 1 giờ).

### Bước 2: Xác định request thuộc cửa sổ nào

```
windowId = currentTimeMillis / windowSizeInMillis
```

Ví dụ với `windowSize = 60000ms` (1 phút):
- Request lúc `30000ms` → `windowId = 30000 / 60000 = 0`
- Request lúc `90000ms` → `windowId = 90000 / 60000 = 1`

Tất cả request trong cùng khoảng thời gian sẽ có cùng `windowId`.

### Bước 3: Đếm và quyết định

```
Nếu windowId thay đổi → reset counter = 0 (cửa sổ mới)
counter++
Nếu counter <= maxRequests → CHO PHÉP ✅
Nếu counter > maxRequests  → TỪ CHỐI ❌ (HTTP 429)
```

### Minh họa trực quan

```
Giới hạn: 5 requests / 10 giây

Cửa sổ 1 [0s - 10s):
  Request @1s  → counter=1 ✅
  Request @3s  → counter=2 ✅
  Request @5s  → counter=3 ✅
  Request @7s  → counter=4 ✅
  Request @9s  → counter=5 ✅
  Request @9.5s → counter=6 ❌ (vượt giới hạn!)

Cửa sổ 2 [10s - 20s):
  counter reset = 0
  Request @10s → counter=1 ✅ (cửa sổ mới, bắt đầu lại)
  ...
```

## 3. Cấu trúc dữ liệu

```
ConcurrentHashMap<String, WindowState>
│
├── "192.168.1.1" → WindowState { windowId=1738000, counter=3 }
├── "192.168.1.2" → WindowState { windowId=1738000, counter=7 }
└── "10.0.0.1"    → WindowState { windowId=1738001, counter=1 }

Mỗi key (IP/userId) có WindowState riêng → rate limit ĐỘC LẬP cho mỗi client.
```

**Độ phức tạp bộ nhớ:** `O(n)` với `n` là số lượng key (client) khác nhau. Mỗi key chỉ cần lưu 2 giá trị (`windowId` + `counter`).

## 4. Phân tích độ phức tạp

| Thao tác | Thời gian | Giải thích |
|----------|-----------|------------|
| `allowRequest()` | `O(1)` | Chỉ cần: lookup HashMap + so sánh + tăng counter |
| Bộ nhớ per key | `O(1)` | 2 giá trị long: windowId + counter = 16 bytes |
| Tổng bộ nhớ | `O(n)` | n = số lượng client khác nhau |

## 5. Ưu điểm

| Ưu điểm | Giải thích |
|----------|------------|
| **Đơn giản** | Dễ hiểu, dễ implement, ít bug |
| **Hiệu năng cao** | O(1) cho mỗi request |
| **Ít bộ nhớ** | Chỉ cần counter + windowId cho mỗi key |
| **Dễ phân tán** | Dễ implement trên Redis (INCR + EXPIRE) |

## 6. Nhược điểm

### Boundary Problem (Vấn đề ranh giới) - NHƯỢC ĐIỂM CHÍNH

```
Giới hạn: 10 requests / 1 phút

            Cửa sổ 1                    Cửa sổ 2
   ─────────────────────────────|──────────────────────────────
                    10 requests ↗ ↖ 10 requests
                     (giây 59)    (giây 0)

   → 20 requests trong ~2 giây! GẤP ĐÔI giới hạn!
```

**Giải thích:** Ở ranh giới giữa 2 cửa sổ, một burst (đợt) request có thể đạt gấp đôi giới hạn:
- 10 request cuối cửa sổ 1 (giây 59) → OK (counter cửa sổ 1 = 10)
- 10 request đầu cửa sổ 2 (giây 0) → OK (counter cửa sổ 2 mới reset = 10)
- Kết quả: 20 request trong 2 giây!

### Bảng so sánh nhược điểm

| Nhược điểm | Mức độ | Giải pháp |
|------------|--------|-----------|
| Boundary Problem | Nghiêm trọng | Dùng Sliding Window |
| Không smooth traffic | Trung bình | Dùng Token/Leaky Bucket |
| Bộ nhớ tích lũy | Nhẹ | Cần cơ chế cleanup key cũ |

## 7. Khi nào nên dùng Fixed Window Counter?

**Nên dùng khi:**
- Ứng dụng nhỏ, yêu cầu đơn giản
- Không cần độ chính xác tuyệt đối tại ranh giới cửa sổ
- Cần implement nhanh, prototype
- Backend đơn giản, không cần distributed rate limiting

**Không nên dùng khi:**
- Cần kiểm soát chặt chẽ lưu lượng (API billing, payment gateway)
- Traffic burst tại ranh giới có thể gây sập hệ thống
- Cần đảm bảo đúng X requests trong BẤT KỲ khoảng thời gian Y nào

## 8. Cấu trúc file

```
src/main/java/com/dncuong/ws/rate_limit/
├── algorithm/
│   ├── RateLimiter.java                              ← Interface chung
│   └── fixedwindow/
│       └── FixedWindowCounterRateLimiter.java        ← Thuật toán chính
├── controller/
│   └── FixedWindowDemoController.java                ← REST API demo
│
src/test/java/com/dncuong/ws/rate_limit/
└── algorithm/
    └── fixedwindow/
        └── FixedWindowCounterRateLimiterTest.java    ← Unit tests (9 test cases)
```

## 9. Cách test

### 9.1. Chạy Unit Tests

```bash
./mvnw test -pl . -Dtest=FixedWindowCounterRateLimiterTest
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
  curl -s -w "\nHTTP Status: %{http_code}\n" http://localhost:8080/api/fixed-window/test
  echo ""
done
```

**Kết quả mong đợi:**
- Request 1-5: HTTP 200, `"status": "SUCCESS"`
- Request 6-7: HTTP 429, `"status": "RATE_LIMITED"`
- Đợi 10 giây, gửi lại: HTTP 200 (cửa sổ mới, counter reset)

## 10. Thư viện sử dụng

**Không cần thêm thư viện nào!** Thuật toán này chỉ sử dụng:
- `java.util.concurrent.ConcurrentHashMap` - có sẵn trong Java
- `synchronized` keyword - có sẵn trong Java

Đây là một trong những ưu điểm lớn nhất của Fixed Window Counter: triển khai hoàn toàn với Java thuần.
