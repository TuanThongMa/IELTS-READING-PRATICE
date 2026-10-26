# Ghi chú kỹ thuật phỏng vấn Backend Spring

Tài liệu tích lũy các kiến thức rút ra trong quá trình xây dựng IELTS Reading. Mỗi nội dung cần phân biệt **đã có trong dự án** với **hướng đề xuất**, để không trình bày nhầm khi phỏng vấn. Cập nhật tài liệu khi có quyết định hoặc triển khai mới liên quan.

## 1. Java record và dữ liệu đăng nhập

**Trong dự án:** `Credentials(String email, String password)` là record nhận dữ liệu đăng nhập từ request; không phải session và không chứa quyền. Java tự sinh constructor, accessor, `equals`, `hashCode` và `toString`.

**Điểm phỏng vấn:** Record phù hợp để biểu diễn dữ liệu truyền giữa các tầng. Tính bất biến của record là theo tham chiếu của các thành phần, không đảm bảo đối tượng bên trong luôn bất biến. Không log record chứa mật khẩu vì `toString()` có thể làm lộ dữ liệu.

## 2. BCrypt và lưu mật khẩu

**Trong dự án:** backend băm mật khẩu bằng BCrypt, lưu hash và kiểm tra mật khẩu nhập vào với hash khi đăng nhập.

**Điểm phỏng vấn:** Hash mật khẩu không phải mã hóa có thể giải mã. BCrypt dùng salt và cost để tăng chi phí dò mật khẩu; cùng mật khẩu có thể tạo hash khác nhau. Băm không thay thế HTTPS, rate limiting hay bảo vệ session. Cần chú ý giới hạn đầu vào tính theo byte và xử lý thông báo rõ ràng.

## 3. Session và phân quyền

**Trong dự án:** sau khi đăng nhập thành công, thông tin xác thực và authority được lưu qua Spring Security context gắn với session; trình duyệt dùng cookie định danh session cho request sau.

**Điểm phỏng vấn:** Authentication xác định người dùng là ai; authorization xác định người đó được làm gì. Đã đăng nhập không có nghĩa được xem dữ liệu của người khác: API phải kiểm tra quyền sở hữu từng attempt. Cookie session bị đánh cắp có thể bị lạm dụng; cần HTTPS và cấu hình cookie phù hợp khi triển khai online.

## 4. Quy tắc nghiệp vụ và transaction

**Trong dự án:** backend xử lý quy tắc làm bài, lưu đáp án, nộp bài, chấm điểm và xem lịch sử; mỗi lần làm cần được phân biệt bằng attempt.

**Điểm phỏng vấn:** Framework cung cấp công cụ nhưng không tự hiểu quy tắc nghiệp vụ. Cần validation ở backend, kiểm tra quyền sở hữu và transaction cho các bước cần thành công hoặc thất bại cùng nhau. Cần phân biệt transaction với kiểm soát xung đột giữa các request đồng thời.

## 5. Optimistic locking

**Trạng thái:** hướng cải thiện, chưa coi là đã triển khai đầy đủ. Dùng version để phát hiện dữ liệu đã thay đổi giữa lúc đọc và cập nhật.

**Điểm phỏng vấn:** Đây là sự phối hợp giữa các tầng: client gửi version, backend kiểm tra và xử lý xung đột, PostgreSQL thực hiện cập nhật có điều kiện một cách nguyên tử. `UPDATE ... WHERE version = expected` ảnh hưởng 0 dòng có thể do xung đột hoặc không tìm thấy dữ liệu; cần phân biệt. Với JdbcTemplate, cần tự xử lý câu SQL và số dòng cập nhật; `@Version` là lựa chọn trong JPA, không tự áp dụng cho JdbcTemplate. Tình huống tạo mới, xóa và request cũ đến trễ cần được thiết kế riêng.

## 6. Snapshot kết quả

**Trạng thái:** hướng thiết kế cần cân nhắc khi thay đổi nội dung hoặc đáp án đã xuất bản; không khẳng định dự án đã lưu đầy đủ snapshot.

**Điểm phỏng vấn:** Snapshot là dữ liệu được lưu lại tại một thời điểm, ví dụ kết quả lúc nộp bài. Nó giúp giữ lịch sử ổn định khi dữ liệu nguồn thay đổi, nhưng cần cân đối dung lượng và độ phức tạp. Có thể lưu số liệu tổng, chi tiết từng câu hoặc tham chiếu phiên bản nội dung; backend tạo snapshot, PostgreSQL lưu và transaction bảo vệ tính nhất quán. Dữ liệu tái dựng sau này không chắc giống trạng thái gốc.

## 7. Tối ưu truy vấn và database

**Trong dự án:** danh sách lịch sử có điểm cần theo dõi vì có xử lý kết quả theo từng attempt; nên đo số query và thời gian khi dữ liệu tăng.

**Điểm phỏng vấn:** Có phân trang không đồng nghĩa query đã tối ưu. Cần xem cả số query bên trong mỗi trang và dữ liệu được tải có thực sự cần cho màn hình đó không. N+1 xảy ra khi một truy vấn danh sách kéo theo nhiều truy vấn lặp theo từng phần tử. Cân nhắc batch query, aggregate hoặc snapshot phù hợp.

**Điểm phỏng vấn:** Index cần dựa trên điều kiện lọc và sắp xếp thực tế; index không miễn phí vì tăng chi phí ghi và dung lượng. Dùng `EXPLAIN`/`EXPLAIN ANALYZE` để kiểm tra kế hoạch và đo thực tế; lưu ý `EXPLAIN ANALYZE` thực thi câu lệnh. Kết hợp phân trang, constraint, transaction và backup thay vì chỉ tập trung vào tốc độ.

## 8. Integration test với PostgreSQL

**Trong dự án:** test hiện tại có dùng H2 ở chế độ tương thích PostgreSQL; chưa mặc định là đã dùng Testcontainers.

**Điểm phỏng vấn:** Unit test kiểm tra đơn vị nhỏ; integration test kiểm tra nhiều thành phần phối hợp như API, Spring Security, Flyway và database. H2 tiện và nhanh nhưng không bảo đảm hành vi giống PostgreSQL. Testcontainers tạo PostgreSQL tạm qua Docker để kiểm tra migration, SQL và các luồng thật hơn; cần cấu hình tách biệt database phát triển.

**Ưu tiên test:** migration trên database rỗng; đăng ký/đăng nhập và session; phân quyền theo chủ sở hữu; lưu và nộp bài; chấm điểm; lịch sử nhiều attempt; request đồng thời. Chạy test với server thật ở luồng riêng không tự được rollback chỉ bằng transaction của test.

## 9. Multi-row invariant khi tạo full test

**Đã triển khai ở Phase 7.1:** migration V8 tạo `reading_tests` và `test_passages`; backend chỉ cho lưu/xuất bản full test khi đúng ba passage đã xuất bản tạo thành dải toàn cục liên tục `1..40`. Số câu từng passage có thể thay đổi, nên range được backend tính từ số câu thật thay vì hard-code 13/13/14.

**Điểm phỏng vấn:** `CHECK` constraint chỉ kiểm tra dữ liệu trong một hàng, ví dụ `question_start_number <= question_end_number`. Nó không thể một mình chứng minh ba hàng không chồng lấp, nối liên tục và tổng đúng 40. Quy tắc nhiều hàng cần được kiểm tra trong cùng transaction tại service; database vẫn bảo vệ các phần có thể biểu diễn bằng `UNIQUE`, foreign key và `CHECK`.

## 10. Tổng hợp kết quả full test

**Trong dự án:** API kết quả full test trả tổng số đúng/sai/bỏ qua, band Academic, breakdown theo loại câu hỏi và dữ liệu review từng passage/câu. Backend chỉ dựng đáp án đúng sau khi xác minh attempt thuộc người gọi và đã `SUBMITTED`; số câu review được đổi sang số thứ tự toàn bài theo range của từng passage.

**Điểm phỏng vấn:** Khi một màn hình cần thống kê theo nhóm, tránh N+1 query bằng batch query hoặc aggregate phù hợp. Chọn nơi tổng hợp theo kích thước dữ liệu và độ phức tạp: tập 40 câu có thể đọc một lần rồi nhóm trong Java; dữ liệu lớn hoặc dashboard nhiều nhóm có thể cần `GROUP BY` ở PostgreSQL. API response cần trả đủ dữ liệu cho UI nhưng không để lộ đáp án khi attempt chưa nộp; kiểm tra quyền sở hữu và trạng thái phải chạy trước khi dựng review.

## 11. Dashboard hoạt động Reading và thời lượng attempt

**Đã triển khai:** Dashboard chỉ tổng hợp các attempt `SUBMITTED` thuộc user đang đăng nhập. Lịch năm và bảng 7 ngày dùng ngày theo `Asia/Ho_Chi_Minh`; thời lượng passage lẻ được cộng dồn và gửi heartbeat mỗi 15 giây, khi đổi tab trình duyệt và lúc nộp. Full test dùng trường `elapsed_seconds` sẵn có. Query chỉ lấy timestamp và thời lượng trong khoảng năm hiện tại hoặc đầu cửa sổ 7 ngày nếu cửa sổ vượt qua ngày 1/1; Java quy đổi timestamp sang ngày địa phương và dựng các ngày rỗng cho UI. Các attempt passage đã nộp trước migration có thời lượng mặc định 0 vì hệ thống chưa từng lưu bộ đếm cho chúng.

**Điểm phỏng vấn:** Không nên suy thời gian học bằng `submitted_at - started_at`: khoảng đó có thể bao gồm thời gian người học bỏ dở. Lưu bộ đếm đã chạy giúp đo thời lượng gần với trải nghiệm hơn; checkpoint định kỳ giới hạn dữ liệu mất khi đóng đột ngột, nhưng có thể mất tối đa khoảng một chu kỳ khi không kịp gửi. Dashboard phải lọc theo `user_id` ở backend và chỉ tính trạng thái đã nộp; ẩn nút ở frontend không phải kiểm soát truy cập.

**Điểm phỏng vấn:** Lưu timestamp tuyệt đối có múi giờ, nhưng nhóm theo ngày tại múi giờ nghiệp vụ. Truy vấn dùng khoảng nửa mở `[đầu năm, ngày mai)` để tránh lỗi ranh giới cuối ngày; composite index `(user_id, status, submitted_at)` khớp thứ tự hai bộ lọc bằng nhau rồi điều kiện khoảng thời gian. Index tăng chi phí ghi và dung lượng, nên cần xem query plan nếu lượng attempt lớn lên.
