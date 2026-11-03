# Ghi chú phỏng vấn: nhập đề Reading từ PDF

## Luồng đã xây dựng ở bước 1

`POST /api/admin/pdf-imports` nhận PDF và tạo job. Flyway V21 lưu trạng thái, số trang, tiến độ, mã lỗi và dữ liệu review dạng `jsonb`; file gốc nằm trong Docker volume riêng với tên UUID. Worker chạy nền trích xuất theo từng lô tối đa ba trang. Trang có lớp chữ dùng đường trích xuất thường; trang scan gọi OpenDataLoader qua OCR service nội bộ. `GET /api/admin/pdf-imports/{id}` cho Admin theo dõi, `GET /{id}/review` trả các khối có số trang nguồn. Chưa có bản ghi passage hay câu hỏi nào được tạo ở bước này.

## Điểm phỏng vấn

- **Bất đồng bộ và giới hạn tải:** OCR có thể mất nhiều phút, nên request upload chỉ xác thực và xếp hàng, trả `202 Accepted`. Một worker xử lý từng job; khóa advisory transaction bảo đảm tối đa ba job đang chờ/chạy ngay cả khi hai Admin upload đồng thời. Có timeout cho từng tiến trình parser và toàn job. Không giữ transaction PostgreSQL trong thời gian OCR.
- **Khôi phục sau lỗi:** Trạng thái job được lưu trong PostgreSQL. Khi API khởi động lại, job `RUNNING` bị gián đoạn được đưa về `QUEUED`; job lỗi có thể retry từ PDF gốc còn hạn. Cách này phù hợp với một API instance. Nếu triển khai nhiều instance, cần lease/heartbeat hoặc hàng đợi chuyên dụng để tránh một instance chiếm lại job của instance khác.
- **Tách dữ liệu gốc và dữ liệu xác nhận:** OCR chỉ tạo đề xuất có số trang và tọa độ; text PDF và scan PDF đều có thể sai thứ tự hoặc thiếu đáp án. Không suy đoán đáp án trống. Admin phải so sánh với PDF gốc trước khi tạo draft và publish ở bước sau.
- **Kiểm soát tệp tải lên:** Chỉ Admin được gọi API, mutation giữ CSRF; truy vấn job luôn ràng buộc `created_by_user_id`. Kiểm MIME, chữ ký `%PDF-`, cấu trúc PDF, dung lượng và số trang trước khi xếp hàng. Tên lưu trữ do server tạo; đường dẫn file không dựa trên tên người dùng gửi. Nguồn được cấp qua endpoint có quyền và `Cache-Control: no-store`.
- **Ranh giới transaction:** Tệp PDF nằm ngoài database nên không thể rollback cùng SQL. Nếu insert job thất bại, xóa tệp vừa ghi. Khi job đã commit, dọn tệp sau hủy/hết hạn. Đây là dạng nhất quán cuối cùng giữa filesystem và PostgreSQL, cần có chính sách cleanup rõ ràng.
- **Tách trang vật lý:** Với tài liệu scan thử nghiệm, cờ chọn trang của OpenDataLoader vẫn khiến hybrid OCR toàn PDF. Worker tạo PDF tạm tối đa ba trang, lưu ánh xạ về số trang gốc và cập nhật `processed_pages` sau mỗi lô. Như vậy tiến độ phản ánh việc đã xử lý xong, không phải ước lượng từ timer.
- **Tương thích runtime:** Docker OCR dùng PyTorch và torchvision bản CPU phù hợp với Transformers/Docling. Service báo `/health` thành công không bảo đảm một trang OCR sẽ chạy thành công; cần smoke test một PDF scan thật để phát hiện lỗi model hoặc dependency.

## Cần làm ở bước kế tiếp

Giao diện Admin so sánh hai cột, lưu bản review có version, xác nhận từng đáp án; sau đó mới xây dựng transaction tạo draft/publish.
