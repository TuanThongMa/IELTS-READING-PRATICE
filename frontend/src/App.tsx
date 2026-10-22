import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import styles from "./features/auth/Auth.module.css";
import { AdminPage } from "./features/admin/AdminPage";
import { api } from "./api";

type User = { id: number; email: string; displayName: string; role: string };
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u;

function AuthPage({ register, onSignedIn }: { register: boolean; onSignedIn: (user: User) => void }) {
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [emailError, setEmailError] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setEmailError("");
    if (!emailPattern.test(email.trim())) {
      setEmailError("Vui lòng nhập email hợp lệ, ví dụ: ban@example.com.");
      return;
    }
    if (register && !name.trim()) { setError("Vui lòng nhập tên hiển thị."); return; }
    if (!password) { setError("Vui lòng nhập mật khẩu."); return; }
    if (register && password !== confirm) { setError("Mật khẩu xác nhận chưa khớp."); return; }
    if (register && password.length < 8) { setError("Mật khẩu cần ít nhất 8 ký tự."); return; }
    if (new TextEncoder().encode(password).length > 72) { setError("Mật khẩu quá dài, vui lòng rút ngắn."); return; }
    setBusy(true);
    try {
      if (register) {
        const response = await api("/auth/register", "POST", { email, password, displayName: name });
        if (!response.ok) {
          if (response.status === 409) setEmailError("Email này đã được sử dụng.");
          else setError(response.status === 400 ? "Thông tin đăng ký chưa hợp lệ. Vui lòng kiểm tra lại." : "Không thể đăng ký lúc này. Vui lòng thử lại.");
          return;
        }
      }
      const response = await api("/auth/sessions", "POST", { email, password });
      if (!response.ok) {
        setError(register ? "Tài khoản đã tạo, nhưng chưa thể đăng nhập. Hãy thử đăng nhập lại." : response.status === 401 ? "Email hoặc mật khẩu không đúng." : "Không thể đăng nhập lúc này. Vui lòng thử lại.");
        return;
      }
      onSignedIn(await response.json() as User);
      navigate("/", { replace: true });
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }
  return <main className={styles.page}>
    <header className={styles.header}><Link to="/" className={styles.brand}><span className={styles.brandMark}>R</span> IELTS Reading Practice</Link></header>
    <div className={styles.layout}>
      <section className={styles.intro} aria-label="Giới thiệu">
        <div className={styles.readingMark} aria-hidden="true"><span>Reading</span><span>01 / 03</span><i /><i /><i /><i /></div>
        <h1>Luyện IELTS Reading<br />theo tiến độ của bạn.</h1>
        <p>Làm bài, xem kết quả và theo dõi quá trình học của bạn trong một không gian tập trung.</p>
      </section>
      <section className={styles.panel}>
        <h2>{register ? "Tạo tài khoản" : "Đăng nhập"}</h2>
        <p className={styles.description}>{register ? "Bắt đầu lưu lại quá trình luyện tập của bạn." : "Tiếp tục hành trình luyện đọc của bạn."}</p>
        <form onSubmit={submit} className={styles.form} noValidate>
          {register && <label>Tên hiển thị<input value={name} onChange={e => setName(e.target.value)} required maxLength={100} autoComplete="name" /></label>}
          <label>Email<input type="email" value={email} onChange={e => { setEmail(e.target.value); setEmailError(""); }} required maxLength={254} autoComplete="email" aria-invalid={Boolean(emailError)} aria-describedby={emailError ? "email-error" : undefined} />{emailError && <span id="email-error" className={styles.fieldError} role="alert">{emailError}</span>}</label>
          <label>Mật khẩu<div className={styles.password}><input type={showPassword ? "text" : "password"} value={password} onChange={e => setPassword(e.target.value)} required minLength={register ? 8 : undefined} autoComplete={register ? "new-password" : "current-password"} /><button type="button" onClick={() => setShowPassword(!showPassword)} aria-label={showPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu"}>{showPassword ? "Ẩn" : "Hiện"}</button></div></label>
          {register && <><p className={styles.hint}>Ít nhất 8 ký tự.</p><label>Xác nhận mật khẩu<input type={showPassword ? "text" : "password"} value={confirm} onChange={e => setConfirm(e.target.value)} required autoComplete="new-password" /></label></>}
          {error && <p className={styles.error} role="alert">{error}</p>}
          <button className={styles.primary} disabled={busy}>{busy ? "Đang xử lý..." : register ? "Đăng ký" : "Đăng nhập"}</button>
        </form>
        <p className={styles.switch}>{register ? "Đã có tài khoản?" : "Chưa có tài khoản?"} <Link to={register ? "/login" : "/register"}>{register ? "Đăng nhập" : "Đăng ký"}</Link></p>
      </section>
    </div>
  </main>;
}

function App() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [networkError, setNetworkError] = useState(false);
  useEffect(() => {
    api("/me").then(async response => {
      if (response.ok) setUser(await response.json() as User);
      else if (response.status !== 401) setNetworkError(true);
    }).catch(() => setNetworkError(true)).finally(() => setLoading(false));
  }, []);
  if (loading) return <main className={styles.status}>Đang kiểm tra phiên đăng nhập...</main>;
  if (networkError) return <main className={styles.status}>Không thể kết nối tới máy chủ. Vui lòng tải lại trang.</main>;
  return <Routes>
    <Route path="/login" element={user ? <Navigate to="/" replace /> : <AuthPage key="login" register={false} onSignedIn={setUser} />} />
    <Route path="/register" element={user ? <Navigate to="/" replace /> : <AuthPage key="register" register onSignedIn={setUser} />} />
    <Route path="/" element={user ? <main className={styles.status}><h1>Xin chào, {user.displayName}</h1><p>Bạn đã đăng nhập. Các chức năng luyện tập sẽ được bổ sung trong các phase tiếp theo.</p>{user.role === "ADMIN" && <p><Link to="/admin">Vào khu vực quản trị</Link></p>}<button className={styles.primary} onClick={async () => { try { const r = await api("/auth/sessions/current", "DELETE"); if (r.ok || r.status === 401) setUser(null); else setNetworkError(true); } catch { setNetworkError(true); } }}>Đăng xuất</button></main> : <Navigate to="/login" replace />} />
    <Route path="/admin" element={!user ? <Navigate to="/login" replace /> : user.role === "ADMIN" ? <AdminPage /> : <Navigate to="/" replace />} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>;
}
export default App;
