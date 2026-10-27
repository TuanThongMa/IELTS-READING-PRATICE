package vn.tuanthongma.ieltsreading.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AuthController {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", Pattern.UNICODE_CHARACTER_CLASS);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String dummyHash;
    private final HttpSessionSecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    AuthController(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.dummyHash = passwords.encode("not-a-real-password");
    }

    record Credentials(String email, String password) {}
    record Registration(String email, String password, String displayName) {}
    record ProfileUpdate(String displayName) {}
    record PasswordChange(String currentPassword, String newPassword) {}
    record Account(long id, String email, String displayName, String role, String passwordHash, boolean active) {}
    record UserResponse(long id, String email, String displayName, String role) {}

    @GetMapping("/auth/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    void register(@RequestBody Registration input) {
        String email = input.email() == null ? "" : input.email().trim();
        String name = input.displayName() == null ? "" : input.displayName().trim();
        String password = input.password();
        if (email.length() > 254 || !EMAIL.matcher(email).matches() || name.isEmpty() || name.length() > 100
                || password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thông tin đăng ký không hợp lệ.");
        }
        try {
            jdbc.update("""
                    INSERT INTO users (email, email_normalized, password_hash, display_name, role, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'LEARNER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, email, normalize(email), passwords.encode(password), name);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng.");
        }
    }

    @PostMapping("/auth/sessions")
    UserResponse signIn(@RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
        String email = input.email() == null ? "" : input.email();
        String password = input.password() == null ? "" : input.password();
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng.");
        }
        var accounts = jdbc.query("""
                SELECT user_id, email, display_name, role, password_hash, is_active
                FROM users WHERE email_normalized = ?
                """, (row, index) -> new Account(row.getLong("user_id"), row.getString("email"),
                row.getString("display_name"), row.getString("role"), row.getString("password_hash"),
                row.getBoolean("is_active")), normalize(email));
        Account account = accounts.isEmpty() ? null : accounts.getFirst();
        boolean valid = passwords.matches(password, account == null ? dummyHash : account.passwordHash());
        if (!valid || account == null || !account.active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng.");
        }
        if (request.getSession(false) != null) request.changeSessionId();
        var principal = new UsernamePasswordAuthenticationToken(Long.toString(account.id()), null,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(principal);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return new UserResponse(account.id(), account.email(), account.displayName(), account.role());
    }

    @GetMapping("/me")
    UserResponse me(Authentication auth) {
        var accounts = jdbc.query("SELECT user_id, email, display_name, role FROM users WHERE user_id = ? AND is_active = true",
                (row, index) -> new UserResponse(row.getLong("user_id"), row.getString("email"),
                        row.getString("display_name"), row.getString("role")), Long.parseLong(auth.getName()));
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return accounts.getFirst();
    }

    @PatchMapping("/me")
    UserResponse updateProfile(@RequestBody ProfileUpdate input, Authentication auth) {
        String name = input.displayName() == null ? "" : input.displayName().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tên hiển thị cần có từ 1 đến 100 ký tự.");
        }
        int updated = jdbc.update("""
                UPDATE users SET display_name = ?, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND is_active = true
                """, name, Long.parseLong(auth.getName()));
        if (updated == 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return me(auth);
    }

    @PatchMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@RequestBody PasswordChange input, Authentication auth) {
        String currentPassword = input.currentPassword() == null ? "" : input.currentPassword();
        String newPassword = input.newPassword() == null ? "" : input.newPassword();
        if (newPassword.length() < 8 || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới chưa đáp ứng yêu cầu.");
        }
        long userId = Long.parseLong(auth.getName());
        var hashes = jdbc.query("SELECT password_hash FROM users WHERE user_id = ? AND is_active = true",
                (row, index) -> row.getString("password_hash"), userId);
        if (hashes.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String currentHash = hashes.getFirst();
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(currentPassword, currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không chính xác.");
        }
        if (passwords.matches(newPassword, currentHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu mới cần khác mật khẩu hiện tại.");
        }
        int updated = jdbc.update("""
                UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND password_hash = ? AND is_active = true
                """, passwords.encode(newPassword), userId, currentHash);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mật khẩu vừa được thay đổi. Hãy tải lại trang và thử lại.");
        }
    }

    @DeleteMapping("/auth/sessions/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void signOut(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
    }

    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
