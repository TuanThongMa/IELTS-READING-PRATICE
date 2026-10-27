import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../api";
import { AppHeader, type AccountUser } from "../../components/AppHeader";
import styles from "./ReadingDashboard.module.css";

type DayActivity = { date: string; submittedAttempts: number; studySeconds: number };
type DashboardData = { year: number; today: string; calendar: DayActivity[]; lastSevenDays: DayActivity[] };
type RecentWord = { id: number; word: string; selectedText: string; selectedDefinition: string; passageTitle: string | null; createdAt: string };

const weekdays = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"];
const monthNames = ["Tháng 1", "Tháng 2", "Tháng 3", "Tháng 4", "Tháng 5", "Tháng 6", "Tháng 7", "Tháng 8", "Tháng 9", "Tháng 10", "Tháng 11", "Tháng 12"];

function dateValue(date: string) {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatDate(date: string) {
  return dateValue(date).toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" });
}

function formatDuration(seconds: number) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  if (hours > 0) return `${hours} giờ ${minutes} phút`;
  return `${minutes} phút`;
}

function MonthCalendar({ year, month, today, activity, selectedDate, onSelect }: {
  year: number; month: number; today: string; activity: Map<string, DayActivity>; selectedDate: string;
  onSelect: (date: string) => void;
}) {
  const firstWeekday = (new Date(year, month, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  return <section className={styles.month} data-month-index={month} aria-label={monthNames[month]}>
    <h3>{monthNames[month]}</h3>
    <div className={styles.monthGrid}>
      {weekdays.map(day => <span className={styles.weekday} key={day}>{day}</span>)}
      {Array.from({ length: firstWeekday }, (_, index) => <span className={styles.emptyDay} key={`blank-${index}`} />)}
      {Array.from({ length: daysInMonth }, (_, index) => {
        const day = index + 1;
        const date = `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
        const details = activity.get(date);
        const submittedCount = details?.submittedAttempts ?? 0;
        const active = submittedCount > 0;
        const future = date > today;
        return <button
          className={`${styles.day} ${active ? styles.activeDay : ""} ${selectedDate === date ? styles.selectedDate : ""}`}
          key={date}
          type="button"
          disabled={future}
          aria-pressed={selectedDate === date}
          aria-label={`${day} tháng ${month + 1}${active ? `, đã nộp ${submittedCount} lượt` : ", chưa nộp bài"}`}
          title={active ? `${submittedCount} lượt nộp · ${formatDuration(details?.studySeconds ?? 0)}` : formatDate(date)}
          onClick={() => onSelect(date)}
        >{day}{active && <i aria-hidden="true" />}</button>;
      })}
    </div>
  </section>;
}

export function ReadingDashboard({ user, onLogout }: { user: AccountUser; onLogout: () => void | Promise<void> }) {
  const [data, setData] = useState<DashboardData | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [retry, setRetry] = useState(0);
  const [selectedDate, setSelectedDate] = useState("");
  const [recentWords, setRecentWords] = useState<RecentWord[]>([]);
  const [wordsLoaded, setWordsLoaded] = useState(false);
  const monthScroller = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let active = true;
    setStatus("loading");
    api("/api/reading/dashboard").then(async response => {
      if (!response.ok) throw new Error("Dashboard unavailable");
      const result = await response.json() as DashboardData;
      if (active) {
        setData(result);
        setSelectedDate(current => current || result.today);
        setStatus("ready");
      }
    }).catch(() => { if (active) setStatus("error"); });
    return () => { active = false; };
  }, [retry]);
  useEffect(() => {
    if (!data || !monthScroller.current) return;
    const currentMonth = dateValue(data.today).getMonth();
    const monthElement = monthScroller.current.querySelector<HTMLElement>(`[data-month-index="${currentMonth}"]`);
    if (monthElement) monthScroller.current.scrollTop = monthElement.offsetTop;
  }, [data]);
  useEffect(() => {
    let active = true;
    api("/api/vocabulary-notes/recent?limit=5").then(async response => {
      if (!response.ok) throw new Error("Recent vocabulary unavailable");
      const result = await response.json() as RecentWord[];
      if (active) setRecentWords(result);
    }).catch(() => { if (active) setRecentWords([]); }).finally(() => { if (active) setWordsLoaded(true); });
    return () => { active = false; };
  }, []);

  const activity = useMemo(() => new Map((data?.calendar ?? []).map(day => [day.date, day])), [data]);
  const selectedActivity = activity.get(selectedDate);
  const weeklyTotals = (data?.lastSevenDays ?? []).reduce((totals, day) => ({ attempts: totals.attempts + day.submittedAttempts, seconds: totals.seconds + day.studySeconds }), { attempts: 0, seconds: 0 });

  return <div className={styles.page}>
    <AppHeader active="home" user={user} onLogout={onLogout} />
    <main className={styles.main}>
      <div className={styles.intro}><div><h1>Chào {user.displayName}</h1><p>Theo dõi những ngày bạn đã nộp bài Reading và thời gian luyện tập.</p></div><Link to="/reading">Chọn bài Reading</Link></div>
      <div className={styles.dashboardGrid}>
        {status === "loading" ? <p className={`${styles.state} ${styles.dashboardState}`}>Đang tải thống kê Reading...</p> : status === "error" || !data ? <div className={`${styles.state} ${styles.dashboardState}`} role="alert"><p>Chưa tải được thống kê Reading.</p><button type="button" onClick={() => setRetry(value => value + 1)}>Thử tải lại</button></div> : <>
          <section className={styles.panel} aria-labelledby="calendar-title">
            <header className={styles.panelHeader}><div><h2 id="calendar-title">Hoạt động Reading năm {data.year}</h2><p>Cuộn lịch để xem các tháng khác.</p></div><span className={styles.legend}><i aria-hidden="true" /> Có nộp bài</span></header>
            <div ref={monthScroller} className={styles.months} role="region" aria-label="Lịch hoạt động, cuộn để xem từng tháng" tabIndex={0}>{monthNames.map((_, month) => <MonthCalendar key={month} year={data.year} month={month} today={data.today} activity={activity} selectedDate={selectedDate} onSelect={setSelectedDate} />)}</div>
            <div className={styles.selectedDay} aria-live="polite"><strong>{selectedDate ? formatDate(selectedDate) : "Chọn một ngày"}</strong><span>{selectedActivity?.submittedAttempts ?? 0} lượt đã nộp</span><span>{formatDuration(selectedActivity?.studySeconds ?? 0)}</span></div>
          </section>
          <section className={styles.panel} aria-labelledby="week-title">
            <header className={styles.panelHeader}><div><h2 id="week-title">7 ngày gần nhất</h2><p>Chỉ tính các lượt Reading đã nộp.</p></div></header>
            <div className={styles.tableWrap}><table className={styles.weekTable}><thead><tr><th scope="col">Ngày</th><th scope="col">Lượt nộp</th><th scope="col">Thời gian học</th></tr></thead><tbody>{data.lastSevenDays.map(day => <tr key={day.date}><th scope="row">{formatDate(day.date)}</th><td>{day.submittedAttempts || "–"}</td><td>{formatDuration(day.studySeconds)}</td></tr>)}</tbody><tfoot><tr><th scope="row">Tổng cộng</th><td>{weeklyTotals.attempts}</td><td>{formatDuration(weeklyTotals.seconds)}</td></tr></tfoot></table></div>
          </section>
          <section className={`${styles.panel} ${styles.recentWords}`} aria-labelledby="recent-words-title">
            <header className={styles.panelHeader}><div><h2 id="recent-words-title">Từ vựng mới lưu</h2><p>Những từ bạn vừa thêm vào sổ cá nhân.</p></div><Link to="/vocabulary">Mở sổ từ vựng</Link></header>
            {!wordsLoaded ? <p className={styles.recentState}>Đang tải từ đã lưu...</p> : recentWords.length === 0 ? <p className={styles.recentState}>Bạn chưa lưu từ nào. Khi luyện Reading, hãy chọn một từ để tra cứu và lưu lại.</p> : <ul className={styles.recentList}>{recentWords.map(word => <li key={word.id}><div><strong>{word.word}</strong><p>{word.selectedDefinition}</p>{word.passageTitle && <span>{word.passageTitle}</span>}</div><time dateTime={word.createdAt}>{new Date(word.createdAt).toLocaleDateString("vi-VN")}</time></li>)}</ul>}
          </section>
        </>}
      </div>
    </main>
  </div>;
}
