# Tài liệu về Security và Authentication trong EOS Backend

## Mục lục
1. [Tổng quan về kiến trúc bảo mật](#1-tổng-quan-về-kiến-trúc-bảo-mật)
2. [JWT (JSON Web Token) và cách validate token](#2-jwt-json-web-token-và-cách-validate-token)
3. [Spring Security Filter Chain](#3-spring-security-filter-chain)
4. [Luồng xử lý Request → Auth Middleware → Controller](#4-luồng-xử-lý-request--auth-middleware--controller)
5. [Chi tiết cấu hình trong code](#5-chi-tiết-cấu-hình-trong-code)
6. [Bảo mật Cookie](#6-bảo-mật-cookie)
7. [Token Invalidation (Logout)](#7-token-invalidation-logout)
8. [Best Practices và Security Considerations](#8-best-practices-và-security-considerations)
9. [Tổng kết](#9-tổng-kết)
10. [Tài liệu tham khảo](#10-tài-liệu-tham-khảo)
---

## 1. Tổng quan về kiến trúc bảo mật

### 1.1. Kiến trúc chính
Hệ thống sử dụng **JWT-based authentication** với **stateless sessions**:
- **Access Token**: Lưu trong cookie, thời hạn 15 phút
- **Refresh Token**: Lưu trong cookie, thời hạn 7 ngày
- **Không sử dụng session** trên server (stateless)
- **Spring Security Filter Chain** để xác thực mọi request

### 1.2. Các thành phần chính
```
┌─────────────────────────────────────────────────────┐
│                    Client Browser                    │
│            (Cookies: access_token, refresh_token)    │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP Request
                       ▼
┌─────────────────────────────────────────────────────┐
│              Spring Security Filter Chain            │
│  ┌───────────────────────────────────────────────┐  │
│  │        JwtAuthenticationFilter                │  │
│  │  - Extract token from cookie                  │  │
│  │  - Validate JWT signature & expiration        │  │
│  │  - Check token invalidation (logout)          │  │
│  │  - Verify user exists & is active             │  │
│  │  - Set SecurityContext                        │  │
│  └───────────────────────────────────────────────┘  │
└──────────────────────┬──────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────┐
│                  Controller Layer                    │
│            (Protected endpoints)                     │
└─────────────────────────────────────────────────────┘
```

---

## 2. JWT (JSON Web Token) và cách validate token

### 2.1. Giới thiệu về JWT
**JWT** là một chuẩn mở (RFC 7519) để truyền thông tin an toàn giữa các bên dưới dạng JSON object. JWT được ký điện tử để đảm bảo tính toàn vẹn.

#### Cấu trúc JWT
```
[Header].[Payload].[Signature]
```

**Ví dụ**:
```
eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxMjM0IiwidXNlcklkIjoiNTY3OCIsInJvbGUiOiJVU0VSIn0.signature
```

### 2.2. Cách tạo JWT trong project

"Kết hợp" header, payload và secret_key sau đó dùng thuật toán hash để cho ra signature.

#### Access Token
File: `JwtUtil.java`

```java
public String generateAccessToken(User user) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());
    String jti = UUID.randomUUID().toString(); // JWT ID duy nhất

    return Jwts.builder()
            .id(jti)                                    // JWT ID
            .subject(user.getId().toString())           // User ID
            .claim("role", user.getRole().getName())    // User role
            .issuedAt(now)                             // Thời điểm tạo
            .expiration(expiryDate)                    // Thời điểm hết hạn
            .signWith(getSigningKey())                 // Ký với secret key
            .compact();
}
```

**Các thành phần trong Access Token**:
- `jti` (JWT ID): UUID duy nhất để quản lý token invalidation
- `sub` (Subject): User ID
- `role`: Vai trò của user (USER, ADMIN, ...)
- `iat` (Issued At): Thời điểm tạo token
- `exp` (Expiration): Thời điểm hết hạn (15 phút)

#### Refresh Token
```java
public TokenInfo generateRefreshToken(User user) {
    Date now = new Date();
    Date expiryDate = new Date(now.getTime() + jwtProperties.getRefreshTokenExpiration());
    String jti = UUID.randomUUID().toString();

    String token = Jwts.builder()
            .id(jti)
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSigningKey())
            .compact();

    return new TokenInfo(token, jti, expiryDate);
}
```

**Refresh Token đơn giản hơn**, chỉ chứa:
- User ID
- JTI để tracking
- Expiration (7 ngày)

### 2.3. Secret Key và thuật toán ký

#### Tạo Signing Key

```java
private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
}
```

**Thuật toán**: HMAC-SHA256 (HS256)
- Symmetric key algorithm
- Cùng một secret key để ký và verify
- Secret key phải **ít nhất 256 bits (32 bytes)**

**Cấu hình** (application.yaml):
```yaml
jwt:
  secret: ${JWT_SECRET}  # Phải là string >= 32 ký tự
  access-token-expiration: 900000      # 15 phút
  refresh-token-expiration: 604800000  # 7 ngày
```

### 2.4. Validate Token

#### Quá trình validation

Lấy header, payload của request access token sau đó "kết hợp" với secret_key dùng thật toán hash để tạo ra signature, dùng signature đó để check matching với request signature.

File: `JwtUtil.java`

```java
public Claims validateToken(String token) {
    return Jwts.parser()
            .verifyWith(getSigningKey())  // Verify chữ ký với secret key
            .build()
            .parseSignedClaims(token)     // Parse và verify
            .getPayload();                 // Trả về claims nếu hợp lệ
}
```

#### Các bước validate:
1. **Verify Signature**: Kiểm tra chữ ký có khớp với secret key không
2. **Check Expiration**: Token có hết hạn chưa
3. **Parse Claims**: Lấy thông tin từ payload

#### Các exception có thể xảy ra:
- `ExpiredJwtException`: Token đã hết hạn
- `JwtException`: Token không hợp lệ (sai signature, format...)
- `MalformedJwtException`: Token có format sai
- `SignatureException`: Chữ ký không khớp

---

## 3. Spring Security Filter Chain

### 3.1. Filter Chain là gì?

**Spring Security Filter Chain** là một chuỗi các servlet filters được Spring Security sử dụng để xử lý authentication và authorization.

```
Request → Filter1 → Filter2 → Filter3 → ... → Controller
```

Mỗi filter có thể:
- Xử lý request
- Chuyển request sang filter tiếp theo (`filterChain.doFilter()`)
- Dừng request và trả về response (không gọi `doFilter()`)

### 3.2. Cấu hình SecurityFilterChain trong project

File: `SecurityConfig.java`

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // 1. Tắt CSRF protection (vì dùng JWT, không phải session)
        .csrf(AbstractHttpConfigurer::disable)
        
        // 2. Cấu hình CORS
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        
        // 3. Session management: STATELESS (không tạo session)
        .sessionManagement(session ->
            session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        
        // 4. Authorization rules
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/google", "/api/auth/refresh").permitAll()
            .requestMatchers("/api/auth/logout").authenticated()
            .anyRequest().authenticated()
        )
        
        // 5. Thêm custom JWT filter TRƯỚC UsernamePasswordAuthenticationFilter
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### 3.3. Phân tích từng phần

#### 1. CSRF (Cross-Site Request Forgery) Protection
```java
.csrf(AbstractHttpConfigurer::disable)
```
**Tại sao tắt CSRF?**
- CSRF protection chủ yếu cho session-based authentication
- Project này dùng JWT với stateless sessions
- Token được gửi qua cookie với `SameSite=None` attribute
- **Lưu ý**: Vì fe be cross-orogin => cookie set SameSite=None nên chúng ta cần phải enable csrf và config kỹ. Chúng ta  sẽ xem xét config ở các đợt update tiếp theo.

#### 2. CORS Configuration
```java
.cors(cors -> cors.configurationSource(corsConfigurationSource()))
```

**CORS Bean**:
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setAllowCredentials(true); // Quan trọng: cho phép gửi cookies
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

**Cấu hình CORS quan trọng**:
- `allowedOrigins`: Frontend URLs được phép gọi API
- `allowCredentials(true)`: **Bắt buộc** để gửi cookies cross-origin
- `allowedMethods`: Các HTTP methods được phép
- `maxAge(3600L)`: Cache preflight response trong 3600 giây (1 giờ)

#### 3. Session Management
```java
.sessionManagement(session ->
    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**STATELESS** nghĩa là:
- Spring Security **KHÔNG** tạo HttpSession
- Không lưu trữ authentication state trên server
- Mỗi request phải mang theo JWT để authenticate
- Phù hợp với kiến trúc microservices và scalability

#### 4. Authorization Rules
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/auth/google", "/api/auth/refresh").permitAll()
    .requestMatchers("/api/auth/logout").authenticated()
    .anyRequest().authenticated()
)
```

**Quy tắc**:
- `/api/auth/google`, `/api/auth/refresh`: **Public** (không cần token)
- `/api/auth/logout`: Phải **authenticated**
- Tất cả các endpoint khác: Phải **authenticated**

#### 5. Custom JWT Filter
```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

**Thứ tự filter**:
```
Request → ... → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter → ... → Controller
```

`JwtAuthenticationFilter` chạy **TRƯỚC** `UsernamePasswordAuthenticationFilter` để:
- Extract và validate JWT
- Set authentication vào `SecurityContext`
- Các filter sau sẽ biết user đã authenticated

### 3.4. Các annotation quan trọng

```java
@Configuration
@EnableWebSecurity      // Enable Spring Security
@EnableMethodSecurity   // Enable method-level security (@PreAuthorize, @Secured)
@RequiredArgsConstructor
public class SecurityConfig { ... }
```

**@EnableMethodSecurity**: Cho phép dùng annotation trên methods:
```java
@PreAuthorize("hasRole('ADMIN')")
public void deleteUser() { ... }
```

---

## 4. Luồng xử lý Request → Auth Middleware → Controller

### 4.1. Sơ đồ tổng quan

```
┌──────────────────────────────────────────────────────────────┐
│  Client sends request with cookie:                           │
│  Cookie: access_token=eyJhbGc...                             │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Spring Security Filter Chain                                │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  1. CORS Filter                                        │  │
│  │     - Check CORS policy                                │  │
│  │     - Add CORS headers                                 │  │
│  └────────────────────────────────────────────────────────┘  │
│                         │                                     │
│                         ▼                                     │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  2. JwtAuthenticationFilter (CUSTOM)                   │  │
│  │                                                         │  │
│  │  Step 1: Check if public path?                         │  │
│  │          (/api/auth/google, /api/auth/refresh)         │  │
│  │          → Yes: Skip authentication, call next filter  │  │
│  │          → No: Continue                                │  │
│  │                                                         │  │
│  │  Step 2: Extract access_token from cookie             │  │
│  │          → Not found: Return 401 Unauthorized          │  │
│  │          → Found: Continue                             │  │
│  │                                                         │  │
│  │  Step 3: Validate JWT token                            │  │
│  │          - Verify signature with secret key            │  │
│  │          - Check expiration                            │  │
│  │          → Invalid/Expired: Return 401                 │  │
│  │          → Valid: Get Claims (userId, role, jti)       │  │
│  │                                                         │  │
│  │  Step 4: Check if token is invalidated (logged out)   │  │
│  │          - Query: invalidatedTokenRepository           │  │
│  │                   .existsByJti(jti)                    │  │
│  │          → Yes: Return 401 "Token has been revoked"    │  │
│  │          → No: Continue                                │  │
│  │                                                         │  │
│  │  Step 5: Verify user exists and is active             │  │
│  │          - Query: userRepository.findById(userId)      │  │
│  │          → User not found or inactive: Return 401      │  │
│  │          → User OK: Continue                           │  │
│  │                                                         │  │
│  │  Step 6: Create Authentication object                  │  │
│  │          - Create SimpleGrantedAuthority with role     │  │
│  │          - Create UsernamePasswordAuthenticationToken  │  │
│  │          - Set authentication to SecurityContext       │  │
│  │                                                         │  │
│  │  Step 7: Call next filter                              │  │
│  │          filterChain.doFilter(request, response)       │  │
│  └────────────────────────────────────────────────────────┘  │
│                         │                                     │
│                         ▼                                     │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  3. Other Security Filters                             │  │
│  │     - ExceptionTranslationFilter                       │  │
│  │     - FilterSecurityInterceptor                        │  │
│  │     - etc.                                             │  │
│  └────────────────────────────────────────────────────────┘  │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  DispatcherServlet                                           │
│  - Route request to appropriate Controller                   │
└────────────────────────┬─────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  Controller (Protected Endpoint)                             │
│  - SecurityContext contains authenticated user               │
│  - Can access user info via:                                 │
│    SecurityContextHolder.getContext().getAuthentication()    │
└──────────────────────────────────────────────────────────────┘
```

### 4.2. Chi tiết JwtAuthenticationFilter

File: `JwtAuthenticationFilter.java`

```java
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/auth/google",
        "/api/auth/refresh",
        "/api/auth/logout"
    );

    private final JwtUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final UserRepository userRepository;
    private final InvalidatedTokenRepository invalidatedTokenRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        // Step 1: Skip authentication for public endpoints
        String requestPath = request.getRequestURI();
        if (isPublicPath(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 2: Extract access token from cookie
        Optional<String> tokenOpt = cookieUtil.extractAccessToken(request);
        if (tokenOpt.isEmpty()) {
            sendUnauthorizedError(response, "Access token is missing");
            return;
        }

        String token = tokenOpt.get();

        try {
            // Step 3: Validate token
            Claims claims = jwtUtil.validateToken(token);
            String jti = claims.getId();

            // Step 4: Check if token is invalidated (logged out)
            if (invalidatedTokenRepository.existsByJti(jti)) {
                sendUnauthorizedError(response, "Token has been revoked");
                return;
            }

            // Step 5: Get user info and verify
            UUID userId = UUID.fromString(claims.getSubject());
            String role = claims.get("role", String.class);

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty() || !userOpt.get().getIsActive()) {
                sendUnauthorizedError(response, "User not found or inactive");
                return;
            }

            // Step 6: Create authentication object
            SimpleGrantedAuthority authority = 
                new SimpleGrantedAuthority("ROLE_" + role.toUpperCase());

            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    userId.toString(),
                    null,
                    Collections.singleton(authority)
                );

            authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
            );
            
            // Set authentication to SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

        } catch (ExpiredJwtException e) {
            sendUnauthorizedError(response, "Access token expired");
            return;
        } catch (JwtException e) {
            sendUnauthorizedError(response, "Invalid access token");
            return;
        } catch (Exception e) {
            sendUnauthorizedError(response, "Authentication failed");
            return;
        }

        // Step 7: Continue to next filter
        filterChain.doFilter(request, response);
    }
}
```

### 4.3. OncePerRequestFilter

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter
```

**OncePerRequestFilter** đảm bảo:
- Filter chỉ chạy **một lần** cho mỗi request
- Ngay cả khi có forward/include trong servlet

### 4.4. SecurityContext

```java
SecurityContextHolder.getContext().setAuthentication(authentication);
```

**SecurityContext**:
- Thread-local storage chứa authentication info
- Mỗi thread (request) có SecurityContext riêng
- Controllers có thể truy cập authentication:

```java
@GetMapping("/me")
public UserInfo getCurrentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String userId = (String) auth.getPrincipal();
    // Get user info...
}
```

Hoặc dùng annotation:
```java
@GetMapping("/me")
public UserInfo getCurrentUser(@AuthenticationPrincipal String userId) {
    // userId tự động inject
}
```

---

## 5. Chi tiết cấu hình trong code

### 5.1. CookieUtil - Quản lý Cookies

File: `CookieUtil.java`

```java
@Service
@RequiredArgsConstructor
public class CookieUtil {
    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private static final String REFRESH_TOKEN_PATH = "/api/auth";
    private static final String ACCESS_TOKEN_PATH = "/";

    private final CookieProperties cookieProperties;

    private ResponseCookie createCookie(String name, String value, 
                                       String path, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(cookieProperties.isHttpOnly())
                .secure(cookieProperties.isSecure())
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .sameSite(cookieProperties.getSameSite())
                .build();
    }

    public ResponseCookie createAccessTokenCookie(String token, long maxAgeSeconds) {
        return createCookie(ACCESS_TOKEN_COOKIE, token, ACCESS_TOKEN_PATH, maxAgeSeconds);
    }

    public ResponseCookie createRefreshTokenCookie(String token, long maxAgeSeconds) {
        return createCookie(REFRESH_TOKEN_COOKIE, token, REFRESH_TOKEN_PATH, maxAgeSeconds);
    }
}
```

**Cookie Paths**:
- **Access Token**: `path=/` → Gửi cho tất cả requests
- **Refresh Token**: `path=/api/auth` → Chỉ gửi cho auth endpoints

**Lý do**:
- Giảm thiểu rủi ro nếu access token bị lộ
- Refresh token chỉ dùng cho refresh endpoint

### 5.2. Database Entities

#### InvalidatedToken Entity

File: `InvalidatedToken.java`

```java
@Entity
@Table(name = "invalidated_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvalidatedToken {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    private String jti;  // JWT ID

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
```

**Mục đích**:
- Lưu JTI của tokens đã bị revoke (logout)
- Khi validate token, check xem JTI có trong table này không

**Cleanup Strategy**: Cần có scheduled job để xóa expired tokens:

## 6. Bảo mật Cookie

### 6.1. Cookie Attributes

```java
ResponseCookie.from(name, value)
    .httpOnly(true)      // Không thể access qua JavaScript
    .secure(true)        // Chỉ gửi qua HTTPS
    .path(path)          // Path scope
    .maxAge(Duration.ofSeconds(maxAgeSeconds))
    .sameSite("None")    // CORS policy
    .build();
```

### 6.2. HttpOnly

```java
.httpOnly(true)
```

**Tác dụng**:
- Cookie **không thể** truy cập qua `document.cookie` trong JavaScript
- Ngăn chặn **XSS attacks** (Cross-Site Scripting)

**Ví dụ tấn công bị ngăn chặn**:
```javascript
// Attacker code (XSS)
let token = document.cookie; // Cannot access httpOnly cookies
sendToHacker(token);
```

### 6.3. Secure

```java
.secure(true)
```

**Tác dụng**:
- Cookie chỉ gửi qua **HTTPS**
- Ngăn chặn **Man-in-the-Middle attacks**

**Lưu ý Development**:
- Localhost HTTP: Set `secure: false` cho development
- Production: **Luôn** set `secure: true`

### 6.4. SameSite

```java
.sameSite("None")
```

**SameSite Options**:
- `Strict`: Cookie không gửi trong cross-site requests
- `Lax`: Cookie gửi cho top-level navigation (links)
- `None`: Cookie gửi trong mọi cross-site requests (cần `Secure=true`)

**Project dùng `None`** vì:
- Frontend và backend khác domain (e.g., localhost:3000 và localhost:8080)
- Cần gửi cookies trong CORS requests

**CORS Configuration cần thiết**:
```java
configuration.setAllowCredentials(true); // Bắt buộc với SameSite=None
```

### 6.5. Path

```java
// Access Token
.path("/")               // Gửi cho tất cả paths

// Refresh Token
.path("/api/auth")       // Chỉ gửi cho /api/auth/*
```

**Security Benefit**:
- Refresh token có privilege cao (tạo access token mới)
- Giới hạn scope giảm thiểu rủi ro

---

## 7. Token Invalidation (Logout)

### 7.1. Vấn đề với Stateless JWT

**Problem**: JWT là stateless, một khi issue không thể revoke

**Traditional Solutions**:
1. **Short expiration**: Access token 15 phút
2. **Token blacklist**: Lưu JTI của tokens bị revoke

Project sử dụng **Token Blacklist** approach.

### 7.2. Implementation

#### 1. Logout Flow

```
User clicks Logout
      ↓
POST /api/auth/logout
      ↓
Extract access_token JTI
      ↓
Save to invalidated_tokens table
      ↓
Clear cookies
      ↓
Return success
```

#### 2. Validation Check

```java
// In JwtAuthenticationFilter
Claims claims = jwtUtil.validateToken(token);
String jti = claims.getId();

// Check blacklist
if (invalidatedTokenRepository.existsByJti(jti)) {
    sendUnauthorizedError(response, "Token has been revoked");
    return;
}
```

#### 3. Database Query

```java
public interface InvalidatedTokenRepository extends JpaRepository<InvalidatedToken, UUID> {
    boolean existsByJti(String jti);
}
```

**Performance Consideration**:
- Mỗi request authenticated cần query DB
- **Optimization**: Sử dụng Redis cache cho invalidated tokens
- Redis có TTL automatic, không cần cleanup job

### 7.3. Refresh Token Invalidation

**Refresh Token** khác:
- Không lưu trong `invalidated_tokens` table
- Lưu trực tiếp trong `users` table hoặc `refresh_tokens` table
- Khi logout, xóa hẳn refresh token từ DB

---

## 8. Best Practices và Security Considerations

### 8.1. User Verification trong Filter

```java
Optional<User> userOpt = userRepository.findById(userId);
if (userOpt.isEmpty() || !userOpt.get().getIsActive()) {
    sendUnauthorizedError(response, "User not found or inactive");
    return;
}
```

**Tại sao cần check DB mỗi request?**
- User có thể bị disable (`isActive = false`)
- User có thể bị xóa
- Role có thể thay đổi

**Optimization**: Dùng Redis cache:
```java
// Cache user for 5 minutes
@Cacheable(value = "users", key = "#userId")
public Optional<User> findById(UUID userId) { ... }
```

## 9. Tổng kết

### 9.1. Request Flow Summary

```
1. Client Request với cookie
2. CORS Filter kiểm tra origin
3. JwtAuthenticationFilter (Authentication):
   - Extract token từ cookie
   - Validate JWT signature & expiration
   - Check token invalidation
   - Verify user exists & active
   - Set SecurityContext với authorities (roles)
4. FilterSecurityInterceptor (Authorization):
   - Lấy authorities từ SecurityContext
   - Check endpoint permissions
   - So sánh user roles vs required roles
   - ✅ Match: Cho phép đi tiếp
   - ❌ Not match: Return 403 Forbidden
5. Controller xử lý request
6. Response trả về client
```

**Phân biệt Authentication vs Authorization:**

| | Authentication | Authorization |
|---|----------------|---------------|
| **Câu hỏi** | "Bạn là ai?" | "Bạn có quyền làm gì?" |
| **Filter** | JwtAuthenticationFilter | FilterSecurityInterceptor |
| **Check** | Token validity, user exists | User roles vs endpoint permissions |
| **Error** | 401 Unauthorized | 403 Forbidden |
| **Solution** | Login lại / Refresh token | Cần quyền cao hơn (không thể tự fix) |

---

## 10. Tài liệu tham khảo

1. **Spring Security Documentation**:
   - https://docs.spring.io/spring-security/reference/

2. **JJWT Library**:
   - https://github.com/jwtk/jjwt

3. **JWT Standard (RFC 7519)**:
   - https://datatracker.ietf.org/doc/html/rfc7519

4. **OWASP Security Guidelines**:
   - https://owasp.org/www-project-web-security-testing-guide/

5. **Cookie Security**:
   - https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies

---
