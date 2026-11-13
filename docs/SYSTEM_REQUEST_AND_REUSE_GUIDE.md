# Sổ tay luồng request và tái sử dụng hệ thống IELTS Reading

**Mục đích.** Đây là tài liệu đọc code và tái sử dụng các cách xử lý của dự án. Khi muốn sửa một tính năng, hãy tìm endpoint ở mục tương ứng, theo đường đi từ React tới controller, SQL và migration, rồi kiểm tra lại bằng một request thành công và ít nhất một request lỗi. Tài liệu mô tả mã nguồn ở commit <code>0156fc3</code> trên nhánh <code>Main-code</code>, đối chiếu ngày 12/11/2026. Số dòng là mốc tìm nhanh và có thể đổi sau khi chỉnh code.

**Phạm vi.** Chỉ IELTS Academic Reading. Backend dùng Java 21, Spring Boot 4.1.1, Spring MVC, Spring Security session, JdbcTemplate, Flyway và PostgreSQL. Frontend dùng React 19, TypeScript, React Router và Vite. Dự án chưa dùng JPA, JWT hoặc Testcontainers. Các đoạn ghi “mẫu tái sử dụng” là hướng dẫn áp dụng tiếp, không phải tính năng đã có.

**Quy ước đường dẫn.** Trong tài liệu, <code>B/</code> là <code>backend/src/main/java/vn/tuanthongma/ieltsreading/</code>; <code>F/</code> là <code>frontend/src/</code>; <code>DB/</code> là <code>backend/src/main/resources/db/migration/</code>. Ví dụ <code>B/identity/AuthController.java:69</code> là method đăng nhập.

## 1. Bản đồ một request

Một thao tác ghi đi theo chuỗi sau:

~~~text
Form React
  -> F/api.ts lấy CSRF token cho method khác GET
  -> Vite proxy chuyển /auth, /me hoặc /api tới :8080
  -> Spring SecurityFilterChain xác thực session và kiểm quyền
  -> Controller nhận DTO record, kiểm input và owner
  -> JdbcTemplate chạy SQL với tham số ?
  -> PostgreSQL áp FK, UNIQUE, CHECK; transaction commit hoặc rollback
  -> HTTP status + JSON/empty body
  -> React đọc status, cập nhật state hoặc hiển thị lỗi
~~~

| Chặng | Vị trí | Việc cần kiểm tra khi tái sử dụng |
| --- | --- | --- |
| URL và auth UI | <code>F/App.tsx:109–153</code> | Route guard chỉ giúp UX; backend vẫn phải kiểm quyền. |
| Fetch và CSRF | <code>F/api.ts:1–18</code> | GET không lấy CSRF; POST/PUT/PATCH/DELETE lấy token trước. Multipart không tự đặt Content-Type. |
| Proxy local | <code>frontend/vite.config.ts:5</code> | Cookie vẫn được gửi cùng origin qua proxy. Khi deploy cần cấu hình reverse proxy tương đương. |
| Security | <code>B/identity/SecurityConfiguration.java:21–31</code> | Public chỉ có vài route; <code>/api/admin/**</code> cần ADMIN; còn lại cần đăng nhập. |
| Xử lý nghiệp vụ | Controller ở <code>B/identity/</code> hoặc <code>B/content/</code> | Nhiều controller hiện chứa cả SQL và nghiệp vụ; đọc method thật trước khi tách lớp. |
| Dữ liệu | <code>DB/V1…V25</code> | Flyway tạo hoặc thay schema; không sửa migration đã chạy trên môi trường có dữ liệu. |

**Trích lược từ code thật, frontend request helper** — <code>F/api.ts:1–18</code>:

~~~ts
export async function api(path: string, method = "GET", body?: object) {
  const headers = method === "GET" ? {} : await csrfHeaders();
  if (body) headers["Content-Type"] = "application/json";
  return fetch(path, {
    method, headers, credentials: "same-origin",
    body: body ? JSON.stringify(body) : undefined
  });
}
~~~

<code>api()</code> trả đối tượng <code>Response</code>, không tự ném lỗi khi HTTP là 400/401/409. Component phải kiểm <code>response.ok</code> hoặc <code>response.status</code>. Ví dụ <code>F/App.tsx:69–92</code> phân biệt email trùng 409 với dữ liệu đăng ký sai 400. Lỗi mạng đi vào <code>catch</code>. Với file PDF dùng <code>apiMultipart()</code>; trình duyệt tự gắn multipart boundary.

**Checklist cho request mới.** Xác định role, owner, trạng thái dữ liệu, DTO, giới hạn input, SQL có tham số, transaction, ràng buộc DB, status lỗi, frontend state và test. Không suy luận rằng route guard ở React đã bảo vệ dữ liệu server.

## 2. Identity và handle request đăng nhập

### 2.1 Bảng endpoint

| Request | Method và file | Xử lý chính | Kết quả hoặc lỗi |
| --- | --- | --- | --- |
| <code>GET /auth/csrf</code> | <code>AuthController.csrf:44</code> | Trả token và tên header. | 200 JSON |
| <code>POST /auth/register</code> | <code>AuthController.register:49</code> | Kiểm email/tên/mật khẩu, chuẩn hóa email, BCrypt, INSERT role LEARNER. | 201; 400 input; 409 email trùng |
| <code>POST /auth/sessions</code> | <code>AuthController.signIn:69</code> | Tìm account, BCrypt matches, tạo Authentication, lưu SecurityContext vào session. | 200 user; 401 thông tin chung |
| <code>GET /me</code> | <code>AuthController.me:97</code> | Đọc userId từ Authentication rồi truy vấn user active. | 200 user; 401 |
| <code>PATCH /me</code> | <code>AuthController.updateProfile:106</code> | Chỉ sửa tên hiển thị của user hiện tại. | 200 user; 400/401 |
| <code>PATCH /me/password</code> | <code>AuthController.changePassword:120</code> | So mật khẩu cũ và cập nhật có điều kiện theo hash cũ. | 204; 400/401/409 |
| <code>DELETE /auth/sessions/current</code> | <code>AuthController.signOut:149</code> | Xóa context, invalidate session. | 204 |

### 2.2 Đăng ký

<code>Credentials</code>, <code>Registration</code>, <code>UserResponse</code> ở <code>B/identity/AuthController.java:37–42</code> là Java record DTO, không phải bảng hay session. Frontend kiểm email và 72 byte để báo lỗi sớm; backend kiểm lại vì client có thể bị bỏ qua. Backend trim và <code>toLowerCase(Locale.ROOT)</code> email để ghi <code>email_normalized</code>. Ràng buộc UNIQUE ở <code>DB/V1__create_users.sql</code> mới giải quyết hai request đăng ký trùng đến cùng lúc. Bắt <code>DuplicateKeyException</code> trả 409. Mật khẩu thô không được log, hash bằng <code>PasswordEncoder</code> trước INSERT.

**Trích lược từ code thật, giới hạn theo byte** — <code>AuthController.java:55–65</code>:

~~~java
if (email.length() > 254 || !EMAIL.matcher(email).matches()
        || name.isEmpty() || name.length() > 100
        || password == null || password.length() < 8
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Thông tin đăng ký không hợp lệ.");
}
~~~

72 là **byte UTF-8**, không phải 72 ký tự. BCrypt có giới hạn input kiểu này; cần giữ cùng quy tắc ở đăng ký, đăng nhập và đổi mật khẩu. Email regex hiện đủ cho validation sản phẩm cơ bản, không xác minh quyền sở hữu email.

### 2.3 Đăng nhập, session và quyền

<code>signIn()</code> truy vấn bằng email đã chuẩn hóa, dùng <code>passwords.matches(raw, hash)</code>. Nếu email không tồn tại vẫn so với <code>dummyHash</code> để giảm khác biệt chi phí xử lý. Không hoạt động hoặc sai mật khẩu đều trả 401 chung. Khi đúng, code đổi session ID đang có, tạo <code>UsernamePasswordAuthenticationToken</code> với principal là chuỗi userId và authority <code>ROLE_LEARNER</code>/<code>ROLE_ADMIN</code>, rồi lưu <code>SecurityContext</code> bằng <code>HttpSessionSecurityContextRepository</code>.

**Trích lược từ code thật, lõi session** — <code>AuthController.java:87–94</code>:

~~~java
if (request.getSession(false) != null) request.changeSessionId();
var principal = new UsernamePasswordAuthenticationToken(
        Long.toString(account.id()), null,
        java.util.List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
SecurityContext context = SecurityContextHolder.createEmptyContext();
context.setAuthentication(principal);
SecurityContextHolder.setContext(context);
contexts.saveContext(context, request, response);
~~~

Cookie giữ ID session, không giữ password. Quyền nằm trong Authentication được khôi phục từ session. <code>SecurityConfiguration.java:24–29</code> cho phép route công khai, chặn <code>/api/admin/**</code> nếu không có ADMIN và đòi xác thực các route còn lại. <code>GET /me</code> đọc lại user hiện tại từ DB để frontend khôi phục trạng thái sau refresh. Route guard React ở <code>F/App.tsx</code> không thay được SecurityFilterChain.

<code>application.yaml</code> đặt cookie HttpOnly, SameSite=Lax; <code>SESSION_COOKIE_SECURE</code> cần bật khi chạy HTTPS. CSRF vẫn cần cho request ghi vì browser tự đính cookie session. Đăng xuất invalidate session. Đổi mật khẩu dùng <code>UPDATE users ... WHERE user_id = ? AND password_hash = ?</code>: nếu tab khác đã đổi hash, số dòng cập nhật bằng 0 và API trả 409. Code hiện **chưa tự thu hồi mọi session khác** sau đổi mật khẩu.

**Lưu ý tái sử dụng.** Đừng lưu record <code>Credentials</code> vào session, đừng trả <code>password_hash</code> trong JSON, đừng nhận role từ form đăng ký, đừng xem 401 là 403. Khi mở rộng endpoint, lọc theo <code>user_id = principal.getName()</code> trong SQL để bảo vệ tài nguyên của từng người.

## 3. Admin biên soạn passage và câu hỏi

### 3.1 Passage nháp và publish

| Request | Vị trí | Bất biến |
| --- | --- | --- |
| <code>GET /api/admin/passages</code>, <code>/{id}</code> | <code>B/content/AdminPassageController.java:28,37</code> | Admin đọc danh sách và chi tiết. |
| <code>POST /api/admin/passages</code> | <code>AdminPassageController.create:46</code> | Title, body, partNumber 1–3; tạo DRAFT, ghi created_by_user_id. |
| <code>PATCH /api/admin/passages/{id}</code> | <code>AdminPassageController.update:56</code> | Chỉ sửa DRAFT. |
| <code>GET .../{id}/publish-check</code> | <code>AdminPassageController.checkPublication:70</code> | Trả danh sách lỗi; không publish. |
| <code>POST .../{id}/publish</code> | <code>AdminPassageController.publish:78</code> | Khóa passage, kiểm lại toàn bộ rồi đổi PUBLISHED. |

<code>publish()</code> là request có transaction. Nó khóa hàng passage bằng <code>SELECT status ... FOR UPDATE</code>, gọi <code>publicationCheck()</code> để kiểm title/body và <code>AdminQuestionGroupController.publicationIssues()</code>. Nếu chưa đủ dữ liệu trả 422 cùng danh sách issue; nếu đạt, update trạng thái. Việc check lại khi publish quan trọng vì dữ liệu có thể đổi sau lần preview. Frontend tương ứng ở <code>F/features/admin/AdminPage.tsx:50–110</code>.

### 3.2 Nhóm câu hỏi là đơn vị ghi

<code>AdminQuestionGroupController</code> ở <code>B/content/AdminQuestionGroupController.java</code> nhận cả group, danh sách question, options/blank/candidates và <code>presentationTemplate</code> trong một <code>GroupInput</code>. <code>POST /api/admin/passages/{passageId}/question-groups</code> ở dòng 119; <code>PUT .../{groupId}</code> ở dòng 150; <code>DELETE .../{groupId}</code> ở dòng 210. Ba thao tác dùng <code>@Transactional</code> và chỉ áp dụng cho passage DRAFT. Frontend form ở <code>F/features/admin/QuestionGroups.tsx:292</code>.

| Họ dữ liệu | <code>task_variant</code> điển hình | Bảng đáp án |
| --- | --- | --- |
| OPTION | TRUE_FALSE_NOT_GIVEN, YES_NO_NOT_GIVEN, MULTIPLE_CHOICE_SINGLE, MULTIPLE_CHOICE_MULTIPLE | <code>option_questions</code>, <code>question_answer_options</code> |
| BLANK | SENTENCE, NOTE, SUMMARY, TABLE, FLOW_CHART_COMPLETION; SHORT_ANSWER | <code>blank_questions</code>, <code>blank_answer_variants</code> |
| MATCHING | INFORMATION, HEADINGS, FEATURES, SENTENCE_ENDINGS; SUMMARY dùng choice pool | <code>matching_questions</code>, <code>matching_question_answers</code> |

<code>SUMMARY_COMPLETION</code> có thể là TEXT_ENTRY hoặc SHARED_CHOICE_POOL; cần kiểm <code>answer_input_mode</code>, không suy dạng chỉ từ tên variant. <code>Diagram Label Completion</code> chưa có luồng nguồn ảnh nên chưa được hỗ trợ đầy đủ.

**Trích lược từ code thật, đánh số lại** — <code>AdminQuestionGroupController.java:244–254</code>:

~~~java
List<Long> ids = jdbc.queryForList("""
        SELECT q.passage_question_id FROM passage_questions q
        JOIN question_groups g ON g.question_group_id = q.question_group_id
        WHERE g.passage_id = ?
        ORDER BY g.display_order, q.question_number, q.passage_question_id
        """, Long.class, passageId);
for (int i = 0; i < ids.size(); i++)
    jdbc.update("UPDATE passage_questions SET question_number = ? WHERE passage_question_id = ?",
            1_000_000 + i, ids.get(i));
for (int i = 0; i < ids.size(); i++)
    jdbc.update("UPDATE passage_questions SET question_number = ? WHERE passage_question_id = ?",
            i + 1, ids.get(i));
~~~

Hai lượt UPDATE giữ UNIQUE khi thay thứ tự: trước tiên chuyển toàn bộ số vào vùng tạm, sau đó gán 1…n. ID câu hỏi không đổi khi số hiển thị đổi. Khi sửa group, code còn đưa số cũ của group lên vùng <code>3_000_000</code>, cập nhật/xóa/thêm câu và gọi <code>renumber()</code>. Đừng xóa một câu rồi tự chỉnh số bằng frontend; backend và DB là nguồn đúng.

Template trình bày là JSON trong <code>question_groups.presentation_template</code>, được đọc/ghi ở <code>B/content/PresentationTemplate.java</code>. Admin validator ở <code>AdminQuestionGroupController.java:470–523</code> yêu cầu mỗi <code>questionIndex</code> xuất hiện đúng một lần, theo thứ tự với đoạn văn. Một ô trống trong đoạn vẫn là một <code>passage_question</code> có ID/đáp án/số riêng. Bản learner đi qua <code>PresentationTemplateCodec.forLearner()</code> để bỏ <code>sourceText</code> nội bộ, tránh lộ đáp án gốc.

### 3.3 Full test 40 câu và khoảng số linh hoạt

<code>AdminReadingTestController.java:113–147</code> kiểm đúng ba passage không lặp, đúng <code>part_number</code> 1/2/3, đã PUBLISHED, số cục bộ liên tục từ 1 đến cuối và tổng đúng 40. Nó tự tính <code>question_start_number</code>/<code>question_end_number</code> rồi lưu vào <code>test_passages</code> ở dòng 168–175. Vì vậy 13/13/14, 14/13/13 hay 12/14/14 đều hợp lệ nếu tổng 40 và liên tục. Số toàn đề = <code>question_start_number + question_number - 1</code>. Kiểm lại lúc publish ở dòng 103–110. Frontend chọn passage ở <code>F/features/admin/ReadingTests.tsx</code>.

**Lưu ý tái sử dụng.** Ràng buộc một hàng trong PostgreSQL không thể tự chứng minh tổng 40 qua ba passage. Logic kiểm toàn bộ nằm ở backend; FK/UNIQUE/CHECK vẫn bảo vệ các bất biến cấp hàng. Nếu thêm đường ghi dữ liệu khác, phải tái dùng hoặc lặp lại kiểm composition tại điểm publish.

## 4. Learner đọc passage lẻ và lưu bài làm

### 4.1 Đọc dữ liệu mà không lộ đáp án

<code>GET /api/reading/passages</code> ở <code>B/content/ReadingPassageController.java:30</code> là public theo SecurityConfiguration; nó trả bài đã publish, số câu, dạng câu, tiến độ và attempt đã nộp gần nhất nếu có user. <code>GET /api/reading/passages/{id}</code> ở dòng 56 yêu cầu đăng nhập, đọc passage PUBLISHED, nhóm, câu, lựa chọn và template đã lọc. Query learner không select cờ <code>is_correct</code>, đáp án blank/matching hay explanation. Đáp án chỉ xuất hiện ở API result sau khi attempt là SUBMITTED. Frontend chọn bài ở <code>F/features/reading/ReadingPage.tsx:306</code>, tải chi tiết ở dòng 428.

### 4.2 Vòng đời một attempt

| Request | Vị trí | Trạng thái và dữ liệu |
| --- | --- | --- |
| <code>POST /api/reading/passages/{id}/attempts</code> | <code>PracticeAttemptController.start:26</code> | Bắt đầu hoặc nối tiếp IN_PROGRESS/PAUSED; mode PRACTICE hoặc REAL_TEST. |
| <code>GET /api/reading/attempts/{id}</code> | <code>PracticeAttemptController.get:64</code> | Đọc attempt thuộc chính user và câu trả lời đã lưu. |
| <code>PUT .../answers/{questionId}</code> | <code>PracticeAttemptController.save:99</code> | Kiểm owner, status, câu thuộc passage; lưu hoặc xóa đáp án. |
| <code>PUT .../heartbeat</code> | <code>PracticeAttemptController.heartbeat:69</code> | Lưu <code>elapsedSeconds</code> do client báo. |
| <code>PUT .../pause</code> | <code>PracticeAttemptController.pause:50</code> | Tạm dừng REAL_TEST passage, lưu thời gian. |
| <code>POST .../submit</code> | <code>PracticeAttemptController.submit:81</code> | Chuyển SUBMITTED, bỏ active marker, ghi submitted_at. |
| <code>GET .../result</code> | <code>PracticeResultController.result:42</code> | Chỉ sau nộp mới trả answer key, explanation, correct/incorrect/skipped. |

<code>start()</code> khóa row <code>users</code> bằng <code>FOR UPDATE</code> trước khi tìm hoặc tạo attempt; <code>active_marker</code> và UNIQUE trong schema ngăn hai lượt active cho cùng user/passage. Sau khi nộp, làm lại tạo ID attempt khác. Câu trả lời và highlight thuộc ID đó nên không bị lẫn phiên cũ. <code>readAttempt()</code> luôn lọc <code>user_id</code> từ principal; không tin ID do URL gửi lên.

<code>save()</code> khóa attempt, yêu cầu IN_PROGRESS và kiểm question thuộc passage của attempt. Với TEXT_ENTRY, backend giữ <code>submitted_raw_value</code> để hiển thị lại, đồng thời lưu <code>submitted_value</code> đã NFC, trim, gộp khoảng trắng, lower-case để chấm. Với choice, nó kiểm label thực sự nằm trong options/candidate của câu, không chỉ kiểm A/B/C chung chung. Giá trị trắng xóa đáp án cũ. <code>UNIQUE(quiz_attempt_id, passage_question_id)</code> giữ một đáp án cuối cùng mỗi câu.

**Trích lược từ code thật, kiểm câu thuộc attempt** — <code>PracticeAttemptController.java:104–121</code>:

~~~java
if (!"IN_PROGRESS".equals(attempt.status()))
    throw new ResponseStatusException(HttpStatus.CONFLICT);
List<QuestionMode> modes = jdbc.query(
    "SELECT g.task_variant, g.answer_input_mode, g.word_limit_mode " +
    "FROM passage_questions q JOIN question_groups g ON g.question_group_id = q.question_group_id " +
    "WHERE q.passage_question_id = ? AND g.passage_id = ?",
    (r, n) -> new QuestionMode(r.getString(1), r.getString(2), r.getString(3)),
    questionId, attempt.passageId());
if (modes.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
~~~

Đây là quy tắc ownership ở hai tầng: attempt phải thuộc user và question phải thuộc passage của attempt. Nếu chỉ kiểm question ID tồn tại, user có thể ghi đáp án của bài khác vào attempt này.

### 4.3 Completion, đồng hồ, nộp và chấm

<code>AnswerTextPolicy.java:13–45</code> đếm token theo word/number. ONE_WORD từ chối <code>New York</code>; TWO_WORDS_AND_ONE_NUMBER cho tối đa hai từ và một số. Từ nối bằng gạch ngang được tính như một word trong policy hiện tại; ký tự khác ngoài phạm vi cho phép sẽ báo 400. Frontend có bản kiểm sớm ở <code>F/features/reading/answerTextPolicy.ts</code>, nhưng backend luôn kiểm lại. Đáp án biến thể được lưu ở <code>blank_answer_variants</code>; <code>Vietnam</code> và <code>vietnam</code> cho cùng kết quả sau normalize, nhưng hai từ đồng nghĩa khác nhau phải do admin khai báo variant.

<code>F/features/reading/ReadingPage.tsx:445–449</code> hiển thị đồng hồ mỗi giây và gửi checkpoint 15 giây. <code>PracticeAttemptController.heartbeat()</code> dùng <code>GREATEST(elapsed_seconds, ?)</code> để không đi lùi. Đây là **thời gian do client báo**, có giới hạn 0…604800 giây ở backend, không phải clock server có thể chứng minh tuyệt đối. Nếu tắt đột ngột, thời gian có thể hụt từ checkpoint cuối. Passage REAL_TEST hiện không tự nộp ở 60 phút tại backend; không nên mô tả như full test.

<code>PracticeResultController.result()</code> chỉ chạy cho SUBMITTED. Nó gom option, matching, blank primary/variants, so đáp án theo từng family, đếm correct/incorrect/skipped và trả breakdown. Kết quả passage là raw score, **không quy band IELTS**. Chấm hiện tính từ key của nội dung published khi gọi result, vì hệ thống chưa snapshot toàn bộ câu hỏi/đáp án tại thời điểm nộp; giữ PUBLISHED bất biến là quan trọng. <code>GET /api/reading/history?passageId=&page=</code> ở <code>PracticeHistoryController.java:27</code> lọc lịch sử theo passage tùy chọn và lấy 21 hàng cho page size 20 để xác định <code>hasMore</code>.

## 5. Full test và đồng hồ do server tính

<code>ReadingTestPracticeController.java</code> dùng bảng <code>reading_test_attempts</code>/<code>reading_test_answers</code>, độc lập với attempt passage lẻ. Các request chính: list <code>GET /api/reading/tests</code> dòng 54, detail <code>GET /{testId}</code> dòng 71, start <code>POST /{testId}/attempts</code> dòng 85, pause dòng 114, heartbeat dòng 127, save answer dòng 137, submit dòng 173, result dòng 185. Frontend ở <code>F/features/reading/ReadingTestPage.tsx</code>.

<code>start()</code> chỉ nhận test PUBLISHED, khóa user trước khi tìm/tạo attempt và tiếp tục lượt đang mở. Khi resume PAUSED, nó đặt <code>clock_started_at = CURRENT_TIMESTAMP</code>. <code>lockedAttempt()</code> ở dòng 412 lấy row bằng <code>FOR UPDATE</code> với userId, bảo vệ heartbeat, lưu answer, pause và submit khỏi ghi chồng.

**Trích lược từ code thật, lõi tính giờ** — <code>ReadingTestPracticeController.java:390–408</code>:

~~~java
Instant now = Instant.now();
Instant started = current.clockStartedAt() == null
        ? now : current.clockStartedAt().toInstant();
long delta = Math.max(0, Duration.between(started, now).getSeconds());
if (recovered) delta = Math.min(15, delta);
long total = (long) current.elapsedSeconds() + delta;
boolean expired = "REAL_TEST".equals(current.mode()) && total >= DURATION_SECONDS;
~~~

Mỗi heartbeat/save/pause/submit gọi <code>accrue()</code>: cộng chênh lệch giữa mốc server và hiện tại vào <code>elapsed_seconds</code>, sau đó đặt lại mốc. Khi request phục hồi sau đứt kết nối với <code>recovered=true</code>, phần chưa checkpoint được giới hạn 15 giây. REAL_TEST full test tự kết thúc tại 3600 giây ở request xử lý tiếp theo; PRACTICE không giới hạn 60 phút. Frontend hiển thị mỗi giây, heartbeat mỗi 15 giây, gửi lại khi online và cố gọi submit khi đồng hồ đạt 60 phút (<code>ReadingTestPage.tsx:41–47</code>).

<code>saveAnswer()</code> dùng <code>@Transactional(noRollbackFor = ResponseStatusException.class)</code>. Nếu <code>accrue()</code> phát hiện hết giờ, nó finalize attempt rồi trả 409 cho câu trả lời đến muộn. Không rollback exception này để trạng thái SUBMITTED và điểm vừa ghi được commit. Đây là ngoại lệ có chủ ý; không sao chép annotation này cho mọi API.

<code>scoreSummary()</code> ở dòng 207–273 chấm 40 câu: BLANK đối chiếu primary/variants đã normalize, matching/option so label, MULTIPLE_CHOICE_MULTIPLE chỉ cộng điểm khi label đúng và chưa được tính trong nhóm. <code>finalizeAttempt()</code> ở dòng 275–285 ghi <code>total_answered</code>, <code>total_correct</code>, <code>academic_band_table_id</code>, <code>band_score</code> và <code>band_result_label</code>. <code>AcademicBandService.forRawScore()</code> chọn bảng đang hiệu lực và range bao raw score. Migration <code>V16</code> định nghĩa bảng quy đổi nửa band; đây là bảng hướng dẫn luyện tập, không cam kết chính xác cho mọi đề thi thực tế.

**Snapshot đã có:** điểm và phiên bản bảng band được lưu vào attempt lúc nộp. **Snapshot chưa có:** toàn bộ body passage, câu hỏi và đáp án đúng. Nếu dự án sau cho sửa PUBLISHED, phải thiết kế version/snapshot nội dung trước. Result API chỉ trả dữ liệu review khi attempt đã SUBMITTED và owner đúng. Số toàn đề được tính bằng offset trong <code>test_passages</code>, không lưu số 1…40 riêng trên từng question.

## 6. Từ điển dùng chung và sổ từ vựng cá nhân

### 6.1 Tra từ qua backend

<code>GET /api/vocabulary/lookup?word=...</code> ở <code>VocabularyController.java:49</code> từ chối nếu user có REAL_TEST đang IN_PROGRESS, rồi gọi <code>DictionaryLookupService.lookup()</code> ở dòng 56. Từ được NFC, trim, lower-case; backend đọc <code>vocabulary_entries</code> và alias trước. Cache đủ thì trả DB. Nếu thiếu hoặc chưa nạp example và có API key, <code>RestClient</code> gọi Merriam-Webster Learner’s Dictionary qua HTTPS. Key từ <code>DICTIONARY_MW_API_KEY</code> chỉ nằm backend, không đưa cho React.

**Trích lược từ code thật, lọc provider suggestion** — <code>DictionaryLookupService.java:115–122</code>:

~~~java
for (ProviderEntry entry : entries)
    if (normalize(entry.word()).equals(term)) matches.add(entry);
if (isSingleWord(term)) for (ProviderEntry entry : entries) {
    if (!normalize(entry.word()).equals(term) && isSingleWord(entry.word())
            && entry.stems().stream().anyMatch(stem -> normalize(stem).equals(term)))
        matches.add(entry);
}
~~~

Provider có thể gợi ý headword khác; code ưu tiên khớp chính xác, sau đó chỉ thêm stem một từ phù hợp. Không lấy toàn bộ suggestion làm nghĩa của từ đang tra. Khi ghi cache, <code>save()</code> ở dòng 125 dùng <code>ON CONFLICT (language_code, normalized_word) DO NOTHING</code>, rồi <code>SELECT ... FOR UPDATE</code> để nhiều user tra cùng từ vẫn dùng một entry chung. Entry → sense → meaning/phonetic/audio/related words; <code>vocabulary_entry_search_terms</code> lưu alias. Query đọc lại sense dùng JOIN gom hàng, không gọi một SQL cho mỗi nghĩa. Nếu provider lỗi mà đã có cache, API trả cache; không có cache thì trả lỗi dịch vụ.

### 6.2 Lưu nghĩa đã chọn, danh mục và trạng thái

| Request | Vị trí | Ghi chú |
| --- | --- | --- |
| <code>GET/POST /api/vocabulary/categories</code> | <code>VocabularyController.java:55,67</code> | Danh mục riêng user; tên normalized UNIQUE. |
| <code>DELETE .../categories/{id}</code> | <code>VocabularyController.java:85</code> | WHERE category_id và user_id. |
| <code>GET /api/vocabulary-notes</code> | <code>VocabularyController.java:92</code> | Lọc category/status/query, giới hạn 200. |
| <code>GET .../summary</code>, <code>/recent</code> | <code>VocabularyController.java:99,110</code> | Tổng toàn bộ sổ và năm từ gần nhất trên dashboard. |
| <code>POST /api/vocabulary-notes</code> | <code>VocabularyController.createNote:115</code> | Lưu sense, ngữ cảnh, passage tùy chọn và 0…n category. |
| <code>PATCH .../{noteId}</code> | <code>VocabularyController.updateNote:158</code> | Sửa selectedText/context/customMeaning, không đổi sense. |
| <code>PATCH .../{noteId}/status</code> | <code>VocabularyController.changeStatus:211</code> | NEW, LEARNING, MASTERED. |
| <code>DELETE .../{noteId}</code> | <code>VocabularyController.deleteNote:220</code> | Chỉ xóa note thuộc user. |

<code>createNote()</code> bắt buộc <code>senseId</code> đã có từ cache từ điển, kiểm passage PUBLISHED nếu có và từng category thuộc user. Category có thể rỗng: từ vẫn nằm trong sổ chung. Nó tìm occurrence theo user + sense + passage/null + context, trả note cũ nếu đã lưu và chỉ thêm liên kết category. <code>DB/V20</code> có UNIQUE index theo tổ hợp đó với <code>md5(context)</code> để bảo vệ khi request cạnh tranh. Hai ngữ cảnh khác nhau của cùng từ vẫn là hai note hợp lệ. <code>selected_definition</code> là nghĩa đã chọn tại lúc lưu; phonetic/audio đọc qua sense nên đúng phát âm nghĩa được chọn.

<code>customMeaning</code> mới mâu thuẫn với note cũ trả 409 và yêu cầu sửa note cũ, tránh ghi đè ngầm. <code>findNotes()</code> ở dòng 227 gắn các filter SQL bằng nhánh code cố định và bind giá trị qua <code>?</code>; đừng ghép input người dùng thành SQL. Frontend sổ ở <code>F/features/vocabulary/VocabularyPage.tsx</code>. Tổng sổ lấy <code>/summary</code>, độc lập với category đang chọn.

### 6.3 Ngữ cảnh và highlight

Khi tra từ bên ngoài bài đọc, UI có thể điền example của sense vào context nếu provider cung cấp; người dùng được để trống hoặc sửa. Khi chọn chữ trong passage, <code>F/features/reading/readingContext.ts</code> ưu tiên lấy câu tiếng Anh chứa vùng chọn bằng <code>Intl.Segmenter</code> nếu câu không quá 80 từ và 4000 ký tự; nếu không ổn thì lấy tối đa 10 từ trước và 10 từ sau trong cùng đoạn. Đây là thuật toán frontend, không phải NLP hoàn hảo; backend hiện chỉ kiểm độ dài context, chưa đối chiếu nó với body.

<code>F/features/reading/ReadingPage.tsx:51–60</code> điều khiển phím H/T; <code>ReadingPane</code> lấy vùng chọn DOM, tính offset theo body và gọi lookup/save. <code>ReadingHighlightController.java:29–95</code> có GET/POST/DELETE theo <code>passageId</code> cộng <code>attemptType=PASSAGE|FULL_TEST</code> và <code>attemptId</code>. Server kiểm owner, passage nằm trong attempt, status IN_PROGRESS khi sửa, offset khớp đúng substring UTF-16 của body và không chồng vùng khác. Highlight được lưu trong <code>reading_highlights</code> với FK tới đúng một loại attempt; lần làm mới không mang highlight lần cũ, còn review lần cũ vẫn đọc được. Migration V24/V25 xử lý dữ liệu highlight cũ khó xác định attempt; không tự gán bừa khi mơ hồ.

**Lưu ý tái sử dụng.** Cùng một từ có thể có nhiều nghĩa và audio. Không lưu note chỉ bằng chuỗi word nếu sản phẩm cần nhớ nghĩa đã chọn; dùng <code>vocabulary_sense_id</code>. Không dùng cùng cơ chế cache chung để lưu dữ liệu riêng người dùng. Chức năng tra từ bị chặn cả UI lẫn API khi có REAL_TEST active.

## 7. Dashboard, lịch sử và đọc dữ liệu tổng hợp

### 7.1 Dashboard chỉ tính bài đã nộp

<code>ReadingDashboardController.java:29</code> phục vụ <code>GET /api/reading/dashboard</code>. Controller dùng principal từ session, lấy bài đã <code>SUBMITTED</code> ở cả <code>quiz_attempts</code> và <code>reading_test_attempts</code>. Hai nguồn được gộp bằng <code>UNION ALL</code>; giới hạn thời gian lấy theo khoảng ngày, rồi quy <code>submitted_at</code> về <code>Asia/Ho_Chi_Minh</code> để xác định ô lịch. Một ngày xanh nghĩa là có ít nhất một bài đã nộp, không phải chỉ mở bài hoặc lưu nháp. Thời gian học là tổng <code>elapsed_seconds</code> của các lượt nộp trong ngày; đây là thời gian làm bài được lưu, không phải tổng thời gian mở web.

~~~sql
SELECT submitted_at, elapsed_seconds FROM quiz_attempts
WHERE user_id = ? AND status = 'SUBMITTED'
  AND submitted_at >= ? AND submitted_at < ?
UNION ALL
SELECT submitted_at, elapsed_seconds FROM reading_test_attempts
WHERE user_id = ? AND status = 'SUBMITTED'
  AND submitted_at >= ? AND submitted_at < ?
~~~

Frontend ở <code>F/features/home/ReadingDashboard.tsx</code> dựng lịch năm, bảng bảy ngày và lấy <code>/api/vocabulary-notes/recent</code> cho danh sách từ mới lưu. Các số liệu vocab không được đưa vào query bài đã nộp.

### 7.2 Lịch sử từng passage và lịch sử gần nhất

<code>PracticeHistoryController.java:27</code> phục vụ <code>GET /api/reading/history</code>. Tham số passage tùy chọn lọc đúng một bài; không truyền thì xem các lượt gần nhất trên nhiều bài. SQL sắp theo ngày nộp và ID giảm dần, dùng <code>LIMIT/OFFSET</code>, mỗi trang tối đa 20 lượt và lấy thêm một bản ghi để biết còn trang sau. <code>PracticeResultController.java:42</code> lấy kết quả của một lượt <code>SUBMITTED</code> thuộc user, chấm từ đáp án đã publish và nhóm loại câu hỏi.

**Điểm phỏng vấn.** Phân trang không bảo đảm query đã tối ưu. <code>PracticeHistoryController.java:42</code> hiện gọi <code>results.result()</code> cho từng lượt trong trang, tức một trang 20 lượt có thể phát sinh nhiều query chấm kết quả. Khi dữ liệu lớn, cần cân nhắc lưu snapshot điểm lúc nộp, hoặc batch aggregate và đo bằng log SQL/EXPLAIN. Đây là giới hạn hiện tại, không nên mô tả là đã tối ưu N+1.

## 8. PDF import: từ upload đến bản review

<code>F/features/admin/PdfImports.tsx</code> là màn hình admin. Controller ở <code>B/content/AdminPdfImportController.java</code>, base path <code>/api/admin/pdf-imports</code>; quyền ADMIN được áp qua <code>SecurityConfiguration</code>. Đây là công cụ hỗ trợ bóc tách và sửa dữ liệu PDF, không phải pipeline tự publish.

| Request | Xử lý | Kết quả |
| --- | --- | --- |
| <code>POST /api/admin/pdf-imports</code> | <code>PdfImportService.upload:44</code> | <code>202 Accepted</code> + <code>Location</code> của job. |
| <code>GET /api/admin/pdf-imports</code>, <code>/{id}</code> | <code>AdminPdfImportController.java:45,50</code> | Danh sách, trạng thái và tiến độ. |
| <code>GET /{id}/source</code> | <code>AdminPdfImportController.java:55</code> | PDF gốc, <code>application/pdf</code>, <code>no-store</code>. |
| <code>GET/PUT /{id}/review</code> | <code>PdfImportReviewService.java:51,60</code> | Bản đọc được và bản admin đã sửa. |
| <code>POST /{id}/retry</code>, <code>DELETE /{id}</code> | <code>AdminPdfImportController.java:78,84</code> | Thử lại hoặc hủy job. |

Upload kiểm scope <code>PASSAGE</code> hoặc <code>FULL_TEST</code>; nếu passage phải có part 1–3. File không rỗng, tối đa 20 MB, MIME/header PDF hợp lệ, PDFBox đọc được và 1–25 trang. Server đặt tên file bằng UUID, lưu metadata vào <code>pdf_import_jobs</code>. Transaction dùng advisory lock để hạn chế hàng đợi đồng thời. Đừng tin tên file, MIME do client gửi, hoặc cho đường dẫn do client chọn.

<code>PdfImportWorker.java:67</code> chạy theo lịch mỗi giây, claim một job bằng <code>FOR UPDATE SKIP LOCKED</code> nên worker khác không cùng xử lý job đó. PDFTextStripper thử lớp chữ; trang ít chữ đi qua OpenDataLoader PDF CLI và dịch vụ OCR hybrid. Worker cập nhật trang đã xử lý, lưu <code>review_data</code> dạng JSONB rồi chuyển <code>REVIEW_READY</code>; lỗi thành <code>FAILED</code>, hủy thành <code>CANCELLED</code>. Job quá hạn được dọn theo lịch giờ ở dòng 108. Gọi công cụ ngoài phải có timeout và giới hạn tài nguyên; đọc progress/diagnostic trong job trước khi thử lại.

<code>PdfImportReviewService.save:60</code> cho admin sửa bản review, giới hạn JSON 2 MB và kiểm phiên bản. SQL chỉ ghi nếu <code>review_version</code> vẫn bằng bản client đã đọc:

~~~sql
UPDATE pdf_import_jobs
SET review_draft = ?::jsonb,
    review_version = review_version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE pdf_import_job_id = ?
  AND status = 'REVIEW_READY'
  AND review_version = ?
~~~

Nếu không có dòng nào được update, server trả <code>409 Conflict</code>, yêu cầu tải lại; đây là optimistic locking ở tầng SQL. Bản review hiện **chưa có endpoint chuyển thành passage/nhóm câu hỏi có thể publish**. Khi tái sử dụng, phải thiết kế riêng bước ánh xạ review → draft, validate số câu/đáp án và để admin xác nhận; không gọi job <code>REVIEW_READY</code> là đã nhập đề.

## 9. PostgreSQL và Flyway: nơi dữ liệu thực sự được giữ

Flyway chạy các file <code>B/src/main/resources/db/migration/V*__*.sql</code> theo số phiên bản khi backend khởi động. JDBC trong controller/service dùng SQL tường minh, không dựa vào JPA entity tự tạo bảng. Không sửa nội dung migration đã chạy; thay cấu trúc bằng một migration V mới, review dữ liệu cũ và constraint trước khi deploy.

| Phiên bản | Dữ liệu/chính sách tạo ra |
| --- | --- |
| V1–V3 | <code>users</code>, passage, nhóm câu hỏi, câu hỏi và option; email normalized UNIQUE. |
| V4–V6 | Chuẩn hóa số câu nháp; completion blank và matching. |
| V7–V10 | Attempt/answer passage, full test/ba passage, test attempt/answer, thời gian và index dashboard. |
| V11–V12 | Vocabulary cache, danh mục, note, highlight; lưu sense cụ thể đã chọn. |
| V13–V15 | PRACTICE/REAL_TEST, pause và đồng hồ full test do server tính. |
| V16–V18 | Band Academic versioned và constraint; đáp án tối đa ba từ. |
| V19–V20 | Note không cần context; loại trùng occurrence và unique index. |
| V21–V23 | PDF import job/review draft; làm mới example và search term từ điển. |
| V24–V25 | Highlight gắn với lượt làm; dữ liệu legacy mơ hồ không gán bừa. |

Các quan hệ quan trọng: <code>users</code> 1–n attempts/categories/notes; passage 1–n question groups 1–n questions; full test gồm đúng ba passage ở thứ tự 1–3; mỗi answer thuộc một attempt và question; <code>vocabulary_entries</code> là cache dùng chung, <code>vocabulary_notes</code> thuộc user và trỏ vào sense đã chọn. Điều kiện UNIQUE/FK/CHECK ở DB là lớp bảo vệ cuối khi hai request chạy cạnh tranh; validation Java giải thích lỗi cho người dùng nhưng không thay được constraint.

**Điểm phỏng vấn.** <code>FOR UPDATE</code> khóa hàng khi sửa cấu trúc câu hỏi hoặc chốt attempt; <code>ON CONFLICT</code> xử lý cạnh tranh cache; <code>review_version</code> là optimistic locking cho bản sửa PDF. Ba kỹ thuật giải quyết các tình huống khác nhau. Index hữu ích khi đi cùng điều kiện <code>WHERE</code>, thứ tự sort và số dòng thực tế; dùng <code>EXPLAIN (ANALYZE, BUFFERS)</code> trên PostgreSQL để xác nhận, thay vì đoán qua số index.

## 10. Handle lỗi, transaction và ranh giới tin cậy

| Tình huống | Nơi xử lý | Phản hồi/ý nghĩa |
| --- | --- | --- |
| Chưa đăng nhập hoặc thiếu quyền | <code>SecurityConfiguration.java</code>, principal helper | 401/403; không tin <code>userId</code> từ body. |
| Input sai định dạng/quy tắc | Controller/service tương ứng, <code>AnswerTextPolicy</code> | 400; frontend đọc message và hiển thị gần form. |
| Passage/question/attempt không tồn tại hoặc không thuộc user | Query có <code>user_id</code>; controller | 404 hoặc 403 theo từng route; tránh trả dữ liệu người khác. |
| Bản nháp chưa đủ để publish | <code>AdminPassageController.publishCheck/publish</code> | 422 kèm danh sách lỗi cụ thể. |
| Email/occurrence đã tồn tại hoặc phiên bản review cũ | UNIQUE/conditional UPDATE | 409 hoặc trả note/cache cũ theo chủ đích. |
| Provider từ điển/OCR lỗi | <code>DictionaryLookupService</code>, <code>PdfImportWorker</code> | Fallback cache nếu có; PDF job FAILED có diagnostic. |
| Màn hình React lỗi render | <code>F/App.tsx</code> error boundary | Trang lỗi có nút tải lại, không thay thế kiểm thử nguyên nhân. |

Các mutation nhiều bước dùng <code>@Transactional</code>: tạo nhóm/câu/option và đánh số, nộp bài/chấm/đóng attempt, lưu note/category, PDF review. Nếu một bước lỗi, giao dịch rollback để không lưu nửa nhóm hoặc nửa bài. Riêng <code>ReadingTestPracticeController.saveAnswer:137</code> có <code>noRollbackFor=ResponseStatusException.class</code> để khi đồng hồ hết giờ, thao tác auto-submit trong cùng request không bị rollback bởi phản hồi báo hết giờ. Cần đọc transaction boundary trước khi copy pattern này; không dùng đại trà.

Frontend không được tin là lớp bảo vệ cuối: khóa nút tra từ trong REAL_TEST chỉ là UX; backend còn kiểm attempt REAL_TEST active trước lookup/note. Không trả answer key trong API bắt đầu làm bài. API key từ điển nằm trong biến môi trường backend, không đưa vào bundle Vite. Session cookie HttpOnly và CSRF token cho mutation bảo vệ luồng cookie session; cấu hình cookie Secure tùy môi trường nên khi triển khai HTTPS phải bật đúng.

**Giới hạn hiện tại.** Chưa có một định dạng lỗi tập trung cho mọi controller, các route vẫn dùng <code>ResponseStatusException</code> và message trực tiếp. Test HTTP chủ yếu dùng H2; chưa có Testcontainers/PostgreSQL integration suite. Vì vậy hành vi đặc thù PostgreSQL (JSONB, advisory lock, SKIP LOCKED, migration) cần kiểm thêm trên PostgreSQL thật trước launch.

## 11. Công thức dùng lại khi mở rộng dự án

### 11.1 Thêm một request mới

1. Viết một câu về actor và quyền: public, USER hay ADMIN; xác định endpoint và dữ liệu vào/ra.
2. Xác định bảng và constraint cần dùng. Nếu đổi schema, thêm V mới; không sửa migration cũ.
3. Controller đọc principal, validate input dễ hiểu, gọi service/SQL bằng parameter bind; đặt transaction quanh thao tác nhiều bước.
4. Quy định 200/201/202/400/401/403/404/409/422 phù hợp và dữ liệu lỗi frontend sẽ hiển thị.
5. Cập nhật <code>SecurityConfiguration</code> khi route có quyền khác mặc định; frontend thêm lời gọi trong <code>F/api.ts</code> hoặc feature API hiện có.
6. Thử ít nhất happy path, invalid input, không đăng nhập, user khác và request cạnh tranh nếu có UNIQUE/version/lock.

### 11.2 Thêm dạng câu hỏi hoặc thay cách chấm

Đi từ <code>AdminQuestionGroupController</code> (DTO, validate, write) → migration nếu cần dữ liệu mới → <code>ReadingPassageController</code> và <code>ReadingTestPracticeController</code> (payload learner không lộ key) → <code>ReadingTaskContent.tsx</code> (hiển thị/nhập) → <code>AnswerTextPolicy</code> và hai result controller (chấm) → publish check → kiểm thử admin nhập, learner làm, nộp, xem lại. Giữ một câu hỏi = một số duy nhất, còn group là đơn vị trình bày. Nếu completion có nhiều ô trong cùng đoạn, mỗi ô vẫn là một question và template trỏ đến số đó.

### 11.3 Thêm nguồn từ điển hoặc công cụ import

Provider mới đi qua backend adapter, chuẩn hóa headword/sense/meaning/example/audio trước khi chạm DB. Giữ cache chung theo normalized word, note riêng theo user+sense. Xử lý timeout, quota, response thiếu trường và fallback cache. Với import, giữ job 202 + polling + review, không chạy parsing nặng trong request upload. Chỉ cho publish khi draft đã qua validation của hệ thống câu hỏi.

### 11.4 Chạy và kiểm tra tại máy

<code>docker compose up --build</code> từ thư mục gốc (file <code>compose.yaml</code>) khởi động PostgreSQL, backend và dịch vụ import/OCR. Mở terminal thứ hai để chạy frontend Vite; proxy <code>/auth</code>, <code>/me</code>, <code>/api</code> sang backend port 8080. File <code>.env</code> chứa biến riêng tư và không commit. Kiểm <code>/actuator/health</code>, migration Flyway và log startup trước khi quy lỗi màn hình trắng cho React.

~~~powershell
cd 'E:\File From CC\Niên luận ngành'
docker compose up --build
~~~

Terminal thứ hai dùng cho frontend; dừng dev server khi cần chạy lệnh build:

~~~powershell
cd 'E:\File From CC\Niên luận ngành\frontend'
npm run dev
~~~

Chạy kiểm thử/build bằng các lệnh riêng:

~~~powershell
cd 'E:\File From CC\Niên luận ngành'
cd backend
.\mvnw.cmd test
cd ..\frontend
npm run build
~~~

Lệnh build/test chứng minh project biên dịch và một phần hành vi tự động; các thao tác admin publish, timer rời bài, đồng thời, âm thanh từ điển, PDF OCR và giao diện mobile vẫn cần kiểm thủ công có dữ liệu thật. Mở Network/Console để xem status API và stack trace; màn hình trắng không đồng nghĩa backend không trả dữ liệu.

## 12. Chỉ mục code để tra lại nhanh

| Nhu cầu | File chính |
| --- | --- |
| Đổi form và route đăng nhập | <code>F/App.tsx</code>, <code>F/api.ts</code>, <code>B/identity/AuthController.java</code>, <code>SecurityConfiguration.java</code> |
| Đổi cấu trúc passage và câu hỏi | <code>B/content/AdminPassageController.java</code>, <code>AdminQuestionGroupController.java</code>, <code>PresentationTemplate.java</code>, <code>F/features/admin/QuestionGroups.tsx</code> |
| Đổi điều kiện full test 40 câu | <code>B/content/AdminReadingTestController.java</code> |
| Đổi cách xem/làm/nộp passage | <code>B/content/ReadingPassageController.java</code>, <code>PracticeAttemptController.java</code>, <code>PracticeResultController.java</code>, <code>F/features/reading/ReadingPage.tsx</code> |
| Đổi timer/band full test | <code>B/content/ReadingTestPracticeController.java</code>, <code>AcademicBandService.java</code>, <code>F/features/reading/ReadingTestPage.tsx</code> |
| Đổi từ điển/note/highlight | <code>B/content/DictionaryLookupService.java</code>, <code>VocabularyController.java</code>, <code>ReadingHighlightController.java</code>, <code>F/features/vocabulary/VocabularyPage.tsx</code> |
| Đổi dashboard/lịch sử | <code>B/content/ReadingDashboardController.java</code>, <code>PracticeHistoryController.java</code>, <code>F/features/home/ReadingDashboard.tsx</code> |
| Đổi PDF import/review | <code>B/content/PdfImportService.java</code>, <code>PdfImportWorker.java</code>, <code>PdfImportReviewService.java</code>, <code>F/features/admin/PdfImports.tsx</code> |
| Đổi DB | <code>B/src/main/resources/db/migration</code>, <code>B/src/main/resources/application.yaml</code>, <code>compose.yaml</code> |

**Bộ kiểm thử hiện có.** <code>B/src/test/java/vn/tuanthongma/ieltsreading/</code> có các HTTP test cho auth, nhóm câu hỏi, load bài đọc, note, PDF import/review và unit test dictionary. Tên file test giúp tìm tình huống sẵn có; không suy diễn rằng mọi kịch bản PostgreSQL hoặc hành vi end-to-end đều đã được bao phủ.

**Khi debug một request:** xác nhận URL/method/status trong Network → tìm route bằng <code>rg</code> → đọc controller và principal/validation → theo query/transaction → kiểm migration/constraint → xem JSON trả về → theo state/render ở frontend. Ghi lại input tối thiểu gây lỗi và sửa đúng tầng sở hữu quy tắc. Nếu chỉ sửa giao diện để che lỗi 400/500, dữ liệu và quyền vẫn sai.

---

Tài liệu phản ánh snapshot code ở branch <code>Main-code</code> tại commit <code>0156fc3</code>, ngày 12/11/2026. Khi code thay đổi, cập nhật bảng endpoint, giới hạn và vị trí method trước khi tái sử dụng. Tài liệu này là bản đồ đọc code; source và migration vẫn là nơi xác nhận hành vi cuối cùng.
