import { Link } from "react-router-dom";
import styles from "./HomePage.module.css";

function PassageIllustration() {
  return <svg viewBox="0 0 520 300" role="img" aria-labelledby="passage-art-title" className={styles.illustration}>
    <title id="passage-art-title">Mở một passage và đánh dấu ý quan trọng</title>
    <path d="M70 63h380v190H70z" fill="#fff" stroke="#cbd5e1" strokeWidth="2" />
    <path d="M260 64v188" stroke="#cbd5e1" strokeWidth="2" />
    <path d="M91 91c45-17 93-13 145 8v118c-48-19-96-22-145-7z" fill="#f8fafc" stroke="#bfdbfe" strokeWidth="2" />
    <path d="M285 99c46-20 91-22 143-9v119c-49-14-97-11-143 8z" fill="#f8fafc" stroke="#bfdbfe" strokeWidth="2" />
    <path d="M108 119h90m-90 17h105m-105 17h89m-89 17h104m-104 17h71" stroke="#94a3b8" strokeWidth="5" strokeLinecap="round" />
    <path d="M303 119h99m-99 17h121m-121 17h90m-90 17h105m-105 17h82" stroke="#94a3b8" strokeWidth="5" strokeLinecap="round" />
    <path d="M303 132h79" stroke="#fef08a" strokeWidth="13" strokeLinecap="round" />
    <path d="m375 50 24 12-74 153-24 11 2-27z" fill="#2563eb" />
    <path d="m325 216 24-11-2 18-23 10z" fill="#f8fafc" />
    <path d="m299 226 24-10-1 18-24 9z" fill="#1e293b" />
    <circle cx="423" cy="72" r="24" fill="#dbeafe" />
    <path d="m412 72 8 8 15-18" fill="none" stroke="#2563eb" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M61 259h398" stroke="#93c5fd" strokeWidth="4" strokeLinecap="round" />
  </svg>;
}

function FullTestIllustration() {
  return <svg viewBox="0 0 520 300" role="img" aria-labelledby="test-art-title" className={styles.illustration}>
    <title id="test-art-title">Bài thi Reading với đồng hồ đếm 60 phút</title>
    <rect x="100" y="42" width="266" height="216" rx="10" fill="#fff" stroke="#cbd5e1" strokeWidth="2" />
    <path d="M126 74h176" stroke="#1e293b" strokeWidth="9" strokeLinecap="round" />
    <path d="M126 95h131" stroke="#94a3b8" strokeWidth="5" strokeLinecap="round" />
    <path d="M126 120h170m-170 15h143m-143 15h161" stroke="#cbd5e1" strokeWidth="4" strokeLinecap="round" />
    <g fill="#dbeafe" stroke="#2563eb" strokeWidth="2">
      <circle cx="143" cy="187" r="13"/><circle cx="143" cy="222" r="13"/>
    </g>
    <path d="M139 187h8m-4-4v8m-4 31 3 3 6-7" fill="none" stroke="#2563eb" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M169 182h134m-134 35h112" stroke="#94a3b8" strokeWidth="5" strokeLinecap="round" />
    <circle cx="383" cy="157" r="83" fill="#eff6ff" stroke="#2563eb" strokeWidth="8" />
    <path d="M383 91v66l43 25" fill="none" stroke="#1e3a8a" strokeWidth="8" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M383 75v11m0 142v11m82-82h-11m-142 0h-11" stroke="#2563eb" strokeWidth="5" strokeLinecap="round" />
    <rect x="350" y="251" width="66" height="30" rx="15" fill="#2563eb" />
    <text x="383" y="272" textAnchor="middle" fill="#fff" fontFamily="Be Vietnam Pro, sans-serif" fontSize="15" fontWeight="700">60 phút</text>
    <path d="M74 267h252" stroke="#93c5fd" strokeWidth="4" strokeLinecap="round" />
  </svg>;
}

export function HomePage() {
  return <div className={styles.page}>
    <header className={styles.header}>
      <Link to="/" className={styles.brand}><span>R</span><b>IELTS Reading Practice</b></Link>
      <nav aria-label="Điều hướng chính"><Link to="/" aria-current="page">Trang chủ</Link><Link to="/reading">Luyện Reading</Link></nav>
      <Link className={styles.login} to="/login">Đăng nhập</Link>
    </header>
    <main className={styles.main}>
      <section className={styles.hero} aria-labelledby="home-title">
        <h1 id="home-title">Đọc sâu từng passage.<br /><span>Vững bước chinh phục IELTS Reading.</span></h1>
        <p>Luyện từng dạng câu hỏi hoặc thử sức với bài thi Reading 40 câu trong 60 phút.</p>
      </section>
      <section className={styles.paths} aria-label="Chọn cách luyện IELTS Reading">
        <article className={styles.pathCard}>
          <div className={styles.artwork}><PassageIllustration /></div>
          <div className={styles.cardText}><h2>Luyện theo passage</h2><p>Tập trung vào từng bài đọc, làm quen với các dạng câu hỏi và cải thiện kỹ năng tìm thông tin.</p><Link to="/reading">Khám phá passage</Link></div>
        </article>
        <article className={styles.pathCard}>
          <div className={`${styles.artwork} ${styles.testArtwork}`}><FullTestIllustration /></div>
          <div className={styles.cardText}><h2>Làm full test</h2><p>Thử làm trọn bộ đề theo thời gian 60 phút và xem kết quả sau khi nộp bài.</p><Link to="/reading?type=test">Xem full test</Link></div>
        </article>
      </section>
    </main>
  </div>;
}
