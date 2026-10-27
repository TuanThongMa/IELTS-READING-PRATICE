import { Component, useEffect, useState, type ErrorInfo, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import styles from "./features/auth/Auth.module.css";
import { AdminPage } from "./features/admin/AdminPage";
import { ReadingLibrary, ReadingPassage } from "./features/reading/ReadingPage";
import { ReadingResult } from "./features/reading/ReadingResult";
import { ReadingHistory } from "./features/reading/ReadingHistory";
import { ReadingTest, ReadingTestResult } from "./features/reading/ReadingTestPage";
import { HomePage } from "./features/home/HomePage";
import { ReadingDashboard } from "./features/home/ReadingDashboard";
import { VocabularyPage } from "./features/vocabulary/VocabularyPage";
import { ProfilePage } from "./features/account/ProfilePage";
import type { AccountUser } from "./components/AppHeader";
import { api } from "./api";

type User = AccountUser;
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u;

class ScreenErrorBoundary extends Component<{ children: ReactNode }, { hasError: boolean }> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("Reading screen failed to render", error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return <main className={styles.status} role="alert">
        <h1>Không thể hiển thị trang này</h1>
        <p>Hãy tải lại trang. Nếu lỗi vẫn còn, quay lại danh sách bài đọc để thử lại.</p>
        <p><button className={styles.primary} onClick={() => window.location.reload()}>Tải lại trang</button></p>
        <Link to="/reading">Quay lại danh sách bài đọc</Link>
      </main>;
    }
    return this.props.children;
  }
}

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

function AppRoutes({ user, setUser, setNetworkError }: { user: User | null; setUser: (user: User | null) => void; setNetworkError: (value: boolean) => void }) {
  async function logout() {
    try {
      const response = await api("/auth/sessions/current", "DELETE");
      if (response.ok || response.status === 401) setUser(null);
      else setNetworkError(true);
    } catch { setNetworkError(true); }
  }
  return <Routes>
    <Route path="/login" element={user ? <Navigate to="/" replace /> : <AuthPage key="login" register={false} onSignedIn={setUser} />} />
    <Route path="/register" element={user ? <Navigate to="/" replace /> : <AuthPage key="register" register onSignedIn={setUser} />} />
    <Route path="/" element={user ? <ReadingDashboard user={user} onLogout={logout} /> : <HomePage />} />
    <Route path="/admin" element={!user ? <Navigate to="/login" replace /> : user.role === "ADMIN" ? <AdminPage user={user} onLogout={logout} /> : <Navigate to="/" replace />} />
    <Route path="/profile" element={user ? <ProfilePage user={user} onUserUpdated={setUser} onLogout={logout} /> : <Navigate to="/login" replace />} />
    <Route path="/reading" element={<ReadingLibrary user={user} onLogout={logout} />} />
    <Route path="/reading/history" element={user ? <ReadingHistory /> : <Navigate to="/login" replace />} />
    <Route path="/vocabulary" element={user ? <VocabularyPage user={user} onLogout={logout} /> : <Navigate to="/login" replace />} />
    <Route path="/reading/passages/:id" element={user ? <ReadingPassage /> : <Navigate to="/login" replace />} />
    <Route path="/reading/attempts/:id/result" element={user ? <ReadingResult /> : <Navigate to="/login" replace />} />
    <Route path="/reading/tests/:id" element={user ? <ReadingTest /> : <Navigate to="/login" replace />} />
    <Route path="/reading/tests/attempts/:id/result" element={user ? <ReadingTestResult /> : <Navigate to="/login" replace />} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>;
}

function GuardedRoutes(props: { user: User | null; setUser: (user: User | null) => void; setNetworkError: (value: boolean) => void }) {
  const location = useLocation();
  return <ScreenErrorBoundary key={location.pathname}><AppRoutes {...props} /></ScreenErrorBoundary>;
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
  return <GuardedRoutes user={user} setUser={setUser} setNetworkError={setNetworkError} />;
}
export default App;
