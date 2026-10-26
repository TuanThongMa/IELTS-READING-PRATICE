import { useEffect, useMemo, useState, type FormEvent } from "react";
import { api } from "../../api";
import styles from "./AdminPage.module.css";

type PassageChoice = { id: number; title: string; partNumber: number; questionCount: number };
type TestPassage = PassageChoice & { questionStartNumber: number; questionEndNumber: number };
type TestSummary = { id: number; title: string; status: string; questionCount: number };
type TestDetail = { id: number; title: string; status: string; passages: TestPassage[] };
type CompositionCheck = { ready: boolean; issues: string[]; passages: TestPassage[]; questionCount: number };

export function ReadingTests({ onBack }: { onBack: () => void }) {
  const [tests, setTests] = useState<TestSummary[]>([]);
  const [passages, setPassages] = useState<PassageChoice[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<TestDetail | null>(null);
  const [creating, setCreating] = useState(false);
  const [title, setTitle] = useState("");
  const [selected, setSelected] = useState<(number | null)[]>([null, null, null]);
  const [check, setCheck] = useState<CompositionCheck | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  const selectedPassages = useMemo(() => selected.map(id => passages.find(p => p.id === id) ?? null), [passages, selected]);
  const derivedTotal = selectedPassages.reduce((sum, passage) => sum + (passage?.questionCount ?? 0), 0);
  const dirty = creating || editing !== null;

  async function load() {
    setLoading(true);
    try {
      const [testsResponse, passagesResponse] = await Promise.all([api("/api/admin/reading-tests"), api("/api/admin/reading-tests/published-passages")]);
      if (!testsResponse.ok || !passagesResponse.ok) throw new Error();
      setTests(await testsResponse.json() as TestSummary[]);
      setPassages(await passagesResponse.json() as PassageChoice[]);
      setError("");
    } catch { setError("Không thể tải dữ liệu full test. Vui lòng tải lại trang."); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, []);

  function resetForm() {
    if (dirty && !window.confirm("Bạn có thay đổi chưa lưu. Bạn có muốn quay lại danh sách?")) return;
    setCreating(false); setEditing(null); setTitle(""); setSelected([null, null, null]); setCheck(null); setError("");
  }

  function selectPassage(position: number, id: number | null) {
    setSelected(previous => previous.map((current, index) => index === position ? id : current));
    setCheck(null); setError("");
  }

  async function inspectComposition() {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api("/api/admin/reading-tests/composition-check", "POST", { passageIds: selected });
      if (!response.ok) throw new Error();
      setCheck(await response.json() as CompositionCheck);
    } catch { setError("Không thể kiểm tra cấu trúc đề. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  async function openTest(id: number) {
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api(`/api/admin/reading-tests/${id}`);
      if (!response.ok) throw new Error();
      const detail = await response.json() as TestDetail;
      setEditing(detail); setCreating(false); setTitle(detail.title); setSelected(detail.passages.map(p => p.id));
      setCheck({ ready: detail.status === "PUBLISHED", issues: [], passages: detail.passages, questionCount: detail.passages.reduce((sum, p) => sum + p.questionCount, 0) });
    } catch { setError("Không thể mở full test. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!title.trim()) { setError("Vui lòng nhập tên full test."); return; }
    if (!check?.ready) { setError("Hãy kiểm tra cấu trúc hợp lệ trước khi lưu."); return; }
    setBusy(true); setError(""); setNotice("");
    try {
      const response = editing
        ? await api(`/api/admin/reading-tests/${editing.id}`, "PUT", { title, passageIds: selected })
        : await api("/api/admin/reading-tests", "POST", { title, passageIds: selected });
      if (response.status === 422) { setError("Dữ liệu passage đã thay đổi. Hãy kiểm tra cấu trúc lại trước khi lưu."); setCheck(null); return; }
      if (!response.ok) { setError(response.status === 409 ? "Đề này không còn là bản nháp." : "Không thể lưu full test. Vui lòng thử lại."); return; }
      const detail = await response.json() as TestDetail;
      setEditing(detail); setCreating(false); setTitle(detail.title); setSelected(detail.passages.map(p => p.id));
      setCheck({ ready: false, issues: [], passages: detail.passages, questionCount: detail.passages.reduce((sum, p) => sum + p.questionCount, 0) });
      setNotice("Đã lưu full test ở trạng thái nháp."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  async function checkPublication() {
    if (!editing) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api(`/api/admin/reading-tests/${editing.id}/publish-check`);
      if (!response.ok) { setError(response.status === 409 ? "Đề này không còn là bản nháp." : "Không thể kiểm tra xuất bản."); return; }
      setCheck(await response.json() as CompositionCheck);
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  async function publish() {
    if (!editing || !check?.ready) return;
    if (!window.confirm("Xuất bản full test này? Sau khi xuất bản, đề không thể chỉnh sửa trực tiếp.")) return;
    setBusy(true); setError("");
    try {
      const response = await api(`/api/admin/reading-tests/${editing.id}/publish`, "POST");
      if (response.status === 422) { setCheck(await response.json() as CompositionCheck); return; }
      if (!response.ok) { setError(response.status === 409 ? "Đề này không còn là bản nháp." : "Không thể xuất bản full test."); return; }
      setEditing(current => current ? { ...current, status: "PUBLISHED" } : current);
      setNotice("Đã xuất bản full test. Học viên sẽ có thể bắt đầu thi sau khi phần phiên thi được hoàn thiện."); await load();
    } catch { setError("Không thể kết nối tới máy chủ. Vui lòng thử lại."); }
    finally { setBusy(false); }
  }

  const shownPassages = check?.passages ?? selectedPassages.map((passage, index) => {
    const start = selectedPassages.slice(0, index).reduce((sum, item) => sum + (item?.questionCount ?? 0), 1);
    return passage && { ...passage, questionStartNumber: start, questionEndNumber: start + passage.questionCount - 1 };
  }).filter((value): value is TestPassage => value !== null);

  return <section className={styles.panel} aria-labelledby="test-management-title">
    <div className={styles.sectionHead}><div><h2 id="test-management-title">Full test</h2><p className={styles.help}>Ghép ba passage đã xuất bản thành một đề 40 câu.</p></div><button type="button" className={styles.secondary} onClick={onBack}>Quay lại passage</button></div>
    {notice && <p className={styles.notice} role="status">{notice}</p>}
    {error && <p className={styles.error} role="alert">{error}</p>}
    {creating || editing ? <div className={styles.testEditor}>
      <h3>{editing ? editing.status === "DRAFT" ? "Chỉnh sửa full test" : "Xem full test" : "Tạo full test"}</h3>
      <p className={styles.help}>{editing?.status === "PUBLISHED" ? "Đề đã xuất bản nên chỉ có thể xem cấu trúc." : "Dải câu được hệ thống tính từ số câu thật của từng passage."}</p>
      <form className={styles.form} onSubmit={save}>
        <label>Tên full test<input value={title} onChange={event => { setTitle(event.target.value); setCheck(null); }} maxLength={200} required disabled={editing?.status === "PUBLISHED"} /></label>
        <div className={styles.testPassages}>
          {[0, 1, 2].map(index => <label key={index}>Passage {index + 1}<select value={selected[index] ?? ""} disabled={busy || editing?.status === "PUBLISHED"} onChange={event => selectPassage(index, event.target.value ? Number(event.target.value) : null)}><option value="">Chọn Passage {index + 1}</option>{passages.filter(passage => passage.partNumber === index + 1).map(passage => <option key={passage.id} value={passage.id}>{passage.title} ({passage.questionCount} câu)</option>)}</select></label>)}
        </div>
        <section className={styles.rangeCheck} aria-live="polite"><div><strong>Cấu trúc câu hỏi</strong><span>{derivedTotal}/40 câu</span></div>{shownPassages.length > 0 ? <ol>{shownPassages.map((passage, index) => <li key={`${passage.id}-${index}`}><span>Passage {index + 1}</span><strong>Câu {passage.questionStartNumber}–{passage.questionEndNumber}</strong><small>{passage.questionCount} câu</small></li>)}</ol> : <p>Chọn ba passage để xem dải câu.</p>}</section>
        {editing?.status !== "PUBLISHED" && <div className={styles.actions}><button type="button" className={styles.secondary} disabled={busy} onClick={() => void inspectComposition()}>Kiểm tra cấu trúc</button><button className={styles.primary} disabled={busy || !check?.ready}>{busy ? "Đang lưu..." : "Lưu full test"}</button><button type="button" className={styles.secondary} disabled={busy} onClick={resetForm}>Hủy</button></div>}
        {editing?.status === "PUBLISHED" && <div className={styles.actions}><button type="button" className={styles.secondary} onClick={resetForm}>Quay lại danh sách</button></div>}
      </form>
      {check && (check.ready ? <p className={styles.notice} role="status">Đề hợp lệ: ba passage tạo thành dải câu liên tục 1–40.</p> : <div className={styles.error} role="alert"><strong>Cần hoàn thiện trước khi lưu hoặc xuất bản:</strong><ul>{check.issues.map((issue, index) => <li key={index}>{issue}</li>)}</ul></div>)}
      {editing?.status === "DRAFT" && <section className={styles.publish}><h3>Kiểm tra và xuất bản</h3><p className={styles.help}>Hệ thống kiểm tra lại passage, dải câu và tổng 40 câu trước khi xuất bản.</p><div className={styles.actions}><button type="button" className={styles.secondary} disabled={busy} onClick={() => void checkPublication()}>Kiểm tra xuất bản</button>{check?.ready && <button type="button" className={styles.primary} disabled={busy} onClick={() => void publish()}>Xuất bản full test</button>}</div></section>}
    </div> : <>
      <div className={styles.testListAction}><button type="button" className={styles.primary} disabled={loading} onClick={() => { setCreating(true); setError(""); setNotice(""); }}>+ Tạo full test</button></div>
      {loading ? <p className={styles.empty}>Đang tải danh sách...</p> : tests.length === 0 ? <p className={styles.empty}>Chưa có full test nào. Chọn “Tạo full test” để ghép ba passage đã xuất bản.</p> : <div className={styles.tableWrap}><table><thead><tr><th>Tên đề</th><th>Số câu</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>{tests.map(test => <tr key={test.id}><td>{test.title}</td><td>{test.questionCount}/40</td><td><span className={styles.badge}>{test.status === "DRAFT" ? "Bản nháp" : test.status === "PUBLISHED" ? "Đã xuất bản" : "Đã lưu trữ"}</span></td><td><button type="button" className={styles.open} disabled={busy} onClick={() => void openTest(test.id)}>{test.status === "DRAFT" ? "Xem / Chỉnh sửa" : "Xem"}</button></td></tr>)}</tbody></table></div>}
    </>}
  </section>;
}
