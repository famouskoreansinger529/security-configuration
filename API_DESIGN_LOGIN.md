# 🔐 API Design Document - Authentication với Google OAuth

---

## 📑 Mục lục

1. [Tổng quan](#1-tổng-quan)
2. [Workflow Diagrams](#2-workflow-diagrams)
3. [API Endpoints](#3-api-endpoints)
4. [Database Schema](#4-database-schema)
5. [JWT Structure](#5-jwt-structure)
6. [Error Codes](#6-error-codes)
7. [Security Considerations](#7-security-considerations)

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
     │                  │  {access_token} + Set-Cookie(RT)  │
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
         - Body: { access_token, user_info }
         - Set-Cookie: refresh_token (HttpOnly, Secure, SameSite)
              │
              ▼
[FE] ──► Lưu Access Token vào memory/localStorage
         Refresh Token tự động được browser lưu trong cookie
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

[User] ──► Gọi bất kỳ API nào (đã có Access Token)
              │
              ▼
[FE] ──► Gửi request với Header: Authorization: Bearer {access_token}
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
         - Body: { access_token }
         - Set-Cookie: refresh_token mới (HttpOnly, Secure)
              │
              ▼
[FE] ──► Lưu Access Token mới
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
         - Header: Authorization: Bearer {access_token}
         - Body hoặc Cookie: refresh_token
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
         {                                            {
           success: false,                              success: true,
           error: "LOGOUT_FAILED",                      message: "Logged out successfully"
           message: "Invalid token"                   }
         }                                              │
    │                                                   ▼
    │                                        [FE] ──► Xóa AT khỏi localStorage/memory
    │                                                   │
    │                                                   ▼
    │                                        [FE] ──► Xóa RT cookie
    │                                                   │
    ▼                                                   ▼
[FE] ──► Xóa AT, RT ở client              [FE] ──► Redirect về trang Login
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
| AT & RT đều valid | Lưu AT vào invalidated_tokens, xóa RT khỏi DB | 200 OK | Xóa tokens, redirect Login |
| AT invalid, RT valid | Xóa RT khỏi DB | 400 Bad Request | Xóa tokens, redirect Login |
| AT valid, RT invalid | Lưu AT vào invalidated_tokens | 400 Bad Request | Xóa tokens, redirect Login |
| AT & RT đều invalid | Không làm gì | 400 Bad Request | Xóa tokens, redirect Login |

> **💡 Tại sao dùng chung response 400 cho các trường hợp fail?**
> - Bảo mật: Không tiết lộ token nào bị sai
> - Đơn giản: FE chỉ cần xử lý 1 case - xóa tokens và redirect
> - Kết quả cuối cùng giống nhau: User được logout

---

## 3. API Endpoints

### 3.1 POST /api/auth/google

**Mô tả:** Đăng nhập với Google OAuth

#### Request

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| code | string | ✅ | Authorization code từ Google |
| redirect_uri | string | ✅ | Redirect URI đã đăng ký với Google |

```http
POST /api/auth/google HTTP/1.1
Host: api.example.com
Content-Type: application/json

{
  "code": "4/0AX4XfWh...",
  "redirect_uri": "https://example.com/callback"
}
```

#### Response Success (200 OK)

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 900,
    "user": {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "email": "user@example.com",
      "name": "Nguyen Van A",
      "avatar": "https://lh3.googleusercontent.com/...",
      "role": "user"
    }
  }
}
```

**Response Headers:**
```
Set-Cookie: refresh_token=eyJhbGciOiJIUzI1NiIs...; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=604800
```

#### Response Errors

| Status | Error Code | Description |
|--------|------------|-------------|
| 400 | INVALID_CODE | Authorization code không hợp lệ hoặc đã hết hạn |
| 400 | MISSING_REDIRECT_URI | Thiếu redirect_uri |
| 403 | USER_NOT_REGISTERED | User không được đăng ký trong hệ thống |
| 500 | GOOGLE_API_ERROR | Lỗi khi gọi Google API |
| 500 | INTERNAL_ERROR | Lỗi server |

```json
{
  "success": false,
  "error": {
    "code": "USER_NOT_REGISTERED",
    "message": "User is not registered in the system. Please contact administrator."
  }
}
```

---

### 3.2 POST /api/auth/refresh

**Mô tả:** Làm mới Access Token bằng Refresh Token

#### Request

```http
POST /api/auth/refresh HTTP/1.1
Host: api.example.com
Cookie: refresh_token=eyJhbGciOiJIUzI1NiIs...
```

*Không cần body, Refresh Token được gửi qua cookie*

#### Response Success (200 OK)

```json
{
  "success": true,
  "data": {
    "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "Bearer",
    "expires_in": 900
  }
}
```

#### Response Errors

| Status | Error Code | Description |
|--------|------------|-------------|
| 401 | MISSING_REFRESH_TOKEN | Không tìm thấy Refresh Token trong cookie |
| 401 | INVALID_REFRESH_TOKEN | Refresh Token không hợp lệ hoặc đã hết hạn |
| 401 | TOKEN_REVOKED | Token đã bị thu hồi |
| 401 | USER_NOT_FOUND | User không còn tồn tại trong hệ thống |

---

### 3.3 POST /api/auth/logout

**Mô tả:** Đăng xuất và thu hồi Refresh Token

#### Request

```http
POST /api/auth/logout HTTP/1.1
Host: api.example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
Cookie: refresh_token=eyJhbGciOiJIUzI1NiIs...
```

#### Response Success (200 OK)

```json
{
  "success": true,
  "message": "Logged out successfully"
}
```

**Response Headers:**
```
Set-Cookie: refresh_token=; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=0
```

#### Response Errors

| Status | Error Code | Description |
|--------|------------|-------------|
| 401 | UNAUTHORIZED | Không có hoặc Access Token không hợp lệ |

---

### 3.4 GET /api/auth/me

**Mô tả:** Lấy thông tin user hiện tại

#### Request

```http
GET /api/auth/me HTTP/1.1
Host: api.example.com
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

#### Response Success (200 OK)

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "name": "Nguyen Van A",
    "avatar": "https://lh3.googleusercontent.com/...",
    "role": "user",
    "created_at": "2026-01-20T10:00:00Z",
    "updated_at": "2026-01-25T15:30:00Z"
  }
}
```

#### Response Errors

| Status | Error Code | Description |
|--------|------------|-------------|
| 401 | UNAUTHORIZED | Access Token không hợp lệ hoặc đã hết hạn |
| 404 | USER_NOT_FOUND | User không tồn tại |

---

## 4. Database Schema

### 4.1 Bảng Users

```sql
CREATE TABLE users (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL UNIQUE,
    name        VARCHAR(255) NOT NULL,
    avatar      VARCHAR(500),
    role        VARCHAR(50) DEFAULT 'user',
    is_active   BOOLEAN DEFAULT true,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_users_email (email)
);
```

### 4.2 Bảng Refresh Tokens

```sql
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    jti         VARCHAR(255) NOT NULL UNIQUE,
    expires_at  TIMESTAMP NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked_at  TIMESTAMP NULL,

    INDEX idx_refresh_tokens_jti (jti),
    INDEX idx_refresh_tokens_user_id (user_id),
    INDEX idx_refresh_tokens_expires_at (expires_at)
);
```

### 4.3 Entity Relationship Diagram

```
┌─────────────────────┐         ┌─────────────────────────┐
│       users         │         │    refresh_tokens       │
├─────────────────────┤         ├─────────────────────────┤
│ id (PK)             │────┐    │ id (PK)                 │
│ email               │    │    │ user_id (FK)            │───┘
│ name                │    └───>│ jti                     │
│ avatar              │         │ expires_at              │
│ role                │         │ created_at              │
│ is_active           │         │ revoked_at              │
│ created_at          │         └─────────────────────────┘
│ updated_at          │
└─────────────────────┘
```

---

## 5. JWT Structure

### 5.1 Access Token

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
  "email": "user@example.com",
  "name": "Nguyen Van A",
  "role": "user",
  "iat": 1706500000,
  "exp": 1706500900
}
```

| Claim | Type | Description |
|-------|------|-------------|
| sub | string | User ID |
| email | string | Email của user |
| name | string | Tên user |
| role | string | Vai trò (user, admin,...) |
| iat | number | Thời gian tạo token (Unix timestamp) |
| exp | number | Thời gian hết hạn (Unix timestamp) |

**Expiry:** 15 phút (900 giây)

### 5.2 Refresh Token

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

## 6. Error Codes

### 6.1 Tổng hợp Error Codes

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

### 6.2 Error Response Format

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

## 7. Security Considerations

### 7.1 Token Storage

| Token | Storage | Lý do |
|-------|---------|-------|
| Access Token | Memory hoặc localStorage | Cần truy cập từ JS để gửi trong header |
| Refresh Token | HttpOnly Cookie | Bảo vệ khỏi XSS attack |

### 7.2 Cookie Configuration

```
Set-Cookie: refresh_token=xxx;
  HttpOnly;           # Không thể truy cập từ JavaScript
  Secure;             # Chỉ gửi qua HTTPS
  SameSite=Strict;    # Chống CSRF
  Path=/api/auth;     # Chỉ gửi cho auth endpoints
  Max-Age=604800      # 7 ngày
```

### 7.3 Best Practices

1. **Access Token ngắn hạn (15 phút):** Giảm thiểu rủi ro nếu token bị lộ
2. **Refresh Token dài hạn (7 ngày):** UX tốt hơn, không cần login thường xuyên

### 7.4 Checklist bảo mật

- [ ] Sử dụng HTTPS cho tất cả endpoints
- [ ] Access Token expiry: 15 phút
- [ ] Refresh Token expiry: 7 ngày
- [ ] Refresh Token lưu trong HttpOnly cookie
- [ ] Validate tất cả input từ client
- [ ] Rate limiting cho login endpoint
- [ ] Log tất cả login attempts
- [ ] Có cơ chế revoke token khi cần
