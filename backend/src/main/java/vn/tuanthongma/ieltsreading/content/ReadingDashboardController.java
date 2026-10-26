package vn.tuanthongma.ieltsreading.content;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reading/dashboard")
class ReadingDashboardController {
    private static final ZoneId USER_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;

    ReadingDashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record DayActivity(LocalDate date, long submittedAttempts, long studySeconds) {}
    record Dashboard(int year, LocalDate today, List<DayActivity> calendar, List<DayActivity> lastSevenDays) {}
    private record Submission(OffsetDateTime submittedAt, int elapsedSeconds) {}

    @GetMapping
    @Transactional(readOnly = true)
    Dashboard get(Principal principal) {
        long userId = Long.parseLong(principal.getName());
        LocalDate today = LocalDate.now(USER_ZONE);
        LocalDate yearStart = today.withDayOfYear(1);
        LocalDate weekStart = today.minusDays(6);
        OffsetDateTime from = (weekStart.isBefore(yearStart) ? weekStart : yearStart).atStartOfDay(USER_ZONE).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(USER_ZONE).toOffsetDateTime();
        List<Submission> submissions = jdbc.query("""
                SELECT submitted_at, elapsed_seconds FROM quiz_attempts
                WHERE user_id = ? AND status = 'SUBMITTED' AND submitted_at >= ? AND submitted_at < ?
                UNION ALL
                SELECT submitted_at, elapsed_seconds FROM reading_test_attempts
                WHERE user_id = ? AND status = 'SUBMITTED' AND submitted_at >= ? AND submitted_at < ?
                """, (r, n) -> new Submission(r.getObject("submitted_at", OffsetDateTime.class), r.getInt("elapsed_seconds")),
                userId, from, to, userId, from, to);

        Map<LocalDate, long[]> totals = new HashMap<>();
        for (Submission submission : submissions) {
            if (submission.submittedAt() == null) continue;
            LocalDate date = submission.submittedAt().atZoneSameInstant(USER_ZONE).toLocalDate();
            long[] total = totals.computeIfAbsent(date, ignored -> new long[2]);
            total[0]++;
            total[1] += submission.elapsedSeconds();
        }

        List<DayActivity> calendar = IntStream.range(0, yearStart.lengthOfYear())
                .mapToObj(day -> activity(yearStart.plusDays(day), totals)).toList();
        List<DayActivity> lastSevenDays = IntStream.range(0, 7)
                .mapToObj(day -> activity(weekStart.plusDays(day), totals)).toList();
        return new Dashboard(today.getYear(), today, calendar, lastSevenDays);
    }

    private static DayActivity activity(LocalDate date, Map<LocalDate, long[]> totals) {
        long[] total = totals.getOrDefault(date, new long[2]);
        return new DayActivity(date, total[0], total[1]);
    }
}
