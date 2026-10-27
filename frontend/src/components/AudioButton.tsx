import { useRef, useState } from "react";
import styles from "./AudioButton.module.css";

export function AudioButton({ source, word }: { source: string; word: string }) {
  const audio = useRef<HTMLAudioElement>(null);
  const [playing, setPlaying] = useState(false);
  const [failed, setFailed] = useState(false);

  async function togglePlayback() {
    const player = audio.current;
    if (!player) return;
    if (!player.paused) { player.pause(); return; }
    setFailed(false);
    try { await player.play(); }
    catch { setPlaying(false); setFailed(true); }
  }

  return <span className={styles.audioControl}>
    <audio ref={audio} className={styles.audioElement} src={source} preload="none" onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => setPlaying(false)} onError={() => { setPlaying(false); setFailed(true); }} />
    <button type="button" className={styles.audioButton} aria-label={failed ? `Không phát được âm thanh của ${word}` : playing ? `Dừng phát âm thanh của ${word}` : `Phát âm thanh của ${word}`} aria-pressed={playing} title={failed ? "Không phát được âm thanh" : playing ? "Dừng phát" : "Phát âm thanh"} onClick={() => void togglePlayback()}>
      <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M3 9v6h4l5 4V5L7 9H3Z" />{playing ? <path d="M15 8h2v8h-2zm4 0h2v8h-2z" /> : <path d="M15.5 8.5a5 5 0 0 1 0 7l1.4 1.4a7 7 0 0 0 0-9.8l-1.4 1.4Z" />}</svg>
    </button>
  </span>;
}
