# Kiến thức nền tảng: Java Concurrency

> Tài liệu này giải thích 3 khái niệm quan trọng được sử dụng trong các thuật toán
> rate limiting: `synchronized`, `ConcurrentHashMap`, và `AtomicLong`.

---

## Mục lục

1. [Vấn đề gốc: Tại sao cần quan tâm đến concurrency?](#1-vấn-đề-gốc)
2. [synchronized — Ổ khóa để bảo vệ dữ liệu](#2-synchronized)
3. [AtomicLong — Biến số nguyên tử](#3-atomiclong)
4. [ConcurrentHashMap — HashMap an toàn cho đa luồng](#4-concurrenthashmap)
5. [So sánh và khi nào dùng cái nào](#5-so-sánh)
6. [Tại sao Fixed Window Counter dùng synchronized mà không dùng AtomicLong?](#6-áp-dụng-vào-rate-limiter)

---

## 1. Vấn đề gốc

### Tại sao cần quan tâm đến concurrency?

Trong web server, **nhiều request đến cùng lúc** = **nhiều thread chạy đồng thời**.
Khi nhiều thread cùng đọc/ghi một biến, kết quả có thể **SAI** nếu không bảo vệ.

### Ví dụ: Race Condition (Lỗi tranh chấp)

Giả sử có biến `counter = 0`, 2 thread cùng muốn tăng lên 1:

```
Mong đợi: counter = 0 → Thread A tăng → counter = 1 → Thread B tăng → counter = 2

Thực tế có thể xảy ra (Race Condition):
  Thời điểm 1: Thread A đọc counter = 0
  Thời điểm 2: Thread B đọc counter = 0     ← cũng đọc được 0!
  Thời điểm 3: Thread A ghi counter = 0 + 1 = 1
  Thời điểm 4: Thread B ghi counter = 0 + 1 = 1  ← GHI ĐÈ!

Kết quả: counter = 1 (SAI! phải là 2)
```

**Vấn đề:** Phép `counter++` trông như 1 bước, nhưng thực ra là 3 bước:
1. **ĐỌC** giá trị hiện tại từ bộ nhớ
2. **TÍNH** giá trị mới (cộng 1)
3. **GHI** giá trị mới vào bộ nhớ

Giữa 3 bước này, thread khác có thể "chen ngang" → kết quả sai.

### Minh họa trực quan

```
          BỘ NHỚ CHUNG
          ┌──────────┐
          │counter = 0│
          └────┬─────┘
               │
     ┌─────────┴─────────┐
     │                    │
  Thread A             Thread B
  Đọc: 0              Đọc: 0       ← Cả 2 đọc cùng giá trị!
  Tính: 0+1=1         Tính: 0+1=1
  Ghi: 1               Ghi: 1      ← Mất 1 lần tăng!
     │                    │
     └─────────┬─────────┘
               │
          ┌────┴─────┐
          │counter = 1│  ← SAI! Phải là 2
          └──────────┘
```

Ba công cụ dưới đây giải quyết vấn đề này theo các cách khác nhau.

---

## 2. synchronized

### synchronized là gì?

`synchronized` giống như một **ổ khóa (lock)**. Khi một thread "khóa" một đoạn code,
các thread khác phải **ĐỢI** cho đến khi thread đó "mở khóa" xong.

### Hình dung đơn giản

```
Giống như phòng vệ sinh công cộng chỉ có 1 phòng:
  - Người A vào → KHÓA cửa
  - Người B đến → thấy cửa khóa → ĐỨNG ĐỢI bên ngoài
  - Người A xong → MỞ khóa cửa
  - Người B vào → KHÓA cửa
  → Đảm bảo chỉ 1 người dùng tại mỗi thời điểm
```

### Cách dùng 1: synchronized method

```java
public class Counter {
    private int count = 0;

    // Cả method được khóa: chỉ 1 thread vào method này tại mỗi thời điểm
    public synchronized void increment() {
        count++;  // An toàn! Vì chỉ 1 thread chạy ở đây
    }

    public synchronized int getCount() {
        return count;
    }
}
```

**Hoạt động:**
```
Thread A gọi increment() → khóa object → count = 0 → count++ → count = 1 → mở khóa
Thread B gọi increment() → ĐỢI...      → khóa object → count = 1 → count++ → count = 2 → mở khóa
→ Kết quả: count = 2 ✅ ĐÚNG!
```

### Cách dùng 2: synchronized block (khóa một đoạn code cụ thể)

```java
public class Counter {
    private int count = 0;
    private final Object lock = new Object();  // Object dùng làm "ổ khóa"

    public void increment() {
        // Code ở đây KHÔNG bị khóa, nhiều thread chạy tự do

        synchronized (lock) {  // ← Chỉ khóa đoạn này
            count++;           // ← An toàn
        }                      // ← Tự động mở khóa khi ra khỏi block

        // Code ở đây cũng KHÔNG bị khóa
    }
}
```

**Tại sao dùng block thay vì method?**
→ Khóa ÍT hơn = hiệu năng TỐT hơn. Chỉ khóa đoạn code thật sự cần bảo vệ.

### Cách dùng 3: synchronized trên object cụ thể (DÙNG TRONG RATE LIMITER)

```java
// Trong FixedWindowCounterRateLimiter:
WindowState state = windowStateMap.get(key);

synchronized (state) {     // ← Khóa trên TỪNG state riêng biệt
    // kiểm tra window + tăng counter
}
```

**Ưu điểm lớn:** Mỗi key (IP) có state riêng, nên:
- Thread xử lý IP "192.168.1.1" khóa state của IP đó
- Thread xử lý IP "192.168.1.2" khóa state khác → KHÔNG PHẢI ĐỢI!
- Chỉ 2 thread cùng IP mới phải đợi nhau

```
  IP 192.168.1.1          IP 192.168.1.2
  ┌────────────┐          ┌────────────┐
  │  state A   │          │  state B   │
  │  (lock A)  │          │  (lock B)  │
  └────────────┘          └────────────┘
       │                        │
   Thread 1,3               Thread 2,4
   (đợi nhau)              (đợi nhau)
   (KHÔNG đợi Thread 2,4)  (KHÔNG đợi Thread 1,3)
```

### Nhược điểm của synchronized

| Nhược điểm | Giải thích |
|-------------|------------|
| **Chậm** | Thread phải đợi → giảm throughput |
| **Deadlock** | Nếu 2 thread khóa chéo nhau → đứng vĩnh viễn |
| **Coarse-grained** | Nếu khóa toàn bộ method → chỉ 1 thread chạy tại mỗi thời điểm |

---

## 3. AtomicLong

### AtomicLong là gì?

`AtomicLong` là biến kiểu `long` mà các phép toán trên nó là **nguyên tử (atomic)**:
phép đọc-tính-ghi xảy ra trong **1 bước duy nhất**, không thread nào chen ngang được.

### Hình dung đơn giản

```
synchronized giống như: "Khóa cả căn phòng để thay 1 bóng đèn"
AtomicLong giống như:   "Bóng đèn tự thay được trong 1 nháy mắt, không cần khóa phòng"
```

### So sánh: long thường vs AtomicLong

```java
// ❌ KHÔNG an toàn - biến long thường
private long counter = 0;

public void increment() {
    counter++;  // 3 bước: đọc → tính → ghi. Thread khác chen ngang được!
}
```

```java
// ✅ AN TOÀN - AtomicLong
private AtomicLong counter = new AtomicLong(0);

public void increment() {
    counter.incrementAndGet();  // 1 bước nguyên tử! Không ai chen ngang được
}
```

### Các method quan trọng của AtomicLong

```java
AtomicLong counter = new AtomicLong(0);

// Lấy giá trị hiện tại
long value = counter.get();                    // → 0

// Tăng 1 và trả về giá trị MỚI
long newVal = counter.incrementAndGet();       // → 1

// Tăng 1 và trả về giá trị CŨ (trước khi tăng)
long oldVal = counter.getAndIncrement();       // oldVal = 1, counter = 2

// Đặt giá trị mới
counter.set(100);                              // counter = 100

// CAS (Compare-And-Swap): chỉ đặt giá trị mới NẾU giá trị hiện tại đúng như mong đợi
boolean success = counter.compareAndSet(100, 200);  // Nếu counter==100 → đặt 200, trả true
                                                     // Nếu counter!=100 → không đổi, trả false
```

### CAS (Compare-And-Swap) — Trái tim của AtomicLong

```
CAS hoạt động như sau:
  "Tôi nghĩ giá trị hiện tại là X. Nếu đúng, hãy đổi thành Y.
   Nếu sai (ai đó đã đổi rồi), thì thôi, tôi sẽ thử lại."

Ví dụ: 2 thread cùng muốn tăng counter từ 5 lên 6:

  Thread A: compareAndSet(5, 6) → counter đang là 5? ĐÚNG → đổi thành 6 ✅
  Thread B: compareAndSet(5, 6) → counter đang là 5? SAI (đã là 6) → THẤT BẠI
  Thread B: đọc lại → counter = 6 → compareAndSet(6, 7) → ĐÚNG → đổi thành 7 ✅

→ Không cần khóa! Chỉ "thử lại" nếu thất bại.
```

### Ví dụ thực tế: Đếm lượt truy cập website

```java
import java.util.concurrent.atomic.AtomicLong;

public class VisitorCounter {
    // Biến đếm nguyên tử - an toàn với đa luồng
    private final AtomicLong totalVisitors = new AtomicLong(0);

    // Mỗi lần có người truy cập → tăng counter
    // Nhiều thread gọi method này cùng lúc vẫn đúng!
    public long recordVisit() {
        return totalVisitors.incrementAndGet();
    }

    public long getTotalVisitors() {
        return totalVisitors.get();
    }
}
```

### AtomicLong vs synchronized

| Tiêu chí | AtomicLong | synchronized |
|-----------|------------|-------------|
| **Cơ chế** | CAS (không khóa) | Lock (khóa) |
| **Hiệu năng** | Nhanh hơn (không đợi) | Chậm hơn (thread phải đợi) |
| **Phạm vi** | 1 biến duy nhất | Nhiều biến, nhiều thao tác |
| **Khi nào dùng** | Tăng/giảm 1 giá trị đơn lẻ | Cần bảo vệ NHIỀU thao tác liên quan |

---

## 4. ConcurrentHashMap

### ConcurrentHashMap là gì?

`ConcurrentHashMap` là phiên bản **thread-safe** của `HashMap`.
Nhiều thread có thể đọc/ghi đồng thời mà không bị lỗi.

### Tại sao không dùng HashMap thường?

```java
// ❌ NGUY HIỂM - HashMap không thread-safe
HashMap<String, Integer> map = new HashMap<>();

// Thread A và Thread B cùng put() đồng thời:
// → ConcurrentModificationException
// → Dữ liệu bị hỏng (corrupt)
// → Vòng lặp vô hạn (infinite loop) trong trường hợp xấu nhất!
```

```java
// ✅ AN TOÀN - ConcurrentHashMap
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

// Thread A và Thread B cùng put() đồng thời:
// → Hoạt động bình thường, không lỗi
```

### Cơ chế hoạt động bên trong

```
HashMap thường:
  ┌──────────────────────────────┐
  │   1 lock cho TOÀN BỘ map    │  ← Thread B phải đợi Thread A xong
  │   [bucket0] [bucket1] ...   │     DÙ đang truy cập bucket khác!
  └──────────────────────────────┘

ConcurrentHashMap:
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ segment 0│ │ segment 1│ │ segment 2│  ← Mỗi segment có lock riêng
  │ [bucket] │ │ [bucket] │ │ [bucket] │  ← Thread A khóa segment 0
  └──────────┘ └──────────┘ └──────────┘  ← Thread B khóa segment 1 → KHÔNG ĐỢI!
```

**ConcurrentHashMap chia map thành nhiều "segment" (phân đoạn)**, mỗi segment có lock riêng:
- Thread A ghi vào segment 0 → chỉ khóa segment 0
- Thread B ghi vào segment 1 → khóa segment 1 → **chạy song song** với Thread A!
- Chỉ khi 2 thread ghi vào CÙNG segment mới phải đợi nhau

### Các method quan trọng

```java
ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();

// === put: thêm cặp key-value ===
map.put("user1", 1);

// === get: lấy value theo key ===
Integer value = map.get("user1");  // → 1

// === putIfAbsent: chỉ thêm nếu key CHƯA tồn tại (ATOMIC!) ===
map.putIfAbsent("user1", 999);  // Không đổi! Vì "user1" đã tồn tại
map.putIfAbsent("user2", 1);    // Thêm "user2" → 1

// === computeIfAbsent: tạo value nếu key chưa tồn tại (ATOMIC!) ===
// ĐÂY LÀ METHOD DÙNG TRONG RATE LIMITER
map.computeIfAbsent("user3", key -> {
    // Lambda này CHỈ chạy nếu "user3" chưa tồn tại
    System.out.println("Tạo mới cho: " + key);
    return 0;
});
```

### computeIfAbsent — Method dùng trong Rate Limiter

```java
// Trong FixedWindowCounterRateLimiter:
WindowState state = windowStateMap.computeIfAbsent(key,
    k -> new WindowState(currentWindowId));

// Ý nghĩa:
// - Nếu key "192.168.1.1" CHƯA có trong map
//   → Tạo WindowState mới, put vào map, trả về nó
// - Nếu key "192.168.1.1" ĐÃ có trong map
//   → Trả về WindowState đã tồn tại, KHÔNG tạo mới
//
// QUAN TRỌNG: Phép toán này là ATOMIC (nguyên tử)
// → 2 thread cùng gọi với cùng key → chỉ 1 WindowState được tạo
```

### Ví dụ thực tế: Đếm request theo IP

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RequestCounter {
    // Map: IP → số lần request
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public long recordRequest(String ip) {
        // computeIfAbsent: tạo AtomicLong(0) nếu IP mới
        // incrementAndGet: tăng counter lên 1
        return counters
                .computeIfAbsent(ip, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    public long getCount(String ip) {
        AtomicLong counter = counters.get(ip);
        return counter != null ? counter.get() : 0;
    }
}

// Sử dụng:
// RequestCounter rc = new RequestCounter();
// rc.recordRequest("192.168.1.1");  → 1
// rc.recordRequest("192.168.1.1");  → 2
// rc.recordRequest("10.0.0.1");     → 1  (IP khác, counter riêng)
```

### ConcurrentHashMap vs các lựa chọn khác

| Lựa chọn | Thread-safe? | Hiệu năng | Khi nào dùng |
|-----------|-------------|------------|-------------|
| `HashMap` | ❌ Không | Nhanh nhất | Chỉ 1 thread dùng |
| `Hashtable` | ✅ Có | Chậm (khóa toàn bộ) | Legacy code, không nên dùng mới |
| `Collections.synchronizedMap()` | ✅ Có | Chậm (khóa toàn bộ) | Cần wrap HashMap có sẵn |
| **`ConcurrentHashMap`** | ✅ Có | **Nhanh** (khóa segment) | **Khuyên dùng cho đa luồng** |

---

## 5. So sánh tổng hợp

### Khi nào dùng cái nào?

```
Chỉ cần tăng/giảm 1 biến số?
  → Dùng AtomicLong (nhanh, không cần khóa)

Cần HashMap an toàn cho đa luồng?
  → Dùng ConcurrentHashMap

Cần bảo vệ NHIỀU thao tác liên quan (phải xảy ra cùng nhau)?
  → Dùng synchronized block
```

### Bảng so sánh chi tiết

| Tiêu chí | `synchronized` | `AtomicLong` | `ConcurrentHashMap` |
|-----------|---------------|-------------|-------------------|
| **Loại** | Keyword (Java) | Class | Class |
| **Mục đích** | Khóa đoạn code | Biến số nguyên tử | Map thread-safe |
| **Cơ chế** | Lock (blocking) | CAS (non-blocking) | Segment locks |
| **Phạm vi** | Bất kỳ đoạn code nào | 1 biến long duy nhất | Cấu trúc Map |
| **Hiệu năng** | Trung bình | Cao | Cao |
| **Dễ dùng** | Dễ | Rất dễ | Dễ |
| **Nhược điểm** | Có thể deadlock | Chỉ cho 1 biến | Không hỗ trợ null key/value |

---

## 6. Áp dụng vào Rate Limiter

### Tại sao Fixed Window Counter dùng synchronized mà không dùng AtomicLong?

Đây là câu hỏi rất hay! Hãy xem 2 phương án:

#### Phương án 1: Dùng AtomicLong (CÓ LỖI!)

```java
// ❌ SAI - Race condition vẫn xảy ra!
class WindowState {
    volatile long windowId;         // Volatile: đọc/ghi luôn từ bộ nhớ chính
    AtomicLong counter = new AtomicLong(0);
}

public boolean allowRequest(String key) {
    long currentWindowId = getCurrentTimeMillis() / windowSizeInMillis;
    WindowState state = windowStateMap.computeIfAbsent(key, k -> new WindowState(currentWindowId));

    // 🐛 BUG Ở ĐÂY:
    // Bước 1 và Bước 2 là 2 thao tác RIÊNG RẼ, thread khác chen ngang được!

    // Bước 1: Kiểm tra window
    if (state.windowId != currentWindowId) {
        state.windowId = currentWindowId;
        state.counter.set(0);        // ← Thread B có thể đọc counter ở đây = 0
    }                                //    rồi tăng lên 1 TRƯỚC khi Thread A kịp tăng

    // Bước 2: Tăng counter
    long count = state.counter.incrementAndGet();
    return count <= maxRequests;
}
```

**Vấn đề:** Giữa bước "kiểm tra/reset window" và bước "tăng counter", thread khác có thể chen vào:

```
Thread A: kiểm tra windowId → cần reset → set counter = 0
                                                              Thread B: đọc counter = 0
Thread A: counter.incrementAndGet() → counter = 1
                                                              Thread B: counter.incrementAndGet() → counter = 2
→ Đúng trong trường hợp này, NHƯNG...

Thread A: kiểm tra windowId → cần reset
                                                              Thread B: kiểm tra windowId → cũng cần reset
Thread A: set counter = 0
Thread A: counter++ → 1
                                                              Thread B: set counter = 0   ← XÓA MẤT REQUEST CỦA A!
                                                              Thread B: counter++ → 1
→ Mất 1 request! Counter = 1 thay vì 2!
```

#### Phương án 2: Dùng synchronized (ĐÚNG!)

```java
// ✅ ĐÚNG - Toàn bộ logic được bảo vệ
synchronized (state) {
    // Trong block này, CHỈ 1 THREAD được chạy tại mỗi thời điểm
    // Nên cả 3 bước dưới đây xảy ra NGUYÊN TỬ:

    // Bước 1: Kiểm tra window
    if (state.windowId != currentWindowId) {
        state.windowId = currentWindowId;
        state.counter = 0;
    }

    // Bước 2: Tăng counter
    state.counter++;

    // Bước 3: Kiểm tra giới hạn
    return state.counter <= maxRequests;

    // → Không thread nào chen ngang được giữa 3 bước này!
}
```

### Quy tắc nhớ

```
┌─────────────────────────────────────────────────────────────┐
│  AtomicLong:    Dùng khi CHỈ CẦN 1 thao tác atomic         │
│                 (tăng counter, đọc giá trị)                 │
│                                                             │
│  synchronized:  Dùng khi cần NHIỀU thao tác phải xảy ra    │
│                 CÙNG NHAU không bị chen ngang               │
│                 (kiểm tra → reset → tăng → so sánh)         │
└─────────────────────────────────────────────────────────────┘
```

Trong Fixed Window Counter, chúng ta cần **3 thao tác phải xảy ra cùng nhau**:
1. Kiểm tra windowId có thay đổi không
2. Reset counter nếu cần
3. Tăng counter và so sánh với maxRequests

→ **Buộc phải dùng `synchronized`**, `AtomicLong` không đủ!
