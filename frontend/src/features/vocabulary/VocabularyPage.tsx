import { Fragment, useEffect, useMemo, useState } from "react";
import { api } from "../../api";
import { AppHeader, type AccountUser } from "../../components/AppHeader";
import { AudioButton } from "../../components/AudioButton";
import styles from "./VocabularyPage.module.css";

type Category = { id: number; name: string; noteCount: number };
type RelatedWord = { word: string; relationType: "SYNONYM" | "ANTONYM" };
type Sense = { id: number; partOfSpeech: string; phoneticText: string | null; audioUrl: string | null; definition: string; example: string | null; relatedWords: RelatedWord[] };
type Entry = { id: number; word: string; sourceUrl: string; senses: Sense[] };
type Note = { id: number; entryId: number; word: string; selectedText: string; pronunciation: string | null; audioUrl: string | null; selectedDefinition: string; customMeaning: string | null; contextSentence: string | null; passageId: number | null; passageTitle: string | null; reviewStatus: "NEW" | "LEARNING" | "MASTERED"; createdAt: string };
const statusLabels = { NEW: "Chưa thuộc", LEARNING: "Nhớ sơ sơ", MASTERED: "Đã thuộc" } as const;

export function VocabularyPage({ user, onLogout }: { user: AccountUser; onLogout: () => void | Promise<void> }) {
  const [categories, setCategories] = useState<Category[]>([]);
  const [notes, setNotes] = useState<Note[]>([]);
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [query, setQuery] = useState("");
  const [loadState, setLoadState] = useState<"loading" | "ready" | "error">("loading");
  const [retry, setRetry] = useState(0);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingNote, setEditingNote] = useState<Note | null>(null);
  const [categoryName, setCategoryName] = useState("");
  const [categoryError, setCategoryError] = useState("");
  const [term, setTerm] = useState("");
  const [lookupState, setLookupState] = useState<"idle" | "loading" | "ready" | "error">("idle");
  const [lookupMessage, setLookupMessage] = useState("");
  const [entries, setEntries] = useState<Entry[]>([]);
  const [selectedSenseId, setSelectedSenseId] = useState<number | null>(null);
  const [context, setContext] = useState("");
  const [customMeaning, setCustomMeaning] = useState("");
  const [selectedCategories, setSelectedCategories] = useState<number[]>([]);
  const [saveState, setSaveState] = useState<"idle" | "saving" | "error">("idle");
  const [saveMessage, setSaveMessage] = useState("");
  const [pageMessage, setPageMessage] = useState("");
  const [expandedNote, setExpandedNote] = useState<number | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [studyIndex, setStudyIndex] = useState<number | null>(null);
  const [cardFlipped, setCardFlipped] = useState(false);

  async function load() {
    setLoadState("loading");
    try {
      const [categoryResponse, noteResponse] = await Promise.all([
        api("/api/vocabulary/categories"),
        api(`/api/vocabulary-notes${categoryId ? `?categoryId=${categoryId}` : ""}`),
      ]);
      if (!categoryResponse.ok || !noteResponse.ok) throw new Error();
      setCategories(await categoryResponse.json() as Category[]);
      setNotes(await noteResponse.json() as Note[]);
      setLoadState("ready");
    } catch { setLoadState("error"); }
  }

  useEffect(() => { void load(); }, [categoryId, retry]);

  const visibleNotes = useMemo(() => notes.filter(note => {
    const matchesStatus = statusFilter === "ALL" || note.reviewStatus === statusFilter;
    const normalized = query.trim().toLocaleLowerCase();
    const matchesQuery = !normalized || `${note.word} ${note.selectedText} ${note.selectedDefinition} ${note.customMeaning ?? ""}`.toLocaleLowerCase().includes(normalized);
    return matchesStatus && matchesQuery;
  }), [notes, query, statusFilter]);
  const totals = useMemo(() => ({
    total: notes.length,
    NEW: notes.filter(note => note.reviewStatus === "NEW").length,
    LEARNING: notes.filter(note => note.reviewStatus === "LEARNING").length,
    MASTERED: notes.filter(note => note.reviewStatus === "MASTERED").length,
  }), [notes]);

  async function createCategory(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setCategoryError("");
    try {
      const response = await api("/api/vocabulary/categories", "POST", { name: categoryName });
      if (!response.ok) { setCategoryError(response.status === 409 ? "Danh mục này đã tồn tại." : "Không thể tạo danh mục."); return; }
      setCategoryName(""); await load();
    } catch { setCategoryError("Không thể kết nối tới máy chủ."); }
  }

  async function deleteCategory(category: Category) {
    if (!window.confirm(`Xóa danh mục “${category.name}”? Các từ đã lưu sẽ vẫn được giữ.`)) return;
    try {
      const response = await api(`/api/vocabulary/categories/${category.id}`, "DELETE");
      if (!response.ok) throw new Error();
      if (categoryId === category.id) setCategoryId(null);
      else await load();
    } catch { setCategoryError("Chưa xóa được danh mục. Hãy thử lại."); }
  }

  async function lookup() {
    if (!term.trim()) return;
    setLookupState("loading"); setLookupMessage(""); setEntries([]); setSelectedSenseId(null);
    try {
      const response = await api(`/api/vocabulary/lookup?word=${encodeURIComponent(term.trim())}`);
      if (!response.ok) {
        let detail = "";
        try { const body = await response.json() as { detail?: string; message?: string }; detail = body.detail ?? body.message ?? ""; } catch { /* response không có JSON */ }
        setLookupState("error");
        setLookupMessage(detail || (response.status === 404 ? "Không tìm thấy từ này trong từ điển." : response.status === 503 ? "Dịch vụ từ điển chưa sẵn sàng. Bạn có thể thử lại sau." : "Không tra cứu được từ lúc này."));
        return;
      }
      const result = await response.json() as Entry[];
      setEntries(result); setSelectedSenseId(result[0]?.senses[0]?.id ?? null); setLookupState("ready");
    } catch { setLookupState("error"); setLookupMessage("Không thể kết nối tới máy chủ."); }
  }

  async function saveNote(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaveState("saving"); setSaveMessage("");
    try {
      const response = editingNote
        ? await api(`/api/vocabulary-notes/${editingNote.id}`, "PATCH", { selectedText: term.trim(), contextSentence: context.trim(), customMeaning: customMeaning.trim() || null })
        : await api("/api/vocabulary-notes", "POST", { senseId: selectedSenseId, selectedText: term.trim(), contextSentence: context.trim(), customMeaning: customMeaning.trim() || null, categoryIds: selectedCategories });
      if (!response.ok) { setSaveState("error"); setSaveMessage(editingNote ? "Không cập nhật được từ đã lưu." : "Không lưu được từ. Hãy kiểm tra nghĩa rồi thử lại."); return; }
      setDrawerOpen(false); setEditingNote(null); setTerm(""); setContext(""); setCustomMeaning(""); setEntries([]); setSelectedCategories([]); setLookupState("idle"); setSaveState("idle");
      await load();
    } catch { setSaveState("error"); setSaveMessage("Không thể kết nối tới máy chủ."); }
  }

  function editNote(note: Note) {
    setEditingNote(note); setTerm(note.selectedText); setContext(note.contextSentence ?? ""); setCustomMeaning(note.customMeaning ?? "");
    setEntries([]); setSelectedSenseId(null); setLookupState("idle"); setSelectedCategories([]); setSaveState("idle"); setSaveMessage(""); setDrawerOpen(true);
  }

  async function changeStatus(note: Note, reviewStatus: Note["reviewStatus"]) {
    try {
      const response = await api(`/api/vocabulary-notes/${note.id}/status`, "PATCH", { reviewStatus });
      if (!response.ok) throw new Error();
      setNotes(current => current.map(item => item.id === note.id ? { ...item, reviewStatus } : item));
    } catch { setPageMessage("Chưa cập nhật được trạng thái từ."); }
  }

  async function deleteNote(note: Note) {
    if (!window.confirm(`Xóa ghi chú từ “${note.word}”?`)) return;
    try {
      const response = await api(`/api/vocabulary-notes/${note.id}`, "DELETE");
      if (!response.ok) throw new Error();
      setNotes(current => current.filter(item => item.id !== note.id));
    } catch { setPageMessage("Chưa xóa được ghi chú. Hãy thử lại."); }
  }

  function beginStudy() { if (visibleNotes.length) { setStudyIndex(0); setCardFlipped(false); } }
  const studyNote = studyIndex === null ? null : visibleNotes[studyIndex];

  return <div className={styles.page}>
    <AppHeader active="vocabulary" user={user} onLogout={onLogout} />
    <main className={styles.main}>
      <div className={styles.pageHeading}><div><p className={styles.kicker}>SỔ CÁ NHÂN</p><h1>Sổ từ vựng</h1><p>Lưu lại những từ bạn gặp khi luyện Reading và ôn tập theo nhịp của mình.</p></div><button className={styles.primary} type="button" onClick={() => { setEditingNote(null); setTerm(""); setContext(""); setCustomMeaning(""); setEntries([]); setSelectedSenseId(null); setLookupState("idle"); setSaveState("idle"); setDrawerOpen(true); }}>+ Thêm từ vựng</button></div>
      <section className={styles.progress} aria-label="Tổng quan từ vựng"><div><span>Tổng số từ</span><strong>{totals.total}</strong></div><div className={styles.progressTrack} aria-hidden="true"><i style={{ width: `${totals.total ? totals.MASTERED / totals.total * 100 : 0}%` }} /></div><div className={styles.statusTotals}><span><i className={styles.newDot} />Chưa thuộc <b>{totals.NEW}</b></span><span><i className={styles.learningDot} />Nhớ sơ sơ <b>{totals.LEARNING}</b></span><span><i className={styles.masteredDot} />Đã thuộc <b>{totals.MASTERED}</b></span></div></section>
      <div className={styles.workspace}>
        <aside className={styles.rail} aria-label="Danh mục từ vựng">
          <div className={styles.railTitle}><h2>Danh mục của tôi</h2></div>
          <button type="button" className={`${styles.categoryButton} ${categoryId === null ? styles.categorySelected : ""}`} onClick={() => setCategoryId(null)}>Tất cả từ <span>{totals.total}</span></button>
          <div className={styles.categoryList}>{categories.map(category => <div className={styles.categoryRow} key={category.id}><button type="button" className={`${styles.categoryButton} ${categoryId === category.id ? styles.categorySelected : ""}`} onClick={() => setCategoryId(category.id)}>{category.name}<span>{category.noteCount}</span></button><button className={styles.categoryDelete} type="button" aria-label={`Xóa danh mục ${category.name}`} onClick={() => void deleteCategory(category)}>×</button></div>)}</div>
          <form className={styles.categoryForm} onSubmit={createCategory}><label htmlFor="new-category">Thêm danh mục</label><div><input id="new-category" value={categoryName} maxLength={80} onChange={event => setCategoryName(event.target.value)} placeholder="Ví dụ: Môi trường" /><button type="submit" aria-label="Tạo danh mục">+</button></div>{categoryError && <p role="alert">{categoryError}</p>}</form>
        </aside>
        <section className={styles.notesArea} aria-labelledby="notes-title">
          <div className={styles.listHeading}><div><h2 id="notes-title">{categoryId ? categories.find(category => category.id === categoryId)?.name : "Tất cả từ"}</h2><span>{visibleNotes.length} từ</span></div><div className={styles.listActions}><button type="button" className={styles.secondary} disabled={!visibleNotes.length} onClick={() => setPreviewOpen(true)}>Xem trước</button><button type="button" className={styles.primary} disabled={!visibleNotes.length} onClick={beginStudy}>Học với flashcards</button></div></div>
          <div className={styles.filters}><label className={styles.search}><span aria-hidden="true">⌕</span><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Tìm từ hoặc nghĩa đã lưu" /></label><label className={styles.statusFilter}>Trạng thái<select value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option value="ALL">Tất cả</option><option value="NEW">Chưa thuộc</option><option value="LEARNING">Nhớ sơ sơ</option><option value="MASTERED">Đã thuộc</option></select></label></div>
          {pageMessage && <p className={styles.pageMessage} role="alert">{pageMessage}</p>}{loadState === "loading" ? <p className={styles.empty}>Đang tải sổ từ vựng...</p> : loadState === "error" ? <div className={styles.empty} role="alert"><p>Không tải được dữ liệu từ vựng.</p><button type="button" onClick={() => setRetry(value => value + 1)}>Thử tải lại</button></div> : visibleNotes.length === 0 ? <div className={styles.empty}><strong>{notes.length ? "Không có từ phù hợp bộ lọc." : "Sổ từ vựng đang trống."}</strong><p>{notes.length ? "Thử chọn trạng thái khác hoặc xóa nội dung tìm kiếm." : "Tra một từ trong passage hoặc thêm từ mới để bắt đầu."}</p>{!notes.length && <button type="button" className={styles.primary} onClick={() => { setEditingNote(null); setDrawerOpen(true); }}>Thêm từ vựng</button>}</div> : <div className={styles.tableWrap}><table className={styles.notesTable}><thead><tr><th scope="col">Trạng thái</th><th scope="col">Từ vựng</th><th scope="col">Nghĩa đã lưu</th><th scope="col">Ngữ cảnh</th><th scope="col"><span className={styles.srOnly}>Thao tác</span></th></tr></thead><tbody>{visibleNotes.map(note => <Fragment key={note.id}><tr className={expandedNote === note.id ? styles.expanded : ""}><td><select aria-label={`Trạng thái ${note.word}`} className={`${styles.statusSelect} ${styles[note.reviewStatus.toLowerCase()]}`} value={note.reviewStatus} onChange={event => void changeStatus(note, event.target.value as Note["reviewStatus"])}><option value="NEW">Chưa thuộc</option><option value="LEARNING">Nhớ sơ sơ</option><option value="MASTERED">Đã thuộc</option></select></td><td><strong className={styles.word}>{note.word}</strong>{(note.pronunciation || note.audioUrl) && <span className={styles.wordPronunciation}>{note.pronunciation && <span className={styles.pronunciation}>{note.pronunciation}</span>}{note.audioUrl && <AudioButton source={note.audioUrl} word={note.word} />}</span>}</td><td><span className={styles.definition}>{note.selectedDefinition}</span>{note.customMeaning && <span className={styles.customMeaning}>{note.customMeaning}</span>}</td><td>{note.contextSentence ? <span className={styles.context}>{note.contextSentence}</span> : <span className={styles.context}>—</span>}{note.passageTitle && <span className={styles.passageSource}>{note.passageTitle}</span>}</td><td className={styles.rowActions}><button type="button" onClick={() => setExpandedNote(expandedNote === note.id ? null : note.id)}>{expandedNote === note.id ? "Thu gọn" : "Xem"}</button><button type="button" onClick={() => editNote(note)}>Sửa</button><button type="button" className={styles.deleteNote} onClick={() => void deleteNote(note)}>Xóa</button></td></tr>{expandedNote === note.id && <tr><td colSpan={5} className={styles.detailCell}><strong>Từ được lưu: {note.selectedText}</strong><p>{note.selectedDefinition}</p>{note.customMeaning && <p>Ghi chú của bạn: {note.customMeaning}</p>}{note.contextSentence && <blockquote>{note.contextSentence}</blockquote>}{note.passageTitle && <span>Nguồn: {note.passageTitle}</span>}</td></tr>}</Fragment>)}</tbody></table></div>}
        </section>
      </div>
    </main>
    {drawerOpen && <div className={styles.overlay} role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) { setDrawerOpen(false); setEditingNote(null); } }}><aside className={styles.drawer} role="dialog" aria-modal="true" aria-labelledby="drawer-title"><header><div><p className={styles.kicker}>SỔ TỪ VỰNG</p><h2 id="drawer-title">{editingNote ? "Sửa từ đã lưu" : "Thêm từ mới"}</h2></div><button type="button" aria-label="Đóng" onClick={() => { setDrawerOpen(false); setEditingNote(null); }}>×</button></header><form onSubmit={saveNote}>
      <label>{editingNote ? "Từ/cụm từ ghi nhớ" : "Từ hoặc cụm từ"}<input value={term} onChange={event => setTerm(event.target.value)} maxLength={100} placeholder="Nhập từ cần tra" /></label>{!editingNote && <button type="button" className={styles.primary} disabled={!term.trim() || lookupState === "loading"} onClick={() => void lookup()}>{lookupState === "loading" ? "Đang tra cứu..." : "Tra từ điển"}</button>}
      {lookupState === "error" && <p className={styles.lookupError} role="alert">{lookupMessage}</p>}
      {entries.length > 0 && <div className={styles.senseList}><p className={styles.senseHeading}>Chọn nghĩa phù hợp với ngữ cảnh</p>{entries.flatMap(entry => entry.senses.map(sense => <div className={styles.senseOption} key={sense.id}><input id={`vocabulary-sense-${sense.id}`} type="radio" name="selectedSense" checked={selectedSenseId === sense.id} onChange={() => setSelectedSenseId(sense.id)} /><div className={styles.senseOptionText}><label htmlFor={`vocabulary-sense-${sense.id}`}><b>{entry.word}</b><em>{sense.partOfSpeech}</em><span>{sense.definition}</span>{sense.example && <i>“{sense.example}”</i>}{sense.relatedWords.length > 0 && <small>{sense.relatedWords.map(item => `${item.relationType === "SYNONYM" ? "Đồng nghĩa" : "Trái nghĩa"}: ${item.word}`).join(" · ")}</small>}</label><div className={styles.sensePronunciation}>{sense.phoneticText && <small>{sense.phoneticText}</small>}{sense.audioUrl && <AudioButton source={sense.audioUrl} word={entry.word} />}</div></div></div>))}</div>}
      {editingNote && <p className={styles.senseHeading}>Nghĩa từ điển: {editingNote.selectedDefinition}</p>}<label>Ngữ cảnh (không bắt buộc)<textarea value={context} onChange={event => setContext(event.target.value)} maxLength={4000} placeholder="Có thể để trống nếu thêm từ ngoài bài đọc" /></label><label>Ghi chú riêng (không bắt buộc)<textarea value={customMeaning} onChange={event => setCustomMeaning(event.target.value)} maxLength={2000} placeholder="Cách hiểu hoặc bản dịch của bạn" /></label>
      {!editingNote && categories.length > 0 && <fieldset className={styles.categoryChecks}><legend>Lưu vào danh mục</legend>{categories.map(category => <label key={category.id}><input type="checkbox" checked={selectedCategories.includes(category.id)} onChange={event => setSelectedCategories(current => event.target.checked ? [...current, category.id] : current.filter(id => id !== category.id))} />{category.name}</label>)}</fieldset>}
      {saveMessage && <p className={styles.lookupError} role="alert">{saveMessage}</p>}<footer><button type="button" className={styles.secondary} onClick={() => { setDrawerOpen(false); setEditingNote(null); }}>Hủy</button><button type="submit" className={styles.primary} disabled={editingNote ? !term.trim() || saveState === "saving" : !selectedSenseId || !term.trim() || saveState === "saving"}>{saveState === "saving" ? "Đang lưu..." : editingNote ? "Lưu thay đổi" : "Lưu từ"}</button></footer>
    </form></aside></div>}
    {previewOpen && <div className={styles.overlay} role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setPreviewOpen(false); }}><section className={styles.previewPanel} role="dialog" aria-modal="true" aria-labelledby="preview-title"><header><div><p className={styles.kicker}>XEM TRƯỚC</p><h2 id="preview-title">{categoryId ? categories.find(category => category.id === categoryId)?.name : "Từ đã lưu"}</h2></div><button type="button" aria-label="Đóng xem trước" onClick={() => setPreviewOpen(false)}>×</button></header><div>{visibleNotes.slice(0, 12).map(note => <article key={note.id}><strong>{note.word}</strong>{(note.pronunciation || note.audioUrl) && <span className={styles.wordPronunciation}>{note.pronunciation && <small>{note.pronunciation}</small>}{note.audioUrl && <AudioButton source={note.audioUrl} word={note.word} />}</span>}<p>{note.selectedDefinition}</p>{note.customMeaning && <span>{note.customMeaning}</span>}</article>)}</div><footer><button type="button" className={styles.secondary} onClick={() => setPreviewOpen(false)}>Đóng</button></footer></section></div>}
    {studyNote && <div className={styles.overlay} role="presentation"><section className={styles.flashcard} role="dialog" aria-modal="true" aria-label="Học từ vựng"><header><span>{studyIndex! + 1} / {visibleNotes.length}</span><button type="button" aria-label="Đóng flashcard" onClick={() => setStudyIndex(null)}>×</button></header><button className={styles.cardFace} type="button" onClick={() => setCardFlipped(value => !value)}><small>{cardFlipped ? "NGHĨA ĐÃ LƯU" : "TỪ VỰNG"}</small><strong>{cardFlipped ? studyNote.selectedDefinition : studyNote.word}</strong>{cardFlipped && studyNote.customMeaning && <span>{studyNote.customMeaning}</span>}<em>Nhấn để {cardFlipped ? "xem từ" : "lật thẻ"}</em></button><div className={styles.flashActions}><button type="button" className={styles.secondary} onClick={() => { setStudyIndex((studyIndex! + 1) % visibleNotes.length); setCardFlipped(false); }}>Ôn tiếp</button><button type="button" className={styles.primary} onClick={() => { void changeStatus(studyNote, "MASTERED"); setStudyIndex(visibleNotes.length > 1 ? (studyIndex! + 1) % visibleNotes.length : null); setCardFlipped(false); }}>Đã thuộc</button></div></section></div>}
  </div>;
}
