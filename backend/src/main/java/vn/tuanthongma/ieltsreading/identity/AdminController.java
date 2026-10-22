package vn.tuanthongma.ieltsreading.identity;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminController {
    @GetMapping("/api/admin/status")
    Map<String, String> status() {
        return Map.of("message", "Khu vực quản trị đã sẵn sàng.");
    }
}
