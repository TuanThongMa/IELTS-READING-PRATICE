package vn.tuanthongma.ieltsreading.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class PdfImportService {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Path storageDir;

    record Job(long id, String scope, Integer partNumber, String status, boolean cancelRequested, String filename,
               int pageCount, int processedPages, int reviewVersion, String errorCode,
               OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    PdfImportService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                     @Value("${pdf-import.storage-dir}") String storageDir) throws IOException {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(this.storageDir);
    }

    Job upload(MultipartFile file, String scope, Integer partNumber, Principal principal) {
        if (!("PASSAGE".equals(scope) && partNumber != null && partNumber >= 1 && partNumber <= 3)
                && !("FULL_TEST".equals(scope) && partNumber == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chọn phạm vi PDF và số passage hợp lệ.");
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Hãy chọn tệp PDF không rỗng.");
        if (file.getSize() > MAX_FILE_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "PDF không được vượt quá 20 MB.");
        if (!"application/pdf".equalsIgnoreCase(file.getContentType()))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Chỉ chấp nhận tệp PDF.");

        UUID key = UUID.randomUUID();
        Path target = sourcePath(key);
        boolean committed = false;
        try {
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target);
            }
            int pages = validatePdf(target);
            String filename = displayFilename(file.getOriginalFilename());
            Long id = transaction.execute(ignored -> {
                // The lock makes the queue bound hold even when two Admins upload at once.
                jdbc.execute("SELECT pg_advisory_xact_lock(20261103)");
                Integer active = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM pdf_import_jobs WHERE status IN ('QUEUED', 'RUNNING')
                        """, Integer.class);
                if (active != null && active >= 3)
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Hàng đợi OCR đã đầy. Hãy thử lại sau.");
                return jdbc.queryForObject("""
                        INSERT INTO pdf_import_jobs (created_by_user_id, scope, part_number,
                                                     source_storage_key, original_filename, page_count)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING pdf_import_job_id
                        """, Long.class, Long.parseLong(principal.getName()), scope, partNumber,
                        key, filename, pages);
            });
            if (id == null) throw new IllegalStateException("PDF import job was not created");
            committed = true;
            return get(id, principal);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF không đọc được hoặc đã được bảo vệ.");
        } finally {
            // A committed job owns its source. Failed uploads must not leave private files behind.
            if (!committed) deleteQuietly(target);
        }
    }

    List<Job> list(Principal principal) {
        return jdbc.query("""
                SELECT pdf_import_job_id, scope, part_number, status, cancel_requested, original_filename,
                       page_count, processed_pages, review_version, error_code, created_at, expires_at
                FROM pdf_import_jobs WHERE created_by_user_id = ?
                ORDER BY created_at DESC, pdf_import_job_id DESC LIMIT 20
                """, PdfImportService::mapJob, Long.parseLong(principal.getName()));
    }

    Job get(long id, Principal principal) {
        return jdbc.query("""
                SELECT pdf_import_job_id, scope, part_number, status, cancel_requested, original_filename,
                       page_count, processed_pages, review_version, error_code, created_at, expires_at
                FROM pdf_import_jobs WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                """, PdfImportService::mapJob, id, Long.parseLong(principal.getName()))
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    Path source(long id, Principal principal) {
        UUID key = jdbc.query("""
                SELECT source_storage_key FROM pdf_import_jobs
                WHERE pdf_import_job_id = ? AND created_by_user_id = ? AND status <> 'EXPIRED'
                """, (row, index) -> row.getObject(1, UUID.class), id, Long.parseLong(principal.getName()))
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path path = sourcePath(key);
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return path;
    }

    JsonNode review(long id, Principal principal) {
        String data = jdbc.query("""
                SELECT review_data::text FROM pdf_import_jobs
                WHERE pdf_import_job_id = ? AND created_by_user_id = ? AND status = 'REVIEW_READY'
                """, (row, index) -> row.getString(1), id, Long.parseLong(principal.getName()))
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return JSON.readTree(data);
    }

    Job retry(long id, Principal principal) {
        long owner = Long.parseLong(principal.getName());
        return transaction.execute(ignored -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(20261103)");
            Integer active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pdf_import_jobs WHERE status IN ('QUEUED', 'RUNNING')
                    """, Integer.class);
            if (active != null && active >= 3)
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Hàng đợi OCR đã đầy. Hãy thử lại sau.");
            int changed = jdbc.update("""
                    UPDATE pdf_import_jobs SET status = 'QUEUED', processed_pages = 0,
                        error_code = NULL, review_data = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                      AND status = 'FAILED' AND expires_at > CURRENT_TIMESTAMP
                    """, id, owner);
            if (changed == 0) {
                get(id, principal);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ có thể thử lại job lỗi chưa hết hạn.");
            }
            return get(id, principal);
        });
    }

    Job discard(long id, Principal principal) {
        long owner = Long.parseLong(principal.getName());
        Job job = get(id, principal);
        if ("DRAFT_SAVED".equals(job.status()) || "PUBLISHED".equals(job.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bản nhập đã được áp dụng.");
        int changed = jdbc.update("""
                UPDATE pdf_import_jobs
                SET cancel_requested = true,
                    status = CASE WHEN status = 'RUNNING' THEN status ELSE 'CANCELLED' END,
                    review_data = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                  AND status NOT IN ('DRAFT_SAVED', 'PUBLISHED', 'EXPIRED')
                """, id, owner);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Bản nhập không thể hủy.");
        Job updated = get(id, principal);
        if ("CANCELLED".equals(updated.status())) {
            UUID key = jdbc.queryForObject("""
                    SELECT source_storage_key FROM pdf_import_jobs
                    WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                    """, UUID.class, id, owner);
            if (key != null) deleteQuietly(sourcePath(key));
        }
        return updated;
    }

    Path sourcePath(UUID key) { return storageDir.resolve(key + ".pdf"); }

    private static int validatePdf(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            if (!Arrays.equals(input.readNBytes(PDF_HEADER.length), PDF_HEADER))
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tệp tải lên không phải PDF.");
        }
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            int count = document.getNumberOfPages();
            if (count < 1 || count > 25)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF cần có từ 1 đến 25 trang.");
            return count;
        }
    }

    private static String displayFilename(String original) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (base.isBlank()) base = "reading.pdf";
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static Job mapJob(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        short part = row.getShort("part_number");
        return new Job(row.getLong("pdf_import_job_id"), row.getString("scope"),
                row.wasNull() ? null : (int) part, row.getString("status"),
                row.getBoolean("cancel_requested"), row.getString("original_filename"), row.getInt("page_count"),
                row.getInt("processed_pages"), row.getInt("review_version"),
                row.getString("error_code"), row.getObject("created_at", OffsetDateTime.class),
                row.getObject("expires_at", OffsetDateTime.class));
    }

    private static void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }
}
