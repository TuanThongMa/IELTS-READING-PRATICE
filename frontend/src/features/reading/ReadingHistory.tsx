import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api } from "../../api";
import styles from "./ReadingHistory.module.css";

type Passage = { id: number; title: string; partNumber: number };
type History = { entries: { attemptId: number; passageId: number; title: string; partNumber: number; submittedAt: string; correct: number; total: number; accuracy: number }[]; page: number; hasMore: boolean };

export function ReadingHistory() {
  const [params, setParams] = useSearchParams();
  const passageId = params.get("passageId") || "";
  const [passages, setPassages] = useState<Passage[]>([]);
  const [filterError, setFilterError] = useState(false);
  const [history, setHistory] = useState<History | null>(null);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("loading");
  useEffect(() => { let active = true; api("/api/reading/passages").then(async response => { if (!response.ok) throw new Error(); const data = await response.json() as Passage[]; if (active) setPassages(data); }).catch(() => { if (active) setFilterError(true); }); return () => { active = false; }; }, []);
  useEffect(() => {
    let active = true; setStatus("loading");
    const query = new URLSearchParams({ page: String(page) });
    if (passageId) query.set("passageId", passageId);
    api(`/api/reading/history?${query}`).then(async response => { if (!response.ok) throw new Error(); const data = await response.json() as History; if (active) { setHistory(data); setStatus("ready"); } }).catch(() => { if (active) setStatus("error"); });
    return () => { active = false; };
  }, [passageId, page]);
  return <main className={styles.page}><Link to="/reading">← Danh sách passage</Link><header className={styles.heading}><h1>Lịch sử làm bài</h1><p>Xem các lượt đã nộp, mới nhất trước.</p></header><label className={styles.filter}>Passage <select value={passageId} disabled={filterError} onChange={event => { setPage(0); setParams(event.target.value ? { passageId: event.target.value } : {}); }}><option value="">Tất cả passage</option>{passages.map(p => <option key={p.id} value={p.id}>Passage {p.partNumber} — {p.title}</option>)}</select></label>{filterError && <p role="alert">Không thể tải bộ lọc passage. Hãy tải lại trang.</p>}
    {status === "loading" ? <p role="status">Đang tải lịch sử...</p> : status === "error" ? <p role="alert">Không thể tải lịch sử. Hãy tải lại trang.</p> : history?.entries.length === 0 ? <p>Chưa có lượt làm bài đã nộp{passageId ? " cho passage này" : ""}.</p> : <><div className={styles.tableWrap}><table><thead><tr><th scope="col">Tên bài</th><th scope="col">Thời gian nộp</th><th scope="col">Câu đúng</th><th scope="col">Tỉ lệ đúng</th><th scope="col"><span className={styles.srOnly}>Kết quả</span></th></tr></thead><tbody>{history?.entries.map(entry => <tr key={entry.attemptId}><th scope="row"><span className={styles.part}>Passage {entry.partNumber}</span><br />{entry.title}</th><td>{new Date(entry.submittedAt).toLocaleString("vi-VN")}</td><td>{entry.correct}/{entry.total}</td><td>{Number(entry.accuracy.toFixed(1))}%</td><td><Link to={`/reading/attempts/${entry.attemptId}/result`}>Xem kết quả</Link></td></tr>)}</tbody></table></div><div className={styles.pagination}><button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>Trang trước</button><span>Trang {page + 1}</span><button type="button" disabled={!history?.hasMore} onClick={() => setPage(page + 1)}>Trang sau</button></div></>}
  </main>;
}
