import { Link } from "react-router-dom";
import { AccountMenu } from "./AccountMenu";
import styles from "./AppHeader.module.css";

export type AccountUser = { id: number; email: string; displayName: string; role: string };
type Page = "home" | "reading" | "vocabulary" | "admin" | "profile";

export function AppHeader({ active, user, onLogout }: { active: Page; user: AccountUser | null; onLogout: () => void | Promise<void> }) {
  return <header className={styles.header}>
    <Link to="/" className={styles.brand}><span aria-hidden="true">R</span><b>IELTS Reading Practice</b></Link>
    <nav className={styles.nav} aria-label="Điều hướng chính">
      <Link to="/" aria-current={active === "home" ? "page" : undefined}>Trang chủ</Link>
      <Link to="/reading" aria-current={active === "reading" ? "page" : undefined}>Luyện Reading</Link>
      {user && <Link to="/vocabulary" aria-current={active === "vocabulary" ? "page" : undefined}>Từ vựng</Link>}
      {user?.role === "ADMIN" && <Link to="/admin" aria-current={active === "admin" ? "page" : undefined}>Quản trị</Link>}
    </nav>
    {user ? <AccountMenu displayName={user.displayName} onLogout={onLogout} /> : <Link className={styles.login} to="/login">Đăng nhập</Link>}
  </header>;
}
