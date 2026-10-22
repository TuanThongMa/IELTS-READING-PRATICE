package vn.tuanthongma.ieltsreading;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_http_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class AuthHttpTest {
    @LocalServerPort
    private int port;

    @Test
    void registrationErrorsKeepTheirHttpStatus() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        URI csrfUri = URI.create("http://localhost:" + port + "/auth/csrf");
        var csrf = client.send(HttpRequest.newBuilder(csrfUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        String token = JsonPath.read(csrf.body(), "$.token");
        assertEquals(201, register(client, token, "test@example.com"));
        assertEquals(409, register(client, token, "TEST@example.com"));
        assertEquals(400, register(client, token, "name@example"));
        var me = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
    }

    private int register(HttpClient client, String token, String email) throws Exception {
        String body = """
                {"email":"%s","password":"sample-password-123","displayName":"Test"}
                """.formatted(email);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/register"))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
