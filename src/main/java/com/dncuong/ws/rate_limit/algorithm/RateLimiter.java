package com.dncuong.ws.rate_limit.algorithm;

/**
 * Interface chung cho tất cả các thuật toán Rate Limiting.
 *
 * Mỗi thuật toán rate limiting (Fixed Window, Sliding Window, Token Bucket, ...)
 * sẽ implement interface này, giúp chúng ta dễ dàng thay đổi thuật toán
 * mà không cần sửa code ở tầng controller hay service (Open/Closed Principle).
 *
 * @author dncuong
 */
public interface RateLimiter {

    /**
     * Kiểm tra xem một request từ "key" có được phép đi qua hay không.
     *
     * "key" thường là: địa chỉ IP của client, user ID, API key, hoặc
     * bất kỳ định danh nào dùng để phân biệt các nguồn request.
     *
     * @param key định danh của nguồn gửi request (ví dụ: IP address, user ID)
     * @return {@code true} nếu request được cho phép (chưa vượt giới hạn),
     *         {@code false} nếu request bị từ chối (đã vượt giới hạn)
     */
    boolean allowRequest(String key);
}
