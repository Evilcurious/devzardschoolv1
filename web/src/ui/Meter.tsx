import type { Meter as MeterData } from "../api/saas";

/** Usage bar for one limited resource (users, branches, storage). */
export default function Meter({ label, data, format }: { label: string; data: MeterData; format?: (n: number) => string }) {
  const show = format || ((n: number) => n.toLocaleString());
  const pct = Math.max(0, Math.min(100, data.percent));
  const tone = data.over || data.full ? "bad" : pct >= 80 ? "warn" : "ok";
  return (
    <div className={`meter ${tone}`}>
      <div className="meter-head">
        <span>{label}</span>
        <b>{show(data.used)} <small>of {show(data.limit)}</small></b>
      </div>
      <div className="meter-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}>
        <i style={{ width: `${pct}%` }} />
      </div>
      <small className="meter-foot">
        {data.over ? "Over the limit. Nothing is deleted, but new items are blocked."
          : data.full ? "Limit reached. New items are blocked until you upgrade or free some space."
          : `${show(data.remaining)} remaining`}
      </small>
    </div>
  );
}
