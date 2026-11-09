package vn.tuanthongma.ieltsreading.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "pdf-import.worker-enabled", havingValue = "true", matchIfMissing = true)
class PdfImportWorker {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TOTAL_LIMIT = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final PdfImportService imports;
    private final String cliJar;
    private final String hybridUrl;

    record JobSource(long id, UUID key, String scope, Integer partNumber) {}
    record Batch(List<Integer> originalPages, boolean ocr) {}
    record Block(String type, String text, JsonNode boundingBox) {}
    record Page(int number, String extractionMode, List<Block> blocks, List<String> warnings) {}
    record Review(int schemaVersion, String scope, Integer partNumber, List<Page> pages) {}

    PdfImportWorker(JdbcTemplate jdbc, PdfImportService imports,
                    @Value("${pdf-import.cli-jar:}") String cliJar,
                    @Value("${pdf-import.hybrid-url:http://localhost:5002}") String hybridUrl) {
        this.jdbc = jdbc;
        this.imports = imports;
        this.cliJar = cliJar;
        this.hybridUrl = hybridUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    void recoverInterrupted() {
        // One API instance owns the worker in this project; a restart cannot leave a job stuck.
        jdbc.update("""
                UPDATE pdf_import_jobs SET status = 'QUEUED', processed_pages = 0,
                    error_code = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND cancel_requested = false
                """);
        List<UUID> cancelled = jdbc.query("""
                UPDATE pdf_import_jobs SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
                WHERE status = 'RUNNING' AND cancel_requested = true
                RETURNING source_storage_key
                """, (row, index) -> row.getObject(1, UUID.class));
        for (UUID key : cancelled) deleteSource(key);
    }

    @Scheduled(fixedDelay = 1000)
    void runNext() {
        List<JobSource> claimed = jdbc.query("""
                UPDATE pdf_import_jobs SET status = 'RUNNING', updated_at = CURRENT_TIMESTAMP
                WHERE pdf_import_job_id = (
                    SELECT pdf_import_job_id FROM pdf_import_jobs
                    WHERE status = 'QUEUED' AND cancel_requested = false
                    ORDER BY created_at, pdf_import_job_id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                RETURNING pdf_import_job_id, source_storage_key, scope, part_number
                """, (row, index) -> {
                    short part = row.getShort("part_number");
                    return new JobSource(row.getLong("pdf_import_job_id"),
                            row.getObject("source_storage_key", UUID.class), row.getString("scope"),
                            row.wasNull() ? null : (int) part);
                });
        if (claimed.isEmpty()) return;
        JobSource job = claimed.getFirst();
        try {
            Review review = extract(job);
            if (cancelRequested(job.id())) {
                markCancelled(job.id());
                return;
            }
            jdbc.update("""
                    UPDATE pdf_import_jobs SET status = 'REVIEW_READY', review_data = ?::jsonb,
                        review_draft = NULL, processed_pages = page_count,
                        review_version = 1, updated_at = CURRENT_TIMESTAMP
                    WHERE pdf_import_job_id = ? AND status = 'RUNNING' AND cancel_requested = false
                    """, JSON.writeValueAsString(review), job.id());
        } catch (ImportFailure ex) {
            fail(job.id(), ex.code);
        } catch (Exception ex) {
            fail(job.id(), "EXTRACTION_FAILED");
        } finally {
            if ("CANCELLED".equals(jdbc.queryForObject(
                    "SELECT status FROM pdf_import_jobs WHERE pdf_import_job_id = ?", String.class, job.id())))
                deleteSource(job.key());
        }
    }

    @Scheduled(fixedDelay = 3600000)
    void expireOld() {
        jdbc.update("""
                UPDATE pdf_import_jobs SET status = 'EXPIRED', review_data = NULL, review_draft = NULL,
                    cancel_requested = true, updated_at = CURRENT_TIMESTAMP
                WHERE expires_at <= CURRENT_TIMESTAMP AND status <> 'EXPIRED'
                """);
        List<UUID> keys = jdbc.query("""
                SELECT source_storage_key FROM pdf_import_jobs WHERE status = 'EXPIRED'
                """, (row, index) -> row.getObject(1, UUID.class));
        for (UUID key : keys) deleteSource(key);
    }

    private Review extract(JobSource job) throws IOException, InterruptedException {
        if (cliJar.isBlank() || !Files.isRegularFile(Path.of(cliJar)))
            throw new ImportFailure("PARSER_UNAVAILABLE");
        Path source = imports.sourcePath(job.key());
        if (!Files.isRegularFile(source)) throw new ImportFailure("SOURCE_MISSING");
        Instant deadline = Instant.now().plus(TOTAL_LIMIT);
        Path temporary = Files.createTempDirectory("reading-pdf-import-");
        try (PDDocument document = Loader.loadPDF(source.toFile())) {
            List<Batch> batches = planBatches(document);
            List<Page> pages = new ArrayList<>();
            int processed = 0;
            for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
                if (cancelRequested(job.id())) break;
                Batch batch = batches.get(batchIndex);
                Path input = temporary.resolve("batch-" + batchIndex + ".pdf");
                Path output = temporary.resolve("out-" + batchIndex);
                Files.createDirectories(output);
                try (PDDocument subset = new PDDocument()) {
                    for (int originalPage : batch.originalPages())
                        subset.importPage(document.getPage(originalPage - 1));
                    subset.save(input.toFile());
                }
                runParser(input, output, batch.ocr(), deadline);
                Path json = output.resolve("batch-" + batchIndex + ".json");
                pages.addAll(readPages(json, batch));
                processed += batch.originalPages().size();
                jdbc.update("""
                        UPDATE pdf_import_jobs SET processed_pages = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE pdf_import_job_id = ? AND status = 'RUNNING'
                        """, processed, job.id());
            }
            return new Review(1, job.scope(), job.partNumber(), pages.stream()
                    .sorted(Comparator.comparingInt(Page::number)).toList());
        } finally {
            try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private static List<Batch> planBatches(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        List<Batch> result = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        Boolean currentOcr = null;
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            boolean ocr = stripper.getText(document).strip().length() < 20;
            if (currentOcr != null && (currentOcr != ocr || current.size() == 3)) {
                result.add(new Batch(List.copyOf(current), currentOcr));
                current.clear();
            }
            current.add(page);
            currentOcr = ocr;
        }
        if (!current.isEmpty()) result.add(new Batch(List.copyOf(current), currentOcr));
        return result;
    }

    private void runParser(Path input, Path output, boolean ocr, Instant deadline)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("java", "-Djava.awt.headless=true", "-jar", cliJar,
                input.toString(), "--output-dir", output.toString(), "--format", "json",
                "--image-output", "off", "--quiet"));
        if (ocr) command.addAll(List.of("--hybrid", "docling-fast", "--hybrid-mode", "full",
                "--hybrid-url", hybridUrl, "--hybrid-timeout", "180000"));
        Path log = output.resolve("parser.log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        long seconds = Math.min(240, Duration.between(Instant.now(), deadline).toSeconds());
        if (seconds <= 0 || !process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new ImportFailure("EXTRACTION_TIMEOUT");
        }
        if (process.exitValue() != 0)
            throw new ImportFailure(ocr ? "OCR_UNAVAILABLE" : "PARSER_FAILED");
    }

    private static List<Page> readPages(Path jsonPath, Batch batch) throws IOException {
        if (!Files.isRegularFile(jsonPath)) throw new ImportFailure("PARSER_OUTPUT_MISSING");
        JsonNode root = JSON.readTree(Files.readString(jsonPath));
        List<Page> pages = new ArrayList<>();
        for (int index = 0; index < batch.originalPages().size(); index++) {
            List<Block> blocks = new ArrayList<>();
            collect(root.get("kids"), index + 1, null, blocks);
            List<String> warnings = new ArrayList<>();
            if (blocks.isEmpty()) warnings.add("NO_TEXT_DETECTED");
            if (batch.ocr()) warnings.add("OCR_REVIEW_REQUIRED");
            pages.add(new Page(batch.originalPages().get(index), batch.ocr() ? "OCR" : "TEXT",
                    blocks, warnings));
        }
        return pages;
    }

    private static void collect(JsonNode children, int batchPage, Integer inheritedPage, List<Block> blocks) {
        if (children == null || !children.isArray()) return;
        for (JsonNode child : children) {
            JsonNode page = child.get("page number");
            Integer actualPage = page == null ? inheritedPage : page.asInt();
            if (actualPage != null && actualPage != batchPage) continue;
            JsonNode content = child.get("content");
            if (content != null && content.isTextual() && !content.asText().isBlank()) {
                JsonNode type = child.get("type");
                blocks.add(new Block(type == null ? "text" : type.asText(),
                        content.asText(), child.get("bounding box")));
            }
            collect(child.get("kids"), batchPage, actualPage, blocks);
            collect(child.get("rows"), batchPage, actualPage, blocks);
            collect(child.get("cells"), batchPage, actualPage, blocks);
            collect(child.get("list items"), batchPage, actualPage, blocks);
        }
    }

    private boolean cancelRequested(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT cancel_requested FROM pdf_import_jobs WHERE pdf_import_job_id = ?", Boolean.class, id));
    }

    private void markCancelled(long id) {
        jdbc.update("""
                UPDATE pdf_import_jobs SET status = 'CANCELLED', review_data = NULL, review_draft = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE pdf_import_job_id = ? AND status = 'RUNNING'
                """, id);
    }

    private void fail(long id, String errorCode) {
        jdbc.update("""
                UPDATE pdf_import_jobs SET status = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'FAILED' END,
                    error_code = CASE WHEN cancel_requested THEN NULL ELSE ? END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE pdf_import_job_id = ? AND status = 'RUNNING'
                """, errorCode, id);
    }

    private void deleteSource(UUID key) {
        try { Files.deleteIfExists(imports.sourcePath(key)); } catch (IOException ignored) { }
    }

    private static final class ImportFailure extends RuntimeException {
        private final String code;
        private ImportFailure(String code) { this.code = code; }
    }
}
