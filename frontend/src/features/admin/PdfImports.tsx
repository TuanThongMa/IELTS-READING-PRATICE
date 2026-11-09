import { lazy, Suspense, useEffect, useMemo, useState, type FormEvent } from "react";
import { api, apiMultipart } from "../../api";
import styles from "./PdfImports.module.css";

const PdfPageViewer = lazy(() => import("./PdfPageViewer").then(module => ({ default: module.PdfPageViewer })));

type ImportJob = {
  id: number; scope: "PASSAGE" | "FULL_TEST"; partNumber: number | null; status: string;
  cancelRequested: boolean; filename: string; pageCount: number; processedPages: number;
  reviewVersion: number; errorCode: string | null; createdAt: string; expiresAt: string;
};
type SourceBlock = { type: string; text: string; boundingBox?: unknown };
type SourcePage = { number: number; extractionMode: string; blocks: SourceBlock[]; warnings: string[] };
type PassageDraft = { partNumber: number; title: string; body: string; sourcePages: number[]; warnings: string[] };
type QuestionDraft = { number: number; text: string; taskVariant: string | null; sourcePage: number | null };
type AnswerDraft = { number: number; value: string; confirmed: boolean; sourcePage: number | null };
type UnclassifiedBlock = { pageNumber: number; blockIndex: number; text: string };
type Draft = { passages: PassageDraft[]; questions: QuestionDraft[]; answers: AnswerDraft[]; unclassifiedBlocks: UnclassifiedBlock[] };
type Review = { version: number; source: { schemaVersion: number; scope: string; partNumber: number | null; pages: SourcePage[] }; draft: Draft };
type ReviewTab = "passages" | "questions" | "answers" | "unclassified";
type TextTarget = { kind: "passage" | "question" | "answer"; index: number };

const variants = [
  ["TRUE_FALSE_NOT_GIVEN", "True / False / Not Given"], ["YES_NO_NOT_GIVEN", "Yes / No / Not Given"],
  ["MULTIPLE_CHOICE_SINGLE", "Trắc nghiệm một đáp án"], ["MULTIPLE_CHOICE_MULTIPLE", "Trắc nghiệm nhiều đáp án"],
  ["SENTENCE_COMPLETION", "Sentence Completion"], ["NOTE_COMPLETION", "Note Completion"],
  ["SUMMARY_COMPLETION", "Summary Completion"], ["TABLE_COMPLETION", "Table Completion"],
  ["FLOW_CHART_COMPLETION", "Flow Chart Completion"], ["SHORT_ANSWER", "Short Answer"],
  ["MATCHING_INFORMATION", "Matching Information"], ["MATCHING_HEADINGS", "Matching Headings"],
  ["MATCHING_FEATURES", "Matching Features"], ["MATCHING_SENTENCE_ENDINGS", "Matching Sentence Endings"],
] as const;

const statusText: Record<string, string> = {
  QUEUED: "Đang chờ", RUNNING: "Đang nhận dạng", REVIEW_READY: "Sẵn sàng kiểm tra",
  FAILED: "Nhận dạng lỗi", CANCELLED: "Đã hủy", EXPIRED: "Đã hết hạn",
  DRAFT_SAVED: "Đã tạo bản nháp", PUBLISHED: "Đã xuất bản",
};
const warningText: Record<string, string> = {
  REVIEW_EXTRACTED_TEXT: "Nội dung được nhận dạng tự động; hãy đối chiếu từng đoạn với PDF gốc.",
  PASSAGE_BOUNDARY_UNCERTAIN: "Chưa xác định chắc chắn ranh giới passage; hãy chọn trang nguồn và nhập nội dung.",
  OCR_REVIEW_REQUIRED: "Trang quét đã qua OCR; cần kiểm tra chữ và thứ tự đoạn.",
  NO_TEXT_DETECTED: "Không nhận dạng được chữ trên trang này.",
};
const displayWarning = (code: string) => warningText[code] ?? `Cảnh báo nhận dạng: ${code}`;
function parseSourcePages(raw: string, pageCount: number): number[] | null {
  if (!raw.trim()) return [];
  const parts = raw.split(",").map(value => value.trim());
  // A final comma is an intermediate typing state; the completed numbers are still usable.
  if (parts.length > 1 && parts[parts.length - 1] === "") parts.pop();
  if (parts.some(value => !/^\d+$/.test(value))) return null;
  const pages = parts.map(Number);
  if (pages.some(page => page < 1 || page > pageCount) || new Set(pages).size !== pages.length) return null;
  return pages;
}
const reviewable = (status: string) => status === "REVIEW_READY";
const readError = async (response: Response) => {
  try {
    const problem = await response.json() as { detail?: string; message?: string };
    if (problem.detail || problem.message) return problem.detail || problem.message || "";
  } catch { /* Some errors do not have a JSON body. */ }
  if (response.status === 413) return "PDF vượt giới hạn 20 MB.";
  if (response.status === 415) return "Hãy chọn tệp PDF hợp lệ.";
  if (response.status === 429) return "Hàng đợi nhận dạng đã đầy. Hãy thử lại sau.";
  if (response.status === 409) return "Dữ liệu đã thay đổi ở cửa sổ khác. Tải lại bản kiểm tra trước khi lưu tiếp.";
  return "Không thể hoàn thành thao tác. Hãy thử lại.";
};

export function PdfImports({ onBack, onDirtyChange }: { onBack: () => void; onDirtyChange: (dirty: boolean) => void }) {
  const [jobs, setJobs] = useState<ImportJob[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [scope, setScope] = useState<"PASSAGE" | "FULL_TEST">("PASSAGE");
  const [partNumber, setPartNumber] = useState(1);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [loadingJobs, setLoadingJobs] = useState(true);
  const [loadingReview, setLoadingReview] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [sourcePageErrors, setSourcePageErrors] = useState<Record<number, string>>({});
  const [review, setReview] = useState<Review | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [tab, setTab] = useState<ReviewTab>("passages");
  const [mobilePane, setMobilePane] = useState<"source" | "draft">("draft");
  const [pageNumber, setPageNumber] = useState(1);
  const [zoom, setZoom] = useState(1);
  const [target, setTarget] = useState<TextTarget | null>(null);
  const [newQuestionNumber, setNewQuestionNumber] = useState(1);
  const [newAnswerNumber, setNewAnswerNumber] = useState(1);
  const selected = jobs.find(job => job.id === selectedId) ?? null;
  const dirty = Boolean((review && draft && JSON.stringify(review.draft) !== JSON.stringify(draft)) || Object.keys(sourcePageErrors).length);
  const sourcePage = review?.source.pages.find(page => page.number === pageNumber);
  const pageCount = selected?.pageCount ?? review?.source.pages.length ?? 0;
  const confirmedCount = draft?.answers.filter(answer => answer.confirmed).length ?? 0;
  const targetLabel = useMemo(() => {
    if (!target || !draft) return "Chọn một ô nội dung bên phải để chèn văn bản OCR.";
    if (target.kind === "passage") return `Đích: Nội dung Passage ${draft.passages[target.index]?.partNumber ?? "?"}`;
    if (target.kind === "question") return `Đích: Câu ${draft.questions[target.index]?.number ?? "?"}`;
    return `Đích: Đáp án ${draft.answers[target.index]?.number ?? "?"}`;
  }, [draft, target]);

  useEffect(() => { onDirtyChange(dirty); }, [dirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange(false), [onDirtyChange]);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const response = await api("/api/admin/pdf-imports");
        if (!response.ok) throw new Error();
        const list = await response.json() as ImportJob[];
        if (cancelled) return;
        setJobs(list);
        setSelectedId(current => current ?? list[0]?.id ?? null);
        setError("");
      } catch { if (!cancelled) setError("Không thể tải các lần nhập PDF gần đây."); }
      finally { if (!cancelled) setLoadingJobs(false); }
    };
    void load();
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!selected || !["QUEUED", "RUNNING"].includes(selected.status)) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const response = await api(`/api/admin/pdf-imports/${selected.id}`);
        if (!response.ok) return;
        const updated = await response.json() as ImportJob;
        if (!cancelled) setJobs(list => list.map(job => job.id === updated.id ? updated : job));
      } catch { /* A missed poll is retried while the page remains open. */ }
    };
    const timer = window.setInterval(() => void poll(), 3000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [selected?.id, selected?.status]);

  useEffect(() => {
    if (!selected || !reviewable(selected.status)) { setReview(null); setDraft(null); return; }
    let cancelled = false;
    const load = async () => {
      setLoadingReview(true);
      try {
        const response = await api(`/api/admin/pdf-imports/${selected.id}/review`);
        if (!response.ok) throw new Error();
        const data = await response.json() as Review;
        if (cancelled) return;
        setReview(data);
        setDraft(structuredClone(data.draft));
        setSourcePageErrors({});
        setPageNumber(data.source.pages[0]?.number ?? 1);
        setTarget(data.draft.passages.length ? { kind: "passage", index: 0 } : null);
        setError("");
      } catch { if (!cancelled) setError("Không thể tải nội dung nhận dạng. Hãy mở lại lần nhập này."); }
      finally { if (!cancelled) setLoadingReview(false); }
    };
    void load();
    return () => { cancelled = true; };
  }, [selected?.id, selected?.status]);

  function chooseJob(id: number) {
    if (id === selectedId) return;
    if (dirty && !window.confirm("Bản kiểm tra có thay đổi chưa lưu. Chuyển sang PDF khác?")) return;
    setSelectedId(id); setReview(null); setDraft(null); setError(""); setNotice(""); setTab("passages"); setZoom(1);
    setSourcePageErrors({});
  }

  function close() {
    if (dirty && !window.confirm("Bản kiểm tra có thay đổi chưa lưu. Quay lại quản trị?")) return;
    onDirtyChange(false); onBack();
  }

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file) { setError("Hãy chọn tệp PDF."); return; }
    if (file.size > 20 * 1024 * 1024) { setError("PDF vượt giới hạn 20 MB."); return; }
    if (!file.name.toLowerCase().endsWith(".pdf")) { setError("Hãy chọn tệp PDF hợp lệ."); return; }
    if (dirty && !window.confirm("Bản kiểm tra có thay đổi chưa lưu. Nhập PDF mới?")) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const body = new FormData();
      body.append("file", file);
      body.append("scope", scope);
      if (scope === "PASSAGE") body.append("partNumber", String(partNumber));
      const response = await apiMultipart("/api/admin/pdf-imports", body);
      if (!response.ok) { setError(await readError(response)); return; }
      const job = await response.json() as ImportJob;
      setJobs(list => [job, ...list.filter(item => item.id !== job.id)]);
      setSelectedId(job.id); setFile(null); setReview(null); setDraft(null);
      setSourcePageErrors({});
      const input = document.getElementById("pdf-import-file") as HTMLInputElement | null;
      if (input) input.value = "";
      setNotice("Đã nhận PDF. Bạn có thể rời trang; tiến trình nhận dạng vẫn tiếp tục.");
    } catch { setError("Không thể kết nối máy chủ để nhập PDF."); }
    finally { setBusy(false); }
  }

  async function changeJob(action: "retry" | "discard") {
    if (!selected) return;
    if (action === "discard" && !window.confirm("Hủy lần nhập PDF này?")) return;
    if (dirty && !window.confirm("Bản kiểm tra có thay đổi chưa lưu. Tiếp tục?")) return;
    setBusy(true); setError("");
    try {
      const response = await api(`/api/admin/pdf-imports/${selected.id}${action === "retry" ? "/retry" : ""}`, action === "retry" ? "POST" : "DELETE");
      if (!response.ok) { setError(await readError(response)); return; }
      const job = await response.json() as ImportJob;
      setJobs(list => list.map(item => item.id === job.id ? job : item));
      setNotice(action === "retry" ? "Đã đưa PDF vào hàng chờ nhận dạng." : "Đã gửi yêu cầu hủy lần nhập PDF.");
    } catch { setError("Không thể kết nối máy chủ. Hãy thử lại."); }
    finally { setBusy(false); }
  }

  function updateDraft(change: (current: Draft) => Draft) { setDraft(current => current ? change(current) : current); }
  function updatePassage(index: number, change: Partial<PassageDraft>) {
    updateDraft(current => ({ ...current, passages: current.passages.map((item, i) => i === index ? { ...item, ...change } : item) }));
  }
  function changeSourcePages(index: number, raw: string) {
    const pages = parseSourcePages(raw, pageCount);
    const part = draft?.passages[index]?.partNumber;
    if (part === undefined) return;
    if (!pages) {
      setSourcePageErrors(current => ({ ...current, [part]: `Nhập các trang từ 1 đến ${pageCount}, cách nhau bằng dấu phẩy và không trùng.` }));
      return;
    }
    setSourcePageErrors(current => { const next = { ...current }; delete next[part]; return next; });
    updatePassage(index, { sourcePages: pages });
  }
  function updateQuestion(index: number, change: Partial<QuestionDraft>) {
    updateDraft(current => ({ ...current, questions: current.questions.map((item, i) => i === index ? { ...item, ...change } : item) }));
  }
  function updateAnswer(index: number, change: Partial<AnswerDraft>) {
    updateDraft(current => ({ ...current, answers: current.answers.map((item, i) => i === index ? { ...item, ...change } : item) }));
  }
  function jumpTo(page: number | null | undefined) {
    if (page && page >= 1 && page <= pageCount) setPageNumber(page);
  }
  function appendBlock(text: string) {
    if (!draft || !target) { setNotice("Chọn ô nội dung bên phải trước khi chèn văn bản OCR."); return; }
    if (target.kind === "passage" && draft.passages[target.index]) {
      const original = draft.passages[target.index].body;
      updatePassage(target.index, { body: `${original}${original ? "\n\n" : ""}${text}` });
    } else if (target.kind === "question" && draft.questions[target.index]) {
      const original = draft.questions[target.index].text;
      updateQuestion(target.index, { text: `${original}${original ? " " : ""}${text}` });
    } else if (target.kind === "answer" && draft.answers[target.index]) {
      const original = draft.answers[target.index].value;
      updateAnswer(target.index, { value: `${original}${original ? " " : ""}${text}`, confirmed: false });
    }
    setNotice("Đã chèn văn bản OCR vào ô đang chọn. Hãy so với PDF gốc trước khi lưu.");
  }
  async function copyBlock(text: string) {
    try { await navigator.clipboard.writeText(text); setNotice("Đã sao chép nội dung OCR."); }
    catch { setError("Không thể sao chép. Hãy chọn văn bản OCR để sao chép thủ công."); }
  }

  async function save() {
    if (!selected || !review || !draft || !dirty || Object.keys(sourcePageErrors).length) return;
    setBusy(true); setError(""); setNotice("");
    try {
      const response = await api(`/api/admin/pdf-imports/${selected.id}/review`, "PUT", { version: review.version, draft });
      if (!response.ok) { setError(await readError(response)); return; }
      const updated = await response.json() as Review;
      setReview(updated); setDraft(structuredClone(updated.draft));
      setJobs(list => list.map(job => job.id === selected.id ? { ...job, reviewVersion: updated.version } : job));
      setNotice(`Đã lưu bản kiểm tra, phiên bản ${updated.version}.`);
    } catch { setError("Không thể lưu bản kiểm tra. Hãy thử lại."); }
    finally { setBusy(false); }
  }

  async function reloadReview() {
    if (!selected) return;
    if (dirty && !window.confirm("Bỏ thay đổi chưa lưu và tải lại bản kiểm tra?")) return;
    setLoadingReview(true); setError("");
    try {
      const response = await api(`/api/admin/pdf-imports/${selected.id}/review`);
      if (!response.ok) throw new Error();
      const data = await response.json() as Review;
      setReview(data); setDraft(structuredClone(data.draft));
      setSourcePageErrors({});
      setNotice("Đã tải lại bản kiểm tra mới nhất.");
    } catch { setError("Không thể tải lại bản kiểm tra."); }
    finally { setLoadingReview(false); }
  }

  function addQuestion() {
    if (!draft) return;
    const number = newQuestionNumber;
    if (number < 1 || number > 40 || draft.questions.some(item => item.number === number)) { setError("Số câu phải từ 1 đến 40 và chưa có trong danh sách."); return; }
    updateDraft(current => ({
      ...current,
      questions: [...current.questions, { number, text: "", taskVariant: null, sourcePage: pageNumber }].sort((a, b) => a.number - b.number),
      answers: current.answers.some(item => item.number === number) ? current.answers
        : [...current.answers, { number, value: "", confirmed: false, sourcePage: pageNumber }].sort((a, b) => a.number - b.number),
    }));
    setError(""); setTab("questions");
  }
  function addAnswer() {
    if (!draft) return;
    const number = newAnswerNumber;
    if (number < 1 || number > 40 || draft.answers.some(item => item.number === number)) { setError("Số đáp án phải từ 1 đến 40 và chưa có trong danh sách."); return; }
    updateDraft(current => ({ ...current, answers: [...current.answers, { number, value: "", confirmed: false, sourcePage: pageNumber }].sort((a, b) => a.number - b.number) }));
    setError(""); setTab("answers");
  }

  return <div className={styles.imports}>
    <div className={styles.topLine}><button type="button" className={styles.textButton} onClick={close}>← Quay lại quản trị</button><span>Chỉ Admin xem được PDF này · Bản nhập chưa xuất bản</span></div>
    <section className={styles.uploadPanel} aria-labelledby="pdf-upload-title">
      <div><h2 id="pdf-upload-title">Nhập đề từ PDF</h2><p>Nhận dạng chỉ đề xuất nội dung. Kiểm tra từng trang và xác nhận đáp án trước khi dùng.</p></div>
      <form onSubmit={event => void upload(event)} className={styles.uploadForm}>
        <label>Phạm vi<select value={scope} onChange={event => setScope(event.target.value as "PASSAGE" | "FULL_TEST")}><option value="PASSAGE">Một passage</option><option value="FULL_TEST">Full test</option></select></label>
        {scope === "PASSAGE" && <label>Vị trí<select value={partNumber} onChange={event => setPartNumber(Number(event.target.value))}><option value={1}>Passage 1</option><option value={2}>Passage 2</option><option value={3}>Passage 3</option></select></label>}
        <label className={styles.fileField}>Tệp PDF · tối đa 20 MB<input id="pdf-import-file" type="file" accept=".pdf,application/pdf" onChange={event => setFile(event.target.files?.[0] ?? null)} /></label>
        <button type="submit" className={styles.primary} disabled={busy}>{busy ? "Đang gửi..." : "Bắt đầu nhận dạng"}</button>
      </form>
    </section>
    {error && <p className={styles.error} role="alert">{error} {error.includes("cửa sổ khác") && <button type="button" onClick={() => void reloadReview()}>Tải lại bản kiểm tra</button>}</p>}
    {notice && <p className={styles.notice} role="status">{notice}</p>}
    <section className={styles.recent} aria-label="Các lần nhập PDF gần đây">
      <div className={styles.recentHead}><h2>Lần nhập gần đây</h2><span>Giữ nguồn PDF 7 ngày</span></div>
      {loadingJobs ? <p>Đang tải...</p> : jobs.length === 0 ? <p>Chưa có PDF nào. Chọn tệp để bắt đầu.</p> : <div className={styles.jobList}>{jobs.map(job => <button key={job.id} type="button" className={`${styles.job} ${selectedId === job.id ? styles.jobSelected : ""}`} onClick={() => chooseJob(job.id)} aria-current={selectedId === job.id ? "true" : undefined}><strong title={job.filename}>{job.filename}</strong><span>{job.scope === "FULL_TEST" ? "Full test" : `Passage ${job.partNumber}`} · {statusText[job.status] ?? job.status}</span></button>)}</div>}
    </section>
    {selected && <section className={styles.jobPanel} aria-labelledby="pdf-job-title">
      <div className={styles.jobHeading}><div><h2 id="pdf-job-title" title={selected.filename}>{selected.filename}</h2><p>{selected.scope === "FULL_TEST" ? "Full test" : `Passage ${selected.partNumber}`} · {selected.pageCount} trang · {statusText[selected.status] ?? selected.status}</p></div><div className={styles.jobActions}>{selected.status === "FAILED" && <button type="button" className={styles.secondary} disabled={busy} onClick={() => void changeJob("retry")}>Thử nhận dạng lại</button>}{!["CANCELLED", "EXPIRED", "DRAFT_SAVED", "PUBLISHED"].includes(selected.status) && <button type="button" className={styles.secondary} disabled={busy} onClick={() => void changeJob("discard")}>{selected.cancelRequested ? "Đang hủy..." : "Hủy lần nhập"}</button>}</div></div>
      {["QUEUED", "RUNNING"].includes(selected.status) && <div className={styles.progress}><div className={styles.progressText}><strong>{statusText[selected.status]}</strong><span>{selected.processedPages}/{selected.pageCount} trang</span></div><progress value={selected.processedPages} max={selected.pageCount || 1} /><p>Có thể rời trang và quay lại. Tiến trình vẫn tiếp tục trên máy chủ.</p></div>}
      {selected.status === "FAILED" && <p className={styles.error}>Nhận dạng thất bại{selected.errorCode ? ` (${selected.errorCode})` : ""}. Bạn có thể thử lại khi nguồn PDF còn hạn.</p>}
      {selected.status === "EXPIRED" && <p className={styles.muted}>Nguồn PDF đã hết hạn lưu trữ.</p>}
      {loadingReview && <p className={styles.muted}>Đang tải bản kiểm tra...</p>}
      {review && draft && <>
        <div className={styles.reviewHead}><div><strong>Bản kiểm tra · phiên bản {review.version}</strong><span>{draft.passages.length} passage · {draft.questions.length} câu đề xuất · {confirmedCount}/{draft.answers.length} đáp án đã xác nhận</span></div><div className={styles.reviewActions}><button type="button" className={styles.secondary} disabled={busy || loadingReview} onClick={() => void reloadReview()}>Tải lại</button><button type="button" className={styles.primary} disabled={busy || !dirty || Object.keys(sourcePageErrors).length > 0} onClick={() => void save()}>{busy ? "Đang lưu..." : dirty ? "Lưu phần kiểm tra" : "Đã lưu"}</button></div></div>
        <div className={styles.mobileSwitch}><button type="button" aria-pressed={mobilePane === "source"} onClick={() => setMobilePane("source")}>PDF gốc</button><button type="button" aria-pressed={mobilePane === "draft"} onClick={() => setMobilePane("draft")}>Nội dung nhập</button></div>
        <div className={styles.workspace}>
          <section className={`${styles.sourcePane} ${mobilePane === "source" ? styles.mobileVisible : ""}`} aria-label="PDF gốc và văn bản nhận dạng">
            <div className={styles.paneToolbar}><div><h3>PDF gốc</h3><span>Trang {pageNumber}/{pageCount}</span></div><div className={styles.pageControls}><button type="button" aria-label="Trang trước" disabled={pageNumber <= 1} onClick={() => setPageNumber(page => page - 1)}>‹</button><select aria-label="Số trang PDF" value={pageNumber} onChange={event => setPageNumber(Number(event.target.value))}>{Array.from({ length: pageCount }, (_, index) => <option key={index + 1} value={index + 1}>{index + 1}</option>)}</select><button type="button" aria-label="Trang sau" disabled={pageNumber >= pageCount} onClick={() => setPageNumber(page => page + 1)}>›</button><button type="button" aria-label="Thu nhỏ" disabled={zoom <= 0.7} onClick={() => setZoom(value => Math.max(0.7, value - 0.2))}>−</button><button type="button" aria-label="Phóng to" disabled={zoom >= 2} onClick={() => setZoom(value => Math.min(2, value + 0.2))}>+</button></div></div>
            <div className={styles.sourceScroll}><Suspense fallback={<p className={styles.pdfLoading}>Đang mở PDF gốc...</p>}><PdfPageViewer jobId={selected.id} pageNumber={pageNumber} zoom={zoom} /></Suspense>
              <div className={styles.ocrSection}><h4>Văn bản nhận dạng · trang {pageNumber}</h4><p>{targetLabel}</p>{sourcePage?.warnings?.length ? <div className={styles.warning}>{sourcePage.warnings.map(displayWarning).join(" ")}</div> : null}
                {!sourcePage?.blocks?.length ? <p className={styles.muted}>Trang này chưa có văn bản nhận dạng.</p> : sourcePage.blocks.map((block, index) => <article className={styles.ocrBlock} key={index}><div><span>Khối {index + 1} · {block.type}</span><div><button type="button" onClick={() => void copyBlock(block.text)}>Sao chép</button><button type="button" disabled={!target} onClick={() => appendBlock(block.text)}>Chèn vào ô chọn</button></div></div><p>{block.text}</p></article>)}
              </div>
            </div>
          </section>
          <section className={`${styles.draftPane} ${mobilePane === "draft" ? styles.mobileVisible : ""}`} aria-label="Nội dung nhận dạng cần kiểm tra">
            <div className={styles.paneToolbar}><div><h3>Nội dung nhập</h3><span>So sánh với PDF trước khi xác nhận</span></div></div>
            <div className={styles.tabs} role="tablist" aria-label="Phần cần kiểm tra">{([ ["passages", "Passage"], ["questions", `Câu hỏi (${draft.questions.length})`], ["answers", `Đáp án (${confirmedCount}/${draft.answers.length})`], ["unclassified", `Khối OCR (${draft.unclassifiedBlocks.length})`] ] as const).map(([key, label]) => <button type="button" key={key} role="tab" aria-selected={tab === key} onClick={() => { setTab(key); setTarget(null); }}>{label}</button>)}</div>
            <div className={styles.draftScroll}>
              {tab === "passages" && <div className={styles.editList}><p className={styles.helper}>OCR có thể sai thứ tự hoặc thiếu đoạn. Nội dung được thêm ở đây vẫn là bản kiểm tra.</p>{draft.passages.map((passage, index) => <article className={styles.editItem} key={`${passage.partNumber}-${index}`}><div className={styles.itemHead}><h4>Passage {passage.partNumber}</h4><div className={styles.pageLinks}>{passage.sourcePages.map(page => <button key={page} type="button" onClick={() => jumpTo(page)}>Trang {page}</button>)}</div></div>{passage.warnings?.length > 0 && <div className={styles.warning}>{passage.warnings.map(displayWarning).join(" ")}</div>}<label>Tên bài<input value={passage.title} onFocus={() => setTarget({ kind: "passage", index })} onChange={event => updatePassage(index, { title: event.target.value })} /></label><label>Nội dung bài đọc<textarea className={styles.readingText} rows={12} value={passage.body} onFocus={() => { setTarget({ kind: "passage", index }); jumpTo(passage.sourcePages[0]); }} onChange={event => updatePassage(index, { body: event.target.value })} /></label><label>Trang nguồn (cách nhau bằng dấu phẩy)<input key={`${selected.id}-${review.version}-${index}`} defaultValue={passage.sourcePages.join(", ")} aria-invalid={Boolean(sourcePageErrors[passage.partNumber])} onChange={event => changeSourcePages(index, event.target.value)} onBlur={event => { const pages = parseSourcePages(event.target.value, pageCount); if (pages) event.target.value = pages.join(", "); }} />{sourcePageErrors[passage.partNumber] && <span className={styles.fieldError} role="alert">{sourcePageErrors[passage.partNumber]}</span>}</label></article>)}</div>}
              {tab === "questions" && <div className={styles.editList}><p className={styles.helper}>Số câu là số gốc trong PDF. Dạng câu hỏi chỉ là đề xuất và cần Admin chọn lại.</p>{draft.questions.map((question, index) => <article className={styles.editItem} key={`${question.number}-${index}`}><div className={styles.itemHead}><h4>Câu {question.number}</h4><div className={styles.pageLinks}>{question.sourcePage && <button type="button" onClick={() => jumpTo(question.sourcePage)}>Trang {question.sourcePage}</button>}<button type="button" className={styles.removeButton} onClick={() => { setTarget(null); updateDraft(current => ({ ...current, questions: current.questions.filter((_, i) => i !== index) })); }}>Bỏ đề xuất</button></div></div><label>Nội dung câu hỏi<textarea rows={4} value={question.text} onFocus={() => { setTarget({ kind: "question", index }); jumpTo(question.sourcePage); }} onChange={event => updateQuestion(index, { text: event.target.value })} /></label><div className={styles.fieldRow}><label>Số câu<input type="number" min={1} max={40} value={question.number} onChange={event => updateQuestion(index, { number: Number(event.target.value) })} /></label><label>Dạng câu<select value={question.taskVariant ?? ""} onChange={event => updateQuestion(index, { taskVariant: event.target.value || null })}><option value="">Chưa xác định</option>{variants.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><label>Trang nguồn<input type="number" min={1} max={pageCount} value={question.sourcePage ?? ""} onChange={event => updateQuestion(index, { sourcePage: event.target.value ? Number(event.target.value) : null })} /></label></div></article>)}<div className={styles.addLine}><label>Thêm câu số<input type="number" min={1} max={40} value={newQuestionNumber} onChange={event => setNewQuestionNumber(Number(event.target.value))} /></label><button type="button" className={styles.secondary} onClick={addQuestion}>+ Thêm câu hỏi</button></div></div>}
              {tab === "answers" && <div className={styles.editList}><div className={styles.warning}>Mỗi đáp án phải được so với PDF gốc rồi mới đánh dấu xác nhận. Đổi đáp án sẽ bỏ xác nhận trước đó.</div>{draft.answers.map((answer, index) => <article className={styles.answerItem} key={`${answer.number}-${index}`}><div className={styles.itemHead}><h4>Câu {answer.number}</h4><div className={styles.pageLinks}>{answer.sourcePage && <button type="button" onClick={() => jumpTo(answer.sourcePage)}>Trang {answer.sourcePage}</button>}{selected.scope !== "FULL_TEST" && <button type="button" className={styles.removeButton} onClick={() => { setTarget(null); updateDraft(current => ({ ...current, answers: current.answers.filter((_, i) => i !== index) })); }}>Bỏ ô</button>}</div></div><label>Đáp án<input value={answer.value} onFocus={() => { setTarget({ kind: "answer", index }); jumpTo(answer.sourcePage); }} onChange={event => updateAnswer(index, { value: event.target.value, confirmed: false })} placeholder="Chưa có đáp án" /></label><div className={styles.answerMeta}><label>Trang nguồn<input type="number" min={1} max={pageCount} value={answer.sourcePage ?? ""} onChange={event => updateAnswer(index, { sourcePage: event.target.value ? Number(event.target.value) : null, confirmed: false })} /></label><label className={styles.confirm}><input type="checkbox" checked={answer.confirmed} disabled={!answer.value.trim()} onChange={event => updateAnswer(index, { confirmed: event.target.checked })} />Đã đối chiếu PDF gốc</label></div></article>)}{selected.scope !== "FULL_TEST" && <div className={styles.addLine}><label>Thêm đáp án câu<input type="number" min={1} max={40} value={newAnswerNumber} onChange={event => setNewAnswerNumber(Number(event.target.value))} /></label><button type="button" className={styles.secondary} onClick={addAnswer}>+ Thêm đáp án</button></div>}</div>}
              {tab === "unclassified" && <div className={styles.editList}><p className={styles.helper}>Toàn bộ khối OCR được giữ lại để đối chiếu, kể cả phần đã đưa vào passage. Chọn ô chỉnh sửa rồi chèn từ nguồn ở bên trái.</p>{draft.unclassifiedBlocks.length === 0 ? <p className={styles.muted}>Không có khối OCR để đối chiếu.</p> : draft.unclassifiedBlocks.map((block, index) => <article className={styles.ocrBlock} key={`${block.pageNumber}-${block.blockIndex}-${index}`}><div><span>Trang {block.pageNumber} · khối {block.blockIndex + 1}</span><div><button type="button" onClick={() => jumpTo(block.pageNumber)}>Xem trang</button><button type="button" onClick={() => void copyBlock(block.text)}>Sao chép</button><button type="button" disabled={!target} onClick={() => appendBlock(block.text)}>Chèn vào ô chọn</button></div></div><p>{block.text}</p></article>)}</div>}
            </div>
          </section>
        </div>
      </>}
    </section>}
  </div>;
}
