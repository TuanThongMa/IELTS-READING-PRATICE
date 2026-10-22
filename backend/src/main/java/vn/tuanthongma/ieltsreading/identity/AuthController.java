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
