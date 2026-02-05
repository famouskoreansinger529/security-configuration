# 🔐 API Design Document - Authentication với Google OAuth

---

## 📑 Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Workflow Diagrams](#2-workflow-diagrams)
3. [JWT Structure](#5-jwt-structure)
4. [Error Codes](#6-error-codes)
5. [Security Considerations](#7-security-considerations)

---

## 1. Tổng quan

### 1.1 Mô tả
Hệ thống authentication sử dụng Google OAuth 2.0 để xác thực người dùng. Chỉ những user đã được admin thêm vào hệ thống mới có thể đăng nhập (không hỗ trợ tự đăng ký).

### 1.2 Công nghệ sử dụng
- **Authentication:** Google OAuth 2.0
- **Token:** JWT (JSON Web Token)
- **Access Token Expiry:** 15 phút
- **Refresh Token Expiry:** 7 ngày

### 1.3 Flow tổng quát

```
┌──────────┐      ┌──────────┐      ┌──────────┐      ┌──────────┐
│  User    │ ──── │ Frontend │ ──── │ Backend  │ ──── │  Google  │
└──────────┘      └──────────┘      └──────────┘      └──────────┘
     │                  │                 │                 │
     │  Login click     │                 │                 │
     │─────────────────>│                 │                 │
     │                  │  Redirect to Google               │
     │                  │────────────────────────────────────>
     │                  │                 │                 │
     │                  │      Authorization Code           │
     │                  │<────────────────────────────────────
     │                  │                 │                 │
     │                  │  POST /auth/google {code}         │
     │                  │────────────────>│                 │
     │                  │                 │  Exchange code  │
     │                  │                 │────────────────>│
     │                  │                 │   User info     │
     │                  │                 │<────────────────│
     │                  │                 │                 │
     │                  │  Set-Cookie(AT) + Set-Cookie(RT)  │
     │                  │<────────────────│                 │
     │  Redirect Home   │                 │                 │
     │<─────────────────│                 │                 │
```

---

## 2. Workflow Diagrams

### 2.1 Workflow 1: Login với Google

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           WORKFLOW 1: LOGIN VỚI GOOGLE                          │
└─────────────────────────────────────────────────────────────────────────────────┘

[User] ──► Vào trang cần auth (chưa có Access Token)
              │
              ▼
[FE] ──► Redirect đến trang Login
              │
              ▼
[User] ──► Click "Login with Google"
              │
              ▼
[Google] ──► Hiện popup/redirect để user đăng nhập
              │
              ├──► (Sai) ──► Báo lỗi, login lại
              │
              ▼ (Đúng)
[Google] ──► Trả về authorization code cho FE (qua redirect_uri)
              │
              ▼
[FE] ──► Gọi API: POST /api/auth/google với { code, redirect_uri }
              │
              ▼
[BE] ──► Dùng code + client_secret để gọi Google API lấy tokens
              │
              ▼
[BE] ──► Dùng Google access_token để lấy user info (email, name,...)
              │
              ▼
[BE] ──► Tìm user trong DB bằng email
              │
              ├──► (Không tìm thấy) ──► Return 403 Forbidden
              │                         "User not registered in system"
              ▼ (Tìm thấy)
[BE] ──► Tạo Access Token (JWT)
         - Payload: { user_id, email, role, ... }
         - Expiry: 15 phút
              │
              ▼
[BE] ──► Tạo Refresh Token (JWT)
         - Payload: { user_id, jti }
         - Expiry: 7 ngày
         - Lưu jti vào DB: RefreshToken(id, user_id, jti, expires_at, created_at)
              │
              ▼
[BE] ──► Response:
         - Body: { user_info }
         - Set-Cookie: access_token (HttpOnly, Secure, SameSite=None)
         - Set-Cookie: refresh_token (HttpOnly, Secure, SameSite=None)
              │
              ▼
[FE] ──► Access Token & Refresh Token tự động được browser lưu trong cookie
         (credentials: 'include' cho cross-domain requests)
              │
              ▼
[FE] ──► Redirect về trang Home hoặc trang user request trước đó
              │
              ▼
         ══════ END WORKFLOW ══════
```

### 2.2 Workflow 2: Kiểm tra API Request (Authentication & Authorization)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    WORKFLOW 2: KIỂM TRA API REQUEST (AUTH MIDDLEWARE)           │
└─────────────────────────────────────────────────────────────────────────────────┘

[User] ──► Gọi bất kỳ API nào (đã có Access Token trong cookie)
              │
              ▼
[FE] ──► Gửi request với credentials: 'include' (browser tự gửi cookie)
              │
              ▼
[BE] ──► (1) Validate Access Token
         - Kiểm tra signature có hợp lệ không
         - Kiểm tra token có còn hạn không
              │
              ├──► (Token không hợp lệ - sai signature)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "INVALID_TOKEN", message: "Token is invalid" }
              │    FE redirect về trang Login
              │
              ├──► (Token hết hạn)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "TOKEN_EXPIRED", message: "Access token expired, please refresh" }
              │         │
              │         ▼
              │    [FE] ──► Gọi API refresh token (xem Workflow 2.1)
              │
              ▼ (Token hợp lệ & còn hạn)
[BE] ──► (2) Kiểm tra trong bảng invalidated_tokens
         - Bảng này lưu các token đã bị đăng xuất/revoke
         - Cấu trúc: (id, jti, expires_at)
         - Worker chạy mỗi ngày để dọn dẹp token hết hạn
              │
              ├──► (Tìm thấy trong invalidated_tokens)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "TOKEN_REVOKED", message: "Token has been revoked" }
              │    FE redirect về trang Login
              │
              ▼ (Không có trong invalidated_tokens - Token OK)
[BE] ──► (3) Kiểm tra quyền truy cập (Authorization)
         - Lấy role từ token payload
         - Kiểm tra user có quyền truy cập API này không
              │
              ├──► (Không có quyền)
              │         │
              │         ▼
              │    Return 403 Forbidden
              │    { error: "FORBIDDEN", message: "You don't have permission to access this resource" }
              │
              ▼ (Có quyền)
[BE] ──► Cho phép request đi tiếp đến API handler
              │
              ▼
[BE] ──► Xử lý logic API và trả về response
              │
              ▼
         ══════ END WORKFLOW ══════
```

### 2.2.1 Sub-flow: Refresh Token khi Access Token hết hạn

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    WORKFLOW 2.1: REFRESH TOKEN                                  │
└─────────────────────────────────────────────────────────────────────────────────┘

[FE] ──► Nhận response 401 với error "TOKEN_EXPIRED"
              │
              ▼
[FE] ──► Gọi API: POST /api/auth/refresh
         - Gửi kèm Refresh Token (trong cookie hoặc body)
              │
              ▼
[BE] ──► (1) Validate Refresh Token
         - Kiểm tra signature với secret_key
         - Kiểm tra format JWT có đúng không
         - Kiểm tra token có hết hạn không
              │
              ├──► (RT không hợp lệ - sai signature/format)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "INVALID_REFRESH_TOKEN", message: "Refresh token is invalid" }
              │         │
              │         ▼
              │    [FE] ──► Redirect về trang Login
              │
              ├──► (RT đã hết hạn)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "REFRESH_TOKEN_EXPIRED", message: "Refresh token expired" }
              │         │
              │         ▼
              │    [FE] ──► Redirect về trang Login
              │
              ▼ (RT hợp lệ & còn hạn)
[BE] ──► (2) Kiểm tra Refresh Token trong DB
         - Bảng: refresh_tokens (id, jti, user_id)
         - Tìm theo jti của RT
              │
              ├──► (Không tìm thấy trong DB)
              │         │
              │         ▼
              │    Return 401 Unauthorized
              │    { error: "REFRESH_TOKEN_NOT_FOUND", message: "Refresh token not found in system" }
              │         │
              │         ▼
              │    [FE] ──► Redirect về trang Login
              │
              ▼ (Tìm thấy trong DB)
[BE] ──► Xóa Refresh Token cũ khỏi DB
              │
              ▼
[BE] ──► Tạo Refresh Token mới
         - Lưu RT mới vào DB (id, jti, user_id, expires_at)
              │
              ▼
[BE] ──► Tạo Access Token mới
              │
              ▼
[BE] ──► Response:
         - Body: { success: true }
         - Set-Cookie: access_token mới (HttpOnly, Secure, SameSite=None)
         - Set-Cookie: refresh_token mới (HttpOnly, Secure, SameSite=None)
              │
              ▼
[FE] ──► Access Token mới tự động được browser lưu trong cookie
              │
              ▼
[FE] ──► Retry lại API request ban đầu với AT mới
         (hoặc redirect về trang Home)
              │
              ▼
         ══════ END WORKFLOW ══════
```

### 2.3 Workflow 3: Logout

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              WORKFLOW 3: LOGOUT                                 │
└─────────────────────────────────────────────────────────────────────────────────┘

[User] ──► Click nút Logout
              │
              ▼
[FE] ──► Gọi API: POST /api/auth/logout
         - Cookie: access_token, refresh_token (tự động gửi bởi browser)
              │
              ▼
[BE] ──► Validate Access Token và Refresh Token
         - Kiểm tra signature với secret_key
         - Kiểm tra format JWT
              │
              │
    ┌─────────┴─────────────────────────────────────────┐
    │                                                   │
    ▼                                                   ▼
(AT hoặc RT không hợp lệ)                    (Cả AT và RT đều hợp lệ)
    │                                                   │
    ▼                                                   ▼
[BE] ──► Xử lý cleanup:                      [BE] ──► Lưu AT vào bảng invalidated_tokens
         - Nếu AT fail:                               (jti, expires_at)
           + Tìm RT trong DB (theo jti)                    │
           + Nếu tìm thấy → Xóa RT khỏi DB                 ▼
         - Nếu RT fail (nhưng AT valid):     [BE] ──► Xóa RT khỏi DB
           + Lưu AT vào invalidated_tokens         (theo jti của RT)
    │                                                   │
    ▼                                                   ▼
[BE] ──► Return 400 Bad Request              [BE] ──► Return 200 OK
         + Clear cookie: access_token                 + Clear cookie: access_token
         + Clear cookie: refresh_token                + Clear cookie: refresh_token
         {                                            {
           success: false,                              success: true,
           error: "LOGOUT_FAILED",                      message: "Logged out successfully"
           message: "Invalid token"                   }
         }                                              │
    │                                                   ▼
    │                                        [FE] ──► Cookies đã được clear bởi BE
    │                                                   │
    ▼                                                   ▼
[FE] ──► Cookies đã được clear bởi BE     [FE] ──► Redirect về trang Login
              │                                         │
              ▼                                         ▼
[FE] ──► Redirect về trang Login               ══════ END ══════
              │
              ▼
         ══════ END WORKFLOW ══════
```

**📝 Ghi chú về Logout:**

| Trường hợp | Xử lý BE | Response | Xử lý FE |
|------------|----------|----------|----------|
| AT & RT đều valid | Lưu AT vào invalidated_tokens, xóa RT khỏi DB, clear cookies | 200 OK | Redirect Login |
| AT invalid, RT valid | Xóa RT khỏi DB, clear cookies | 400 Bad Request | Redirect Login |
| AT valid, RT invalid | Lưu AT vào invalidated_tokens, clear cookies | 400 Bad Request | Redirect Login |
| AT & RT đều invalid | Clear cookies | 400 Bad Request | Redirect Login |

---

## 3. JWT Structure

### 3.1 Access Token

**Header:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "role": "user",
  "iat": 1706500000,
  "exp": 1706500900
}
```

| Claim | Type | Description |
|-------|------|-------------|
| sub | string | User ID |
| role | string | Vai trò (user, admin,...) |
| iat | number | Thời gian tạo token (Unix timestamp) |
| exp | number | Thời gian hết hạn (Unix timestamp) |

**Expiry:** 15 phút (900 giây)

### 3.2 Refresh Token

**Header:**
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

**Payload:**
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",
  "jti": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "iat": 1706500000,
  "exp": 1707104800
}
```

| Claim | Type | Description |
|-------|------|-------------|
| sub | string | User ID |
| jti | string | JWT ID - unique identifier, lưu trong DB |
| iat | number | Thời gian tạo token |
| exp | number | Thời gian hết hạn |

**Expiry:** 7 ngày (604800 giây)

---

## 4. Error Codes

### 4.1 Tổng hợp Error Codes

| Code | HTTP Status | Description |
|------|-------------|-------------|
| INVALID_CODE | 400 | Authorization code không hợp lệ |
| MISSING_REDIRECT_URI | 400 | Thiếu redirect_uri trong request |
| INVALID_REQUEST | 400 | Request body không hợp lệ |
| UNAUTHORIZED | 401 | Không có hoặc token không hợp lệ |
| INVALID_ACCESS_TOKEN | 401 | Access Token không hợp lệ |
| ACCESS_TOKEN_EXPIRED | 401 | Access Token đã hết hạn |
| INVALID_REFRESH_TOKEN | 401 | Refresh Token không hợp lệ |
| MISSING_REFRESH_TOKEN | 401 | Không tìm thấy Refresh Token |
| TOKEN_REVOKED | 401 | Token đã bị thu hồi |
| USER_NOT_REGISTERED | 403 | User không được đăng ký trong hệ thống |
| USER_INACTIVE | 403 | Tài khoản đã bị vô hiệu hóa |
| USER_NOT_FOUND | 404 | User không tồn tại |
| GOOGLE_API_ERROR | 500 | Lỗi khi gọi Google API |
| INTERNAL_ERROR | 500 | Lỗi server nội bộ |

### 4.2 Error Response Format

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable error message"
  }
}
```

---

# 5. Security Considerations

## 5.1 Token Storage Strategy (Why HttpOnly Cookie?)

Việc lựa chọn nơi lưu trữ Token là sự cân nhắc giữa rủi ro **XSS (Cross-Site Scripting)** và **CSRF (Cross-Site Request Forgery)**.

Chúng tôi quyết định sử dụng **HttpOnly Cookie** thay vì **LocalStorage / SessionStorage** vì các lý do sau:

| Tiêu chí | LocalStorage / JS Variable | HttpOnly Cookie (Được chọn) |
|--------|----------------------------|-----------------------------|
| **Cơ chế truy cập** | JavaScript có thể đọc/ghi trực tiếp | JavaScript **không thể truy cập** (thông qua flag `HttpOnly`). Chỉ trình duyệt mới có quyền gửi kèm request |
| **Rủi ro XSS** | **Cao**. Nếu hacker chèn được mã độc JS vào trang web, chúng có thể đọc toàn bộ Token và gửi về server của chúng | **Thấp**. Hacker có thể thực thi lệnh JS, nhưng **không thể đánh cắp Raw Token** để sử dụng ở nơi khác |
| **Rủi ro CSRF** | Thấp (vì JS phải tự đính kèm token vào header) | Trung bình / Cao. Trình duyệt tự động gửi cookie nên có thể bị lợi dụng |
| **Giải pháp** | Khó khắc phục triệt để XSS | Có thể giảm thiểu CSRF bằng `SameSite` hoặc CSRF Token (Double Submit Cookie) |

**Kết luận**

> Bảo vệ **Access Token khỏi bị đánh cắp thông qua XSS** quan trọng hơn.
> Rủi ro CSRF sẽ được giảm thiểu thông qua **cấu hình Cookie** và **CORS chặt chẽ**.

---

## 5.2 Cookie Configuration Details

Cấu hình dưới đây áp dụng cho kiến trúc **Cross-Domain**
(Frontend và Backend nằm trên hai domain khác nhau, ví dụ: `app.com` và `api.com`).

---

### A. Access Token Cookie

**Mục tiêu**

- Dùng để xác thực từng request
- Thời gian sống ngắn để giảm thiểu rủi ro nếu bị lộ session

Set-Cookie: access_token=xxx;
  HttpOnly;       # Bảo mật: JS client không thể đọc (Chặn XSS)
  Secure;         # Bảo mật: Chỉ gửi qua HTTPS (Bắt buộc nếu SameSite=None)
  SameSite=None;  # Cross-domain: Cho phép gửi cookie khi gọi từ domain khác
  Path=/;         # Phạm vi: Có hiệu lực trên toàn bộ API endpoints
  Max-Age=900     # Expiration: 15 phút (Đồng bộ với thời gian sống của JWT)

### B. Refresh Token Cookie

#### Mục tiêu

- Dùng để lấy **Access Token** mới
- Là thành phần **nhạy cảm nhất**, cần được bảo vệ kỹ và **hạn chế phạm vi gửi đi**

```http
Set-Cookie: refresh_token=yyy;
  HttpOnly;
  Secure;
  SameSite=None;
  Path=/api/auth/refresh; # TỐI ƯU HÓA: Chỉ gửi cookie khi gọi endpoint refresh
  Max-Age=604800          # Expiration: 7 ngày (hoặc lâu hơn tùy nghiệp vụ)

---

## 5.3 Cross-Origin Resource Sharing (CORS)

> Do sử dụng SameSite=None để hỗ trợ Cookie cross-domain,
> CORS đóng vai trò là lớp bảo vệ thứ hai để ngăn các request trái phép từ > > domain lạ.

---

## 5.4 Cross-Site Request Forgery (CSRF)
