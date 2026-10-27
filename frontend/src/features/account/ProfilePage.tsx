import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../api";
import { AppHeader, type AccountUser } from "../../components/AppHeader";
import styles from "./ProfilePage.module.css";

async function errorMessage(response: Response, fallback: string) {
  try {
    const body = await response.json() as { detail?: string; message?: string };
    return body.detail ?? body.message ?? fallback;
  } catch { return fallback; }
}

export function ProfilePage({ user, onUserUpdated, onLogout }: {
  user: AccountUser; onUserUpdated: (user: AccountUser) => void; onLogout: () => void | Promise<void>;
}) {
  const [displayName, setDisplayName] = useState(user.displayName);
  const [profileState, setProfileState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [profileMessage, setProfileMessage] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordState, setPasswordState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [passwordMessage, setPasswordMessage] = useState("");

  useEffect(() => setDisplayName(user.displayName), [user.displayName]);

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedName = displayName.trim();
    if (!normalizedName || normalizedName.length > 100) {
      setProfileState("error");
      setProfileMessage("Tên hiển thị cần có từ 1 đến 100 ký tự.");
      return;
    }
    setProfileState("saving");
    setProfileMessage("");
    try {
      const response = await api("/me", "PATCH", { displayName: normalizedName });
      if (!response.ok) {
        setProfileState("error");
        setProfileMessage(await errorMessage(response, "Chưa lưu được thông tin. Hãy thử lại."));
        return;
      }
      const updated = await response.json() as AccountUser;
      onUserUpdated(updated);
      setDisplayName(updated.displayName);
      setProfileState("saved");
      setProfileMessage("Đã cập nhật thông tin cá nhân.");
    } catch {
      setProfileState("error");
      setProfileMessage("Không thể kết nối tới máy chủ. Kiểm tra kết nối rồi thử lại.");
    }
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordMessage("");
    if (newPassword.length < 8) {
      setPasswordState("error"); setPasswordMessage("Mật khẩu mới cần ít nhất 8 ký tự."); return;
    }
    if (new TextEncoder().encode(newPassword).length > 72) {
      setPasswordState("error"); setPasswordMessage("Mật khẩu mới quá dài. Hãy rút ngắn rồi thử lại."); return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordState("error"); setPasswordMessage("Mật khẩu xác nhận chưa khớp."); return;
    }
    if (newPassword === currentPassword) {
      setPasswordState("error"); setPasswordMessage("Mật khẩu mới cần khác mật khẩu hiện tại."); return;
    }
    setPasswordState("saving");
    try {
      const response = await api("/me/password", "PATCH", { currentPassword, newPassword });
      if (!response.ok) {
        setPasswordState("error");
        setPasswordMessage(await errorMessage(response, "Chưa đổi được mật khẩu. Hãy kiểm tra mật khẩu hiện tại."));
        return;
      }
      setCurrentPassword(""); setNewPassword(""); setConfirmPassword("");
      setPasswordState("saved"); setPasswordMessage("Đã đổi mật khẩu. Phiên đăng nhập hiện tại vẫn còn hiệu lực.");
    } catch {
      setPasswordState("error");
      setPasswordMessage("Không thể kết nối tới máy chủ. Kiểm tra kết nối rồi thử lại.");
    }
  }

  return <div className={styles.page}>
    <AppHeader active="profile" user={user} onLogout={onLogout} />
    <main className={styles.main}>
      <div className={styles.intro}><p className={styles.eyebrow}>TÀI KHOẢN</p><h1>Thông tin cá nhân</h1><p>Quản lý thông tin đăng nhập và tên hiển thị của bạn.</p></div>
      <div className={styles.forms}>
        <section className={styles.panel} aria-labelledby="profile-details-title">
          <header className={styles.panelHeader}><div><h2 id="profile-details-title">Thông tin cơ bản</h2><p>Tên hiển thị sẽ được dùng trong khu vực học tập.</p></div></header>
          <form className={styles.form} onSubmit={saveProfile}>
            <label>Tên hiển thị<input value={displayName} onChange={event => { setDisplayName(event.target.value); setProfileState("idle"); setProfileMessage(""); }} maxLength={100} autoComplete="name" required /></label>
            <label>Email<input type="email" value={user.email} readOnly aria-describedby="email-note" /><small id="email-note">Email hiện được dùng làm tên đăng nhập và chưa thể thay đổi.</small></label>
            <div className={styles.roleLine}><span>Loại tài khoản</span><strong>{user.role === "ADMIN" ? "Quản trị viên" : "Học viên"}</strong></div>
            <div className={styles.formFooter}><button className={styles.primary} type="submit" disabled={profileState === "saving"}>{profileState === "saving" ? "Đang lưu..." : "Lưu thông tin"}</button>{profileMessage && <p className={profileState === "error" ? styles.error : styles.success} role={profileState === "error" ? "alert" : "status"}>{profileMessage}</p>}</div>
          </form>
        </section>
        <section className={styles.panel} aria-labelledby="change-password-title">
          <header className={styles.panelHeader}><div><h2 id="change-password-title">Đổi mật khẩu</h2><p>Nhập mật khẩu hiện tại để xác nhận thay đổi.</p></div></header>
          <form className={styles.form} onSubmit={changePassword}>
            <label>Mật khẩu hiện tại<input type="password" value={currentPassword} onChange={event => { setCurrentPassword(event.target.value); setPasswordState("idle"); setPasswordMessage(""); }} autoComplete="current-password" required /></label>
            <label>Mật khẩu mới<input type="password" value={newPassword} onChange={event => { setNewPassword(event.target.value); setPasswordState("idle"); setPasswordMessage(""); }} minLength={8} autoComplete="new-password" required /><small>Mật khẩu cần ít nhất 8 ký tự.</small></label>
            <label>Xác nhận mật khẩu mới<input type="password" value={confirmPassword} onChange={event => { setConfirmPassword(event.target.value); setPasswordState("idle"); setPasswordMessage(""); }} minLength={8} autoComplete="new-password" required /></label>
            <div className={styles.formFooter}><button className={styles.secondary} type="submit" disabled={passwordState === "saving"}>{passwordState === "saving" ? "Đang đổi..." : "Đổi mật khẩu"}</button>{passwordMessage && <p className={passwordState === "error" ? styles.error : styles.success} role={passwordState === "error" ? "alert" : "status"}>{passwordMessage}</p>}</div>
          </form>
        </section>
      </div>
    </main>
  </div>;
}
