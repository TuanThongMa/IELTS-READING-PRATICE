package vn.tuanthongma.ieltsreading.content;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class PdfImportReviewService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern PASSAGE = Pattern.compile("(?i)^\\s*reading\\s+passage\\s*([123])\\s*$");
    private static final Pattern QUESTIONS = Pattern.compile("(?i)^\\s*questions?\\s+\\d+\\b");
    private static final Pattern NUMBERED_QUESTION = Pattern.compile("(?m)^\\s*(40|[1-3]\\d|[1-9])[.)]\\s+(.{8,})");
    private static final Set<String> VARIANTS = Set.of(
            "TRUE_FALSE_NOT_GIVEN", "YES_NO_NOT_GIVEN", "MULTIPLE_CHOICE_SINGLE",
            "MULTIPLE_CHOICE_MULTIPLE", "SENTENCE_COMPLETION", "NOTE_COMPLETION",
            "SUMMARY_COMPLETION", "TABLE_COMPLETION", "FLOW_CHART_COMPLETION",
            "SHORT_ANSWER", "MATCHING_INFORMATION", "MATCHING_HEADINGS",
            "MATCHING_FEATURES", "MATCHING_SENTENCE_ENDINGS");
    private static final int MAX_DRAFT_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;

    record ReviewDocument(int version, JsonNode source, Draft draft) {}
    record Draft(List<PassageDraft> passages, List<QuestionDraft> questions,
                 List<AnswerDraft> answers, List<UnclassifiedBlock> unclassifiedBlocks) {}
    record PassageDraft(int partNumber, String title, String body,
                        List<Integer> sourcePages, List<String> warnings) {}
    record QuestionDraft(int number, String text, String taskVariant, Integer sourcePage) {}
    record AnswerDraft(int number, String value, boolean confirmed, Integer sourcePage) {}
    record UnclassifiedBlock(int pageNumber, int blockIndex, String text) {}
    record SaveRequest(int version, Draft draft) {}
    private record Stored(String sourceJson, String draftJson, int version,
                          String scope, Integer partNumber, int pageCount) {}
    private record BlockRef(int pageNumber, int blockIndex, String text) {}

    PdfImportReviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    ReviewDocument get(long id, Principal principal) {
        Stored stored = find(id, principal);
        JsonNode source = JSON.readTree(stored.sourceJson());
        Draft draft = stored.draftJson() == null ? recognize(source, stored)
                : JSON.readValue(stored.draftJson(), Draft.class);
        return new ReviewDocument(stored.version(), source, draft);
    }

    @Transactional
    ReviewDocument save(long id, SaveRequest request, Principal principal) {
        Stored stored = find(id, principal);
        if (request == null || request.draft() == null)
            throw bad("Bản rà soát không được để trống.");
        if (request.version() != stored.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bản rà soát đã thay đổi. Hãy tải lại trước khi lưu.");
        JsonNode source = JSON.readTree(stored.sourceJson());
        validate(request.draft(), stored, source);
        String json = JSON.writeValueAsString(request.draft());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_DRAFT_BYTES)
            throw bad("Bản rà soát vượt giới hạn 2 MB.");
        int changed = jdbc.update("""
                UPDATE pdf_import_jobs SET review_draft = ?::jsonb,
                    review_version = review_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                  AND status = 'REVIEW_READY' AND review_version = ?
                """, json, id, Long.parseLong(principal.getName()), request.version());
        if (changed == 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bản rà soát đã thay đổi. Hãy tải lại trước khi lưu.");
        return new ReviewDocument(request.version() + 1, source, request.draft());
    }

    private Stored find(long id, Principal principal) {
        return jdbc.query("""
                SELECT review_data::text, review_draft::text, review_version, scope, part_number, page_count
                FROM pdf_import_jobs
                WHERE pdf_import_job_id = ? AND created_by_user_id = ?
                  AND status = 'REVIEW_READY' AND review_data IS NOT NULL
                """, (row, ignored) -> {
                    short part = row.getShort(5);
                    return new Stored(row.getString(1), row.getString(2), row.getInt(3),
                            row.getString(4), row.wasNull() ? null : (int) part, row.getInt(6));
                }, id, Long.parseLong(principal.getName())).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Draft recognize(JsonNode source, Stored stored) {
        Map<Integer, List<BlockRef>> pageBlocks = blocks(source);
        List<BlockRef> ordered = pageBlocks.values().stream().flatMap(List::stream).toList();
        List<UnclassifiedBlock> unclassified = ordered.stream()
                .map(block -> new UnclassifiedBlock(block.pageNumber(), block.blockIndex(), block.text()))
                .toList();
        Map<Integer, PassageDraft> passages = new LinkedHashMap<>();
        Map<Integer, QuestionDraft> questions = new LinkedHashMap<>();
        int expected = "PASSAGE".equals(stored.scope()) ? stored.partNumber() : 0;
        boolean inQuestions = false;
        int activePart = 0;
        int bodyStart = -1;
        for (int i = 0; i < ordered.size(); i++) {
            BlockRef block = ordered.get(i);
            String text = block.text().strip();
            Matcher passage = PASSAGE.matcher(text);
            if (passage.find()) {
                int nextPart = Integer.parseInt(passage.group(1));
                if (activePart > 0 && bodyStart >= 0 && !passages.containsKey(activePart))
                    passages.put(activePart, uncertain(activePart));
                activePart = nextPart;
                bodyStart = i + 1;
                inQuestions = false;
                continue;
            }
            if (activePart > 0 && QUESTIONS.matcher(text).find()) {
                if (!passages.containsKey(activePart))
                    passages.put(activePart, bodyProposal(activePart, ordered, bodyStart, i));
                inQuestions = true;
            }
            if (inQuestions && (expected == 0 || expected == activePart)) {
                Matcher numbered = NUMBERED_QUESTION.matcher(block.text());
                while (numbered.find()) {
                    int number = Integer.parseInt(numbered.group(1));
                    questions.putIfAbsent(number, new QuestionDraft(number, numbered.group(2).strip(),
                            null, block.pageNumber()));
                }
            }
        }
        List<PassageDraft> passageList = new ArrayList<>();
        for (int part = 1; part <= 3; part++) {
            if (expected != 0 && expected != part) continue;
            passageList.add(passages.getOrDefault(part, uncertain(part)));
        }
        List<QuestionDraft> questionList = questions.values().stream()
                .sorted((a, b) -> Integer.compare(a.number(), b.number())).toList();
        List<AnswerDraft> answers = new ArrayList<>();
        if (expected == 0) {
            for (int number = 1; number <= 40; number++)
                answers.add(new AnswerDraft(number, "", false, null));
        } else {
            for (QuestionDraft question : questionList)
                answers.add(new AnswerDraft(question.number(), "", false, question.sourcePage()));
        }
        return new Draft(passageList, questionList, answers, unclassified);
    }

    private static PassageDraft bodyProposal(int part, List<BlockRef> blocks, int start, int end) {
        if (start < 0 || start >= end) return uncertain(part);
        StringBuilder body = new StringBuilder();
        List<Integer> pages = new ArrayList<>();
        for (int i = start; i < end; i++) {
            BlockRef block = blocks.get(i);
            String text = block.text().strip();
            if (text.isEmpty()) continue;
            if (!pages.contains(block.pageNumber())) pages.add(block.pageNumber());
            if (!body.isEmpty()) body.append("\n\n");
            body.append(text);
        }
        if (body.isEmpty()) return uncertain(part);
        // OCR layout can place headings and side notes among paragraphs. Admin verifies this proposal.
        return new PassageDraft(part, "", body.toString(), pages, List.of("REVIEW_EXTRACTED_TEXT"));
    }

    private static PassageDraft uncertain(int part) {
        return new PassageDraft(part, "", "", List.of(), List.of("PASSAGE_BOUNDARY_UNCERTAIN"));
    }

    private static Map<Integer, List<BlockRef>> blocks(JsonNode source) {
        Map<Integer, List<BlockRef>> result = new LinkedHashMap<>();
        JsonNode pages = source.get("pages");
        if (pages == null || !pages.isArray()) return result;
        for (JsonNode page : pages) {
            int number = page.path("number").asInt();
            List<BlockRef> refs = new ArrayList<>();
            JsonNode items = page.get("blocks");
            if (items != null && items.isArray()) {
                for (int index = 0; index < items.size(); index++)
                    refs.add(new BlockRef(number, index, items.get(index).path("text").asText("")));
            }
            result.put(number, refs);
        }
        return result;
    }

    private static void validate(Draft draft, Stored stored, JsonNode source) {
        int pageCount = stored.pageCount();
        if (draft.passages() == null || draft.questions() == null || draft.answers() == null
                || draft.unclassifiedBlocks() == null)
            throw bad("Bản rà soát thiếu danh sách passage, câu hỏi, đáp án hoặc nội dung chưa phân loại.");
        Set<Integer> parts = new HashSet<>();
        for (PassageDraft passage : draft.passages()) {
            if (passage == null || passage.partNumber() < 1 || passage.partNumber() > 3
                    || !parts.add(passage.partNumber())
                    || passage.sourcePages() == null || passage.sourcePages().size() > pageCount
                    || passage.warnings() == null || passage.warnings().size() > 20)
                throw bad("Danh sách passage không hợp lệ.");
            bounded(passage.title(), 300, "tiêu đề passage");
            bounded(passage.body(), 100_000, "nội dung passage");
            Set<Integer> seenPages = new HashSet<>();
            for (Integer page : passage.sourcePages()) {
                validPage(page, pageCount);
                if (!seenPages.add(page)) throw bad("Trang nguồn passage bị trùng.");
            }
            for (String warning : passage.warnings()) bounded(warning, 100, "cảnh báo passage");
        }
        if ("PASSAGE".equals(stored.scope())) {
            if (parts.size() != 1 || !parts.contains(stored.partNumber()))
                throw bad("Passage phải giữ đúng số đã chọn khi tải PDF.");
        } else if (parts.size() != 3 || !parts.equals(Set.of(1, 2, 3))) {
            throw bad("Full test cần ba passage khác nhau: 1, 2 và 3.");
        }
        if (draft.questions().size() > 40 || draft.answers().size() > 40)
            throw bad("Số câu hỏi hoặc đáp án vượt quá 40.");
        Set<Integer> questionNumbers = new HashSet<>();
        for (QuestionDraft question : draft.questions()) {
            if (question == null) throw bad("Câu hỏi không hợp lệ.");
            validNumber(question.number());
            if (!questionNumbers.add(question.number())) throw bad("Số câu hỏi bị trùng.");
            bounded(question.text(), 10_000, "nội dung câu hỏi");
            if (question.taskVariant() != null && !question.taskVariant().isBlank()
                    && !VARIANTS.contains(question.taskVariant())) throw bad("Dạng câu hỏi không được hỗ trợ.");
            if (question.sourcePage() != null) validPage(question.sourcePage(), pageCount);
        }
        Set<Integer> answerNumbers = new HashSet<>();
        for (AnswerDraft answer : draft.answers()) {
            if (answer == null) throw bad("Đáp án không hợp lệ.");
            validNumber(answer.number());
            if (!answerNumbers.add(answer.number())) throw bad("Số đáp án bị trùng.");
            bounded(answer.value(), 1_000, "nội dung đáp án");
            if (answer.confirmed() && answer.value().isBlank())
                throw bad("Đáp án đã xác nhận phải có nội dung.");
            if (answer.sourcePage() != null) validPage(answer.sourcePage(), pageCount);
        }
        if ("FULL_TEST".equals(stored.scope())) {
            if (answerNumbers.size() != 40) throw bad("Full test cần giữ đủ 40 ô đáp án để rà soát.");
        } else if (!answerNumbers.containsAll(questionNumbers)) {
            throw bad("Mỗi câu hỏi cần có một ô đáp án.");
        }
        Map<Integer, List<BlockRef>> sourceBlocks = blocks(source);
        if (draft.unclassifiedBlocks().size() > 2_000) throw bad("Quá nhiều nội dung chưa phân loại.");
        Set<String> blockKeys = new HashSet<>();
        for (UnclassifiedBlock block : draft.unclassifiedBlocks()) {
            if (block == null) throw bad("Nội dung chưa phân loại không hợp lệ.");
            validPage(block.pageNumber(), pageCount);
            List<BlockRef> refs = sourceBlocks.get(block.pageNumber());
            if (refs == null || block.blockIndex() < 0 || block.blockIndex() >= refs.size()
                    || !blockKeys.add(block.pageNumber() + ":" + block.blockIndex()))
                throw bad("Vị trí nội dung chưa phân loại không hợp lệ.");
            bounded(block.text(), 10_000, "nội dung chưa phân loại");
        }
    }

    private static void bounded(String text, int max, String field) {
        if (text == null || text.length() > max) throw bad("Độ dài " + field + " không hợp lệ.");
    }

    private static void validNumber(int number) {
        if (number < 1 || number > 40) throw bad("Số câu hỏi phải trong khoảng 1–40.");
    }

    private static void validPage(Integer page, int count) {
        if (page == null || page < 1 || page > count)
            throw bad("Trang nguồn phải trong phạm vi PDF.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
