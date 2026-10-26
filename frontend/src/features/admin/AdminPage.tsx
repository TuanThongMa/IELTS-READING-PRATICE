import { useEffect, useState, type FormEvent } from "react";
import { Link, useBlocker } from "react-router-dom";
import { api } from "../../api";
import styles from "./AdminPage.module.css";
import { QuestionGroups } from "./QuestionGroups";
import { ReadingTests } from "./ReadingTests";

type Passage = { id: number; title: string; partNumber: number; status: string };
type PassageDetail = Passage & { body: string };
type PublicationCheck = { ready: boolean; issues: string[] };

export function AdminPage() {
  const [passages, setPassages] = useState<Passage[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [partNumber, setPartNumber] = useState(1);
  const [editing, setEditing] = useState<PassageDetail | null>(null);
  const [opening, setOpening] = useState(false);
  const [questionsDirty, setQuestionsDirty] = useState(false);
  const [publicationCheck, setPublicationCheck] = useState<PublicationCheck | null>(null);
  const [managingTests, setManagingTests] = useState(false);
  const dirty = questionsDirty || (editing !== null && (title !== editing.title || body !== editing.body || partNumber !== editing.partNumber));
  const blocker = useBlocker(dirty);
  useEffect(() => {
    if (blocker.state === "blocked") {
      if (window.confirm("Bạn có thay đổi chưa lưu. Bạn có muốn rời trang?")) blocker.proceed();
      else blocker.reset();
    }
  }, [blocker]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  async function load() {
    setLoading(true);
    try {
      const response = await api("/api/admin/passages");
      if (!response.ok) throw new Error();
      setPassages(await response.json() as Passage[]);
      setError("");
    } catch { setError("Không thể tải danh sách passage. Vui lòng tải lại trang."); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, []);

  async function openPassage(id: number) {
    setOpening(true); setError(""); setNotice("");
    try {
      const response = await api(`/api/admin/passages/${id}`);
      if (!response.ok) throw new Error();
      const detail = await response.json() as PassageDetail;
      setEditing(detail); setTitle(detail.title); setBody(detail.body); setPartNumber(detail.partNumber); setPublicationCheck(null);
    } catch { setError("Không thể mở passage. Vui lòng thử lại."); }
    finally { setOpening(false); }
  }

  function closeForm() {
    if (dirty && !window.confirm("Bạn có thay đổi chưa lưu. Bạn có muốn quay lại?")) return;
    setEditing(null); setCreating(false); setTitle(""); setBody(""); setPartNumber(1); setError(""); setQuestionsDirty(false); setPublicationCheck(null);
  }

  async function checkPublication() {
    if (!editing || dirty) { setError("Hãy lưu hoặc hủy các thay đổi trước khi kiểm tra xuất bản."); return; }
    setBusy(true); setError(""); setPublicationCheck(null);
    try {
      const response = await api(`/api/admin/passages/${editing.id}/publish-check`);
      if (!response.ok) { setError(response.status === 409 ? "Passage không còn ở trạng thái nháp. Hãy mở lại passage." : "Không thể kiểm tra passage. Vui lòng thử lại."); return; }
      setPublicationCheck(await response.json() as PublicationCheck);
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  async function publish() {
    if (!editing || dirty) { setError("Hãy lưu hoặc hủy các thay đổi trước khi xuất bản."); return; }
    if (!window.confirm("Xuất bản passage này? Sau khi xuất bản, bạn không thể chỉnh sửa trực tiếp.")) return;
    setBusy(true); setError("");
    try {
      const response = await api(`/api/admin/passages/${editing.id}/publish`, "POST");
      if (response.status === 422) { setPublicationCheck(await response.json() as PublicationCheck); return; }
      if (!response.ok) { setError(response.status === 409 ? "Passage không còn ở trạng thái nháp. Hãy mở lại passage." : "Không thể xuất bản passage. Vui lòng thử lại."); return; }
      setEditing({ ...editing, status: "PUBLISHED" }); setPublicationCheck(null); setNotice("Đã xuất bản passage. Giao diện làm bài cho học viên sẽ được xây dựng ở bước tiếp theo."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Hãy mở lại passage để kiểm tra trạng thái trước khi thử lại."); }
    finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (questionsDirty && !window.confirm("Nhóm câu hỏi chưa lưu sẽ bị mất. Bạn vẫn muốn lưu passage và quay lại?")) return;
    setError("");
    setNotice("");
    if (!title.trim() || !body.trim()) { setError("Vui lòng nhập tên và nội dung passage."); return; }
    setBusy(true);
    try {
      const response = editing
        ? await api(`/api/admin/passages/${editing.id}`, "PATCH", { title, body, partNumber })
        : await api("/api/admin/passages", "POST", { title, body, partNumber });
      if (!response.ok) {
        setError(response.status === 400 ? "Thông tin passage chưa hợp lệ. Vui lòng kiểm tra lại." : response.status === 404 ? "Passage không còn tồn tại." : response.status === 409 ? "Passage không còn ở trạng thái nháp, không thể chỉnh sửa." : "Không thể lưu passage. Vui lòng thử lại.");
        return;
      }
      const wasEditing = editing !== null;
      setEditing(null);
      setQuestionsDirty(false);
      setTitle(""); setBody(""); setPartNumber(1); setCreating(false);
      setNotice(wasEditing ? "Đã lưu thay đổi passage." : "Đã tạo passage nháp.");
      await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  return <div className={styles.page}>
    <header className={styles.header}><Link to="/" className={styles.brand}><span>R</span> IELTS Reading Practice</Link><Link to="/">Về trang chính</Link></header>
    <main className={styles.main}>
      <div className={styles.heading}><div><h1>Quản trị Reading</h1><p>Quản lý passage và ghép full test cho học viên.</p></div>{!creating && !editing && !managingTests && <div className={styles.headingActions}><button className={styles.secondary} disabled={opening} onClick={() => { setManagingTests(true); setError(""); setNotice(""); }}>Quản lý full test</button><button className={styles.primary} disabled={opening} onClick={() => { setCreating(true); setError(""); setNotice(""); }}>+ Tạo passage</button></div>}</div>
      {notice && <p className={styles.notice} role="status">{notice}</p>}
      {error && <p className={styles.error} role="alert">{error}</p>}
      {managingTests ? <ReadingTests onBack={() => setManagingTests(false)} /> : creating || editing ? <section className={styles.panel}><h2>{editing ? editing.status === "DRAFT" ? "Chỉnh sửa passage" : "Xem passage" : "Tạo passage"}</h2><p className={styles.help}>{editing && editing.status !== "DRAFT" ? "Passage này không ở trạng thái nháp nên chỉ có thể xem." : "Bài được lưu dưới dạng bản nháp và chưa hiển thị với học viên."}</p>
        <form onSubmit={submit} className={styles.form}>
          <label>Tên passage<input value={title} onChange={e => setTitle(e.target.value)} maxLength={200} disabled={!!editing && editing.status !== "DRAFT"} required /></label>
          <label>Phần<select value={partNumber} onChange={e => setPartNumber(Number(e.target.value))} disabled={!!editing && editing.status !== "DRAFT"}><option value={1}>Passage 1</option><option value={2}>Passage 2</option><option value={3}>Passage 3</option></select></label>
          <label>Nội dung bài đọc<textarea value={body} onChange={e => setBody(e.target.value)} maxLength={100000} rows={14} disabled={!!editing && editing.status !== "DRAFT"} required /></label>
          <div className={styles.actions}>{(!editing || editing.status === "DRAFT") && <button className={styles.primary} disabled={busy}>{busy ? "Đang lưu..." : editing ? "Lưu thay đổi" : "Lưu bản nháp"}</button>}<button type="button" className={styles.secondary} disabled={busy} onClick={closeForm}>{editing ? "Quay lại danh sách" : "Hủy"}</button></div>
        </form>
        {editing && <QuestionGroups passageId={editing.id} canCreate={editing.status === "DRAFT"} onDirtyChange={setQuestionsDirty} />}
        {editing?.status === "DRAFT" && <section className={styles.publish} aria-labelledby="publish-title"><h2 id="publish-title">Kiểm tra và xuất bản</h2><p className={styles.help}>Lưu các thay đổi trước khi kiểm tra. Passage cần có ít nhất một nhóm câu hỏi hợp lệ. Sau khi xuất bản, nội dung không thể chỉnh sửa trực tiếp.</p><div className={styles.actions}><button type="button" className={styles.secondary} disabled={busy} onClick={() => void checkPublication()}>Kiểm tra passage</button>{publicationCheck?.ready && <button type="button" className={styles.primary} disabled={busy || dirty} onClick={() => void publish()}>Xuất bản passage</button>}</div>{publicationCheck && (publicationCheck.ready ? <p className={styles.notice} role="status">Passage đã sẵn sàng xuất bản. Hệ thống sẽ kiểm tra lại khi bạn xuất bản.</p> : <div className={styles.error} role="alert"><strong>Cần hoàn thiện trước khi xuất bản:</strong><ul>{publicationCheck.issues.map((issue, i) => <li key={i}>{issue}</li>)}</ul></div>)}</section>}
      </section> : <section className={styles.panel} aria-labelledby="passage-list"><div className={styles.sectionHead}><h2 id="passage-list">Danh sách passage</h2><span>{passages.length} bài</span></div>
        {loading ? <p className={styles.empty}>Đang tải danh sách...</p> : passages.length === 0 ? <p className={styles.empty}>Chưa có passage nào. Chọn “Tạo passage” để bắt đầu.</p> : <div className={styles.tableWrap}><table><thead><tr><th>Tên passage</th><th>Phần</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>{passages.map(p => <tr key={p.id}><td>{p.title}</td><td>Passage {p.partNumber}</td><td><span className={styles.badge}>{p.status === "DRAFT" ? "Bản nháp" : p.status === "PUBLISHED" ? "Đã xuất bản" : "Đã lưu trữ"}</span></td><td><button type="button" className={styles.open} disabled={opening} onClick={() => void openPassage(p.id)}>{p.status === "DRAFT" ? "Xem / Chỉnh sửa" : "Xem"}</button></td></tr>)}</tbody></table></div>}
      </section>}
    </main>
  </div>;
}
