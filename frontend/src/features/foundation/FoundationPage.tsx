import styles from "./FoundationPage.module.css";

export function FoundationPage() {
  return (
    <main className={styles.page}>
      <p className={styles.kicker}>IELTS Academic Reading</p>
      <h1>Reading Desk</h1>
      <p>
        Nền tảng kỹ thuật đã sẵn sàng. Thư viện đề, màn hình làm bài và kết quả
        sẽ được bổ sung theo từng use case đã duyệt.
      </p>
    </main>
  );
}
