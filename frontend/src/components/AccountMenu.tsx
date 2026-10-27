import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import styles from "./AccountMenu.module.css";

export function AccountMenu({ displayName, onLogout }: { displayName: string; onLogout: () => void | Promise<void> }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const initial = Array.from(displayName.trim())[0]?.toLocaleUpperCase("vi-VN") ?? "U";

  useEffect(() => {
    if (!open) return;
    function closeOutside(event: PointerEvent) {
      if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false);
    }
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        trigger.current?.focus();
      }
    }
    document.addEventListener("pointerdown", closeOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  return <div className={styles.root} ref={root}>
    <button
      ref={trigger}
      className={styles.trigger}
      type="button"
      aria-expanded={open}
      aria-haspopup="true"
      aria-label={`Tài khoản ${displayName}`}
      onClick={() => setOpen(value => !value)}
    >
      <span className={styles.avatar} aria-hidden="true">{initial}</span>
      <span className={styles.name}>{displayName}</span>
      <svg className={open ? styles.chevronOpen : styles.chevron} aria-hidden="true" viewBox="0 0 16 16"><path d="m4 6 4 4 4-4" /></svg>
    </button>
    {open && <div className={styles.popover}>
      <div className={styles.identity}><strong>{displayName}</strong><span>Tài khoản Reading</span></div>
      <Link to="/profile" onClick={() => setOpen(false)}>Thông tin cá nhân</Link>
      <div className={styles.divider} />
      <button type="button" onClick={() => { setOpen(false); void onLogout(); }}>Đăng xuất</button>
    </div>}
  </div>;
}
