import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  fmtBytes,
  fmtMb,
  platformApi,
  type Plan,
  type PlatformSchoolDetail,
  type PlatformSite,
  type ReviewAction,
  type VerificationDocument,
} from "../../api/saas";
import { resetPlatformSite } from "../../lib/platform";
import { useToast } from "../../lib/toast";
import { fmtDate } from "../../lib/format";
import { Badge, Button, Empty, Field, FileLink, Form, Modal, QueryState, Search, Stat, Table, useAsync, useDebounced } from "../../ui/kit";
import Meter from "../../ui/Meter";

const STATUS_TABS: { id: string; label: string }[] = [
  { id: "PENDING", label: "Pending" },
  { id: "UNDER_REVIEW", label: "Under review" },
  { id: "VERIFIED", label: "Verified" },
  { id: "REJECTED", label: "Rejected" },
  { id: "SUSPENDED", label: "Suspended" },
  { id: "", label: "All" },
];

function uptime(seconds: number) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
}

function Gauge({ label, used, total, unit, warn }: { label: string; used?: number; total?: number; unit: string; warn: number }) {
  if (used == null || !total) {
    return <div className="meter"><div className="meter-head"><span>{label}</span><b>Not available</b></div></div>;
  }
  const pct = Math.min(100, Math.round((used / total) * 100));
  return (
    <div className={`meter ${pct >= warn ? "bad" : pct >= warn - 15 ? "warn" : "ok"}`}>
      <div className="meter-head"><span>{label}</span><b>{pct}% <small>{unit === "%" ? "" : `${fmtMb(used)} of ${fmtMb(total)}`}</small></b></div>
      <div className="meter-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}><i style={{ width: `${pct}%` }} /></div>
    </div>
  );
}

/** Platform Owner: schools by status, real server figures, schools near their limits. */
export function PlatformOverviewPage() {
  const q = useAsync(() => platformApi.overview(), []);
  useEffect(() => {
    const id = window.setInterval(() => { void q.reload(); }, 20_000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const d = q.data;
  return (
    <div>
      <div className="page-title">
        <div>
          <h1>Platform</h1>
          <p>Schools, verification queue and this server's real load.</p>
        </div>
        <Button kind="ghost" loadingText="Refreshing…" onClick={() => q.reload()}>Refresh</Button>
      </div>
      <QueryState status={q} label="platform overview">
        {d ? (
          <>
            {d.schools.pending + d.schools.underReview > 0 ? (
              <div className="notice">
                <strong>{d.schools.pending + d.schools.underReview}</strong> school application{d.schools.pending + d.schools.underReview === 1 ? "" : "s"} waiting for review.{" "}
                <Link to="/admin/app/verification">Open the queue</Link>
              </div>
            ) : null}
            <div className="grid stats">
              <Stat label="Schools" value={d.schools.total} />
              <Stat label="Verified" value={d.schools.verified} />
              <Stat label="Pending review" value={d.schools.pending + d.schools.underReview} />
              <Stat label="Suspended" value={d.schools.suspended} />
              <Stat label="Active users" value={d.totals.activeUsers.toLocaleString()} />
              <Stat label="Branches" value={d.totals.branches} />
              <Stat label="Students" value={d.totals.students.toLocaleString()} />
              <Stat label="Files stored" value={fmtBytes(d.totals.storageBytes)} />
            </div>

            <h2>Server</h2>
            <div className="grid three meters">
              <div className="card"><Gauge label="CPU" used={d.server.cpuPercent} total={100} unit="%" warn={d.thresholds.cpuWarnPercent} /></div>
              <div className="card"><Gauge label="Server RAM" used={d.server.systemRamUsedMb} total={d.server.systemRamTotalMb} unit="MB" warn={d.thresholds.ramWarnPercent} /></div>
              <div className="card"><Gauge label="Disk" used={d.server.diskUsedMb} total={d.server.diskTotalMb} unit="MB" warn={d.thresholds.diskWarnPercent} /></div>
            </div>
            <dl className="facts card" style={{ marginTop: 14 }}>
              <div><dt>Application memory</dt><dd>{fmtMb(d.server.heapUsedMb)} of {fmtMb(d.server.heapMaxMb)}</dd></div>
              <div><dt>CPU cores</dt><dd>{d.server.processors}</dd></div>
              <div><dt>Uptime</dt><dd>{uptime(d.server.uptimeSeconds)}</dd></div>
              <div><dt>Database size</dt><dd>{d.totals.databaseBytes != null ? fmtBytes(d.totals.databaseBytes) : "—"}</dd></div>
              <div><dt>Requests, last 5 minutes</dt><dd>{d.recentRequests.toLocaleString()}</dd></div>
              <div><dt>RAM promised to schools</dt><dd>{fmtMb(d.totals.ramBudgetAllocatedMb)}{d.server.systemRamTotalMb ? ` (server has ${fmtMb(d.server.systemRamTotalMb)})` : ""}</dd></div>
              <div><dt>Storage promised to schools</dt><dd>{fmtMb(d.totals.storageAllocatedMb)}{d.server.diskTotalMb ? ` (disk has ${fmtMb(d.server.diskTotalMb)})` : ""}</dd></div>
            </dl>
            <p className="hint" style={{ marginTop: 10 }}>
              All schools share this one server. A school's RAM figure is a planning budget, not a hard reservation: the server cannot give one school its own memory. Users, branches and storage are hard limits.
            </p>

            <div className="grid two" style={{ marginTop: 14 }}>
              <div className="card">
                <h3 style={{ marginTop: 0 }}>Schools near a limit</h3>
                {d.nearLimit.length ? (
                  <Table
                    headers={["School", "Users", "Branches", "Storage"]}
                    rows={d.nearLimit.map((n) => [
                      <Link key="l" to={`/admin/app/verification/${n.id}`}>{n.name}</Link>,
                      `${n.activeUsers} / ${n.maxActiveUsers}`,
                      `${n.branches} / ${n.maxBranches}`,
                      `${fmtBytes(n.storageBytes)} / ${fmtMb(n.storageLimitMb)}`,
                    ])}
                  />
                ) : <p className="hint">No school is close to a limit.</p>}
              </div>
              <div className="card">
                <h3 style={{ marginTop: 0 }}>Schools by plan</h3>
                <Table headers={["Plan", "Schools"]} rows={d.plans.map((p) => [p.name, p.schools])} />
              </div>
            </div>
          </>
        ) : null}
      </QueryState>
    </div>
  );
}

/** Platform Owner: every school with its verification status, plan and usage. */
export function PlatformSchoolsPage() {
  const [status, setStatus] = useState("PENDING");
  const [search, setSearch] = useState("");
  const q = useDebounced(search, 250);
  const list = useAsync(() => platformApi.schools(status || undefined, q || undefined), [status, q]);
  return (
    <div>
      <div className="page-title">
        <div>
          <h1>School verification</h1>
          <p>Review applications, approve or reject schools, and manage plans and limits.</p>
        </div>
        <Search value={search} onChange={setSearch} placeholder="Search name, code, city, email" />
      </div>
      <div className="children">
        {STATUS_TABS.map((t) => (
          <button key={t.id || "all"} type="button" className={`chip ${status === t.id ? "on" : ""}`} onClick={() => setStatus(t.id)}>{t.label}</button>
        ))}
      </div>
      <QueryState status={list} label="schools">
        {list.data?.length ? (
          <Table
            headers={["School", "Owner", "Status", "Plan", "Users", "Branches", "Storage", "Registered", ""]}
            rows={list.data.map((s) => [
              <div key="n"><strong>{s.name}</strong><div className="hint" style={{ margin: 0 }}>{[s.code, s.city].filter(Boolean).join(" · ")}</div></div>,
              <div key="o">{s.ownerName || "—"}<div className="hint" style={{ margin: 0 }}>{s.ownerEmail}</div></div>,
              <span key="s" className="row"><Badge value={s.verificationStatus} />{s.documentsRequested ? <span className="badge warn">Docs requested</span> : null}</span>,
              <span key="p">{s.planName || "—"}{s.hasOverride ? " *" : ""}</span>,
              `${s.activeUsers ?? 0} / ${s.maxActiveUsers ?? "—"}`,
              `${s.branches ?? 0} / ${s.maxBranches ?? "—"}`,
              `${fmtBytes(s.storageBytes)} / ${fmtMb(s.storageLimitMb)}`,
              s.registeredAt ? fmtDate(s.registeredAt) : "—",
              <Link key="l" className="btn ghost sm" to={`/admin/app/verification/${s.id}`}>Open</Link>,
            ])}
          />
        ) : <Empty title="No schools in this view." hint="Try another status or clear the search." />}
      </QueryState>
      <p className="hint" style={{ marginTop: 10 }}>* custom limits are set for this school.</p>
    </div>
  );
}

const ACTIONS: Record<string, { action: ReviewAction; label: string; kind: "brass" | "ghost" | "danger"; note?: "required" | "optional" }[]> = {
  PENDING: [
    { action: "APPROVE", label: "Approve school", kind: "brass", note: "optional" },
    { action: "UNDER_REVIEW", label: "Mark under review", kind: "ghost" },
    { action: "REQUEST_DOCUMENTS", label: "Request documents", kind: "ghost", note: "required" },
    { action: "REJECT", label: "Reject", kind: "danger", note: "required" },
  ],
  UNDER_REVIEW: [
    { action: "APPROVE", label: "Approve school", kind: "brass", note: "optional" },
    { action: "REQUEST_DOCUMENTS", label: "Request documents", kind: "ghost", note: "required" },
    { action: "REJECT", label: "Reject", kind: "danger", note: "required" },
  ],
  VERIFIED: [{ action: "SUSPEND", label: "Suspend school", kind: "danger", note: "required" }],
  REJECTED: [
    { action: "UNDER_REVIEW", label: "Reopen for review", kind: "ghost" },
    { action: "APPROVE", label: "Approve school", kind: "brass", note: "optional" },
  ],
  SUSPENDED: [{ action: "REACTIVATE", label: "Reactivate school", kind: "brass" }],
};

const NOTE_LABEL: Partial<Record<ReviewAction, string>> = {
  APPROVE: "Note for your records (optional)",
  REQUEST_DOCUMENTS: "What should the school upload?",
  REJECT: "Reason shown to the school",
  SUSPEND: "Reason shown to the school",
};

export function PlatformSchoolDetailPage() {
  const { id = "" } = useParams();
  const toast = useToast();
  const q = useAsync(() => platformApi.school(id), [id]);
  const plans = useAsync(() => platformApi.plans(), []);
  const [ask, setAsk] = useState<{ action: ReviewAction; label: string; required: boolean } | null>(null);
  const [note, setNote] = useState("");
  const [limits, setLimits] = useState(false);
  const [plan, setPlan] = useState(false);
  const d = q.data;

  const run = async (action: ReviewAction, text?: string) => {
    q.setData(await platformApi.review(id, action, text));
    toast("ok", "Saved.");
  };

  return (
    <div>
      <Link to="/admin/app/verification" className="back-link">← All schools</Link>
      <QueryState status={q} label="school">
        {d ? (
          <>
            <div className="page-title" style={{ marginTop: 10 }}>
              <div>
                <h1>{d.schoolName}</h1>
                <p>{[d.code, d.profile.city, d.profile.country].filter(Boolean).join(" · ")}</p>
              </div>
              <Badge value={d.verificationStatus} />
            </div>

            <div className="card" style={{ marginBottom: 14 }}>
              <h3 style={{ marginTop: 0 }}>Decision</h3>
              {d.application?.rejectionReason && d.verificationStatus === "REJECTED" ? <p className="notice">Rejected: {d.application.rejectionReason}</p> : null}
              {d.application?.suspensionReason && d.verificationStatus === "SUSPENDED" ? <p className="notice">Suspended: {d.application.suspensionReason}</p> : null}
              {d.application?.documentsRequested ? <p className="notice">More documents were requested from this school.</p> : null}
              <div className="row">
                {(ACTIONS[d.verificationStatus] || []).map((a) => (
                  <Button
                    key={a.action}
                    kind={a.kind}
                    loadingText="Saving…"
                    onClick={async () => {
                      if (a.note) {
                        setNote("");
                        setAsk({ action: a.action, label: a.label, required: a.note === "required" });
                        return;
                      }
                      await run(a.action);
                    }}
                  >
                    {a.label}
                  </Button>
                ))}
              </div>
              <p className="hint" style={{ margin: "10px 0 0" }}>Suspending or rejecting never deletes a school's data. Approving unlocks the school system for this school only.</p>
            </div>

            <div className="grid two">
              <div className="card">
                <h3 style={{ marginTop: 0 }}>School</h3>
                <dl className="facts">
                  <div><dt>School type</dt><dd>{d.application?.schoolType || d.profile.schoolType || "—"}</dd></div>
                  <div><dt>Address</dt><dd>{[d.application?.address || d.profile.address, d.profile.city, d.profile.country].filter(Boolean).join(", ") || "—"}</dd></div>
                  <div><dt>Contact number</dt><dd>{d.application?.contactNumber || d.profile.phone || "—"}</dd></div>
                  <div><dt>Official email</dt><dd>{d.application?.officialEmail || d.profile.email || "—"}</dd></div>
                  <div><dt>Owner or principal</dt><dd>{d.application?.principalName || d.profile.principalName || "—"}</dd></div>
                  <div><dt>Registration details</dt><dd>{d.application?.registrationDetails || "—"}</dd></div>
                  <div><dt>Registered</dt><dd>{d.registeredAt ? fmtDate(d.registeredAt) : "—"}</dd></div>
                </dl>
              </div>
              <div className="card">
                <h3 style={{ marginTop: 0 }}>School Owner account</h3>
                <dl className="facts">
                  <div><dt>Name</dt><dd>{d.profile.ownerName || "—"}</dd></div>
                  <div><dt>Login email</dt><dd>{d.profile.ownerEmail || "—"}</dd></div>
                  <div><dt>Username</dt><dd>{d.profile.ownerUsername || "—"}</dd></div>
                  <div><dt>Last sign-in</dt><dd>{d.profile.ownerLastLoginAt ? fmtDate(d.profile.ownerLastLoginAt) : "Never"}</dd></div>
                  <div><dt>Requests, last 5 minutes</dt><dd>{d.recentRequests ?? 0}</dd></div>
                </dl>
              </div>
            </div>

            <div className="card" style={{ marginTop: 14 }}>
              <h3 style={{ marginTop: 0 }}>Verification documents</h3>
              {d.documents.length ? (
                <ul className="doc-list">
                  {d.documents.map((doc) => <DocumentRow key={doc.id} schoolId={id} doc={doc} onChange={(next) => q.setData(next)} />)}
                </ul>
              ) : <p className="hint">This school has not uploaded any document.</p>}
            </div>

            <div className="card" style={{ marginTop: 14 }}>
              <div className="page-title" style={{ marginBottom: 12 }}>
                <div>
                  <h3 style={{ margin: 0 }}>Plan and limits</h3>
                  <p>{d.resources.limits.subscribedPlanName || d.resources.limits.planName} plan{d.resources.limits.hasOverride ? " · custom limits" : ""}{d.resources.limits.planExpired ? " · expired, Free limits apply" : ""}</p>
                </div>
                <div className="row">
                  <Button kind="ghost" onClick={() => setPlan(true)}>Change plan</Button>
                  <Button kind="ghost" onClick={() => setLimits(true)}>Custom limits</Button>
                </div>
              </div>
              <div className="grid three meters">
                <Meter label="Active users" data={d.resources.users} />
                <Meter label="Branches" data={d.resources.branches} />
                <Meter label="Storage" data={d.resources.storage} format={fmtBytes} />
              </div>
              <p className="hint" style={{ margin: "12px 0 0" }}>RAM budget: {fmtMb(d.resources.limits.ramBudgetMb)} (planning figure). {d.resources.usage.students} students, {d.resources.usage.totalUsers} accounts in total.</p>
              {d.subscriptions.length ? (
                <div style={{ marginTop: 12 }}>
                  <Table
                    headers={["Plan", "Status", "Billing", "Started", "Ended", "Note"]}
                    rows={d.subscriptions.map((s) => [s.planName, <Badge key="b" value={s.status} />, s.billingCycle || "—", s.startedAt ? fmtDate(s.startedAt) : "—", s.endedAt ? fmtDate(s.endedAt) : "—", s.notes || "—"])}
                  />
                </div>
              ) : null}
            </div>

            <div className="card" style={{ marginTop: 14 }}>
              <h3 style={{ marginTop: 0 }}>Branches</h3>
              {d.branches.length ? (
                <Table
                  headers={["Branch", "Status", "Users", "Students"]}
                  rows={d.branches.map((b) => [<span key="n">{b.name}{b.main ? " (main)" : ""}</span>, <Badge key="s" value={b.status} />, b.users ?? 0, b.students ?? 0])}
                />
              ) : <p className="hint">No branch yet. A main branch is created when the school is approved.</p>}
            </div>

            <Modal title={ask?.label || ""} open={Boolean(ask)} onClose={() => setAsk(null)}>
              {ask ? (
                <Form
                  onSubmit={async () => {
                    if (ask.required && !note.trim()) throw new Error("Please write a short reason.");
                    await run(ask.action, note.trim());
                    setAsk(null);
                  }}
                >
                  <Field label={NOTE_LABEL[ask.action] || "Note"}>
                    <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={1000} required={ask.required} autoFocus />
                  </Field>
                  <Button type="submit" kind={ask.action === "REJECT" || ask.action === "SUSPEND" ? "danger" : "brass"}>{ask.label}</Button>
                </Form>
              ) : null}
            </Modal>
            <Modal title="Custom limits" open={limits} onClose={() => setLimits(false)}>
              {limits ? <LimitsForm id={id} detail={d} onDone={() => { setLimits(false); void q.reload(); }} /> : null}
            </Modal>
            <Modal title="Change plan" open={plan} onClose={() => setPlan(false)}>
              {plan ? <PlanAssignForm id={id} detail={d} plans={plans.data || []} onDone={() => { setPlan(false); void q.reload(); }} /> : null}
            </Modal>
          </>
        ) : null}
      </QueryState>
    </div>
  );
}

function DocumentRow({ schoolId, doc, onChange }: { schoolId: string; doc: VerificationDocument; onChange: (d: PlatformSchoolDetail) => void }) {
  const toast = useToast();
  return (
    <li>
      <div>
        <strong>{doc.documentType}</strong>
        <small>{doc.fileName} · {fmtBytes(doc.sizeBytes)}{doc.uploadedAt ? ` · ${fmtDate(doc.uploadedAt)}` : ""}</small>
        {doc.reviewNote ? <small className="field-err">{doc.reviewNote}</small> : null}
      </div>
      <Badge value={doc.status} />
      <FileLink href={platformApi.documentUrl(schoolId, doc.id)} label="View" />
      <Button kind="ghost" loadingText="Saving…" onClick={async () => { onChange(await platformApi.reviewDocument(schoolId, doc.id, "ACCEPTED")); toast("ok", "Document accepted."); }}>Accept</Button>
      <Button
        kind="ghost"
        loadingText="Saving…"
        onClick={async () => {
          const reason = window.prompt("Why is this document not acceptable? The school will see this.");
          if (!reason || !reason.trim()) return;
          onChange(await platformApi.reviewDocument(schoolId, doc.id, "REJECTED", reason.trim()));
          toast("ok", "Document rejected.");
        }}
      >
        Reject
      </Button>
    </li>
  );
}

function LimitsForm({ id, detail, onDone }: { id: string; detail: PlatformSchoolDetail; onDone: () => void }) {
  const toast = useToast();
  const l = detail.resources.limits;
  const [f, setF] = useState({
    maxActiveUsers: String(l.maxActiveUsers),
    maxBranches: String(l.maxBranches),
    storageLimitMb: String(l.storageLimitMb),
    ramBudgetMb: String(l.ramBudgetMb),
    note: "",
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  return (
    <Form
      onSubmit={async () => {
        await platformApi.setLimits(id, {
          maxActiveUsers: Number(f.maxActiveUsers),
          maxBranches: Number(f.maxBranches),
          storageLimitMb: Number(f.storageLimitMb),
          ramBudgetMb: Number(f.ramBudgetMb),
          note: f.note || undefined,
        });
        toast("ok", "Custom limits saved.");
        onDone();
      }}
    >
      <p className="hint">These replace the plan's limits for this school only. Setting a limit below current use deletes nothing: it only blocks new users, branches or uploads.</p>
      <div className="reg-grid">
        <Field label="Active users"><input type="number" min={0} value={f.maxActiveUsers} onChange={set("maxActiveUsers")} required /></Field>
        <Field label="Branches"><input type="number" min={0} value={f.maxBranches} onChange={set("maxBranches")} required /></Field>
        <Field label="Storage (MB)"><input type="number" min={0} value={f.storageLimitMb} onChange={set("storageLimitMb")} required /></Field>
        <Field label="RAM budget (MB)"><input type="number" min={0} value={f.ramBudgetMb} onChange={set("ramBudgetMb")} required /></Field>
      </div>
      <Field label="Note (optional)"><input value={f.note} onChange={set("note")} maxLength={300} /></Field>
      <div className="row">
        <Button type="submit" kind="brass">Save custom limits</Button>
        {l.hasOverride ? (
          <Button kind="ghost" loadingText="Removing…" onClick={async () => { await platformApi.setLimits(id, {}); toast("ok", "Back to plan limits."); onDone(); }}>
            Use plan limits
          </Button>
        ) : null}
      </div>
    </Form>
  );
}

function PlanAssignForm({ id, detail, plans, onDone }: { id: string; detail: PlatformSchoolDetail; plans: Plan[]; onDone: () => void }) {
  const toast = useToast();
  const [planCode, setPlanCode] = useState(detail.resources.limits.subscribedPlanCode || "");
  const [billingCycle, setBillingCycle] = useState("NONE");
  const [expiresAt, setExpiresAt] = useState("");
  const [notes, setNotes] = useState("");
  const chosen = plans.find((p) => p.code === planCode);
  return (
    <Form
      onSubmit={async () => {
        await platformApi.setPlan(id, { planCode, billingCycle, expiresAt: expiresAt || undefined, notes: notes || undefined });
        toast("ok", "Plan changed.");
        onDone();
      }}
    >
      <Field label="Plan">
        <select value={planCode} onChange={(e) => setPlanCode(e.target.value)} required>
          <option value="">Choose…</option>
          {plans.filter((p) => p.isActive !== false).map((p) => <option key={p.id} value={p.code}>{p.name}</option>)}
        </select>
      </Field>
      {chosen ? <p className="hint">{chosen.maxActiveUsers.toLocaleString()} users · {chosen.maxBranches} branches · {fmtMb(chosen.storageLimitMb)} storage · {fmtMb(chosen.ramBudgetMb)} RAM budget</p> : null}
      <div className="reg-grid">
        <Field label="Billing">
          <select value={billingCycle} onChange={(e) => setBillingCycle(e.target.value)}>
            <option value="NONE">No billing</option>
            <option value="MONTHLY">Monthly</option>
            <option value="YEARLY">Yearly</option>
          </select>
        </Field>
        <Field label="Expires on (optional)"><input type="date" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} /></Field>
      </div>
      <Field label="Note (optional)"><input value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={300} placeholder="For example: paid by bank transfer" /></Field>
      <p className="hint">The previous plan stays in the school's history. When a plan expires the school falls back to Free limits and keeps all its data.</p>
      <Button type="submit" kind="brass">Change plan</Button>
    </Form>
  );
}

/** Platform Owner: create and edit plans. Limits are data, never code. */
export function PlatformPlansPage() {
  const q = useAsync(() => platformApi.plans(), []);
  const [edit, setEdit] = useState<Plan | "new" | null>(null);
  return (
    <div>
      <div className="page-title">
        <div>
          <h1>Plans</h1>
          <p>Limits each school gets. Changing a plan here changes it for every school on that plan.</p>
        </div>
        <Button kind="brass" onClick={() => setEdit("new")}>New plan</Button>
      </div>
      <QueryState status={q} label="plans">
        <Table
          headers={["Plan", "Users", "Branches", "Storage", "RAM budget", "Monthly", "Schools", "Shown", ""]}
          rows={(q.data || []).map((p) => [
            <div key="n"><strong>{p.name}</strong><div className="hint" style={{ margin: 0 }}>{p.code}{p.isDefault ? " · default" : ""}</div></div>,
            p.maxActiveUsers.toLocaleString(),
            p.maxBranches,
            fmtMb(p.storageLimitMb),
            fmtMb(p.ramBudgetMb),
            Number(p.priceMonthly || 0) > 0 ? `${p.currency || "PKR"} ${Number(p.priceMonthly).toLocaleString()}` : "Free",
            p.schools ?? 0,
            <span key="s" className="row">{p.isActive === false ? <span className="badge bad">Inactive</span> : <span className="badge ok">Active</span>}{p.isPublic ? <span className="badge mute">Public</span> : <span className="badge mute">Hidden</span>}</span>,
            <button key="e" type="button" className="btn ghost sm" onClick={() => setEdit(p)}>Edit</button>,
          ])}
        />
      </QueryState>
      <Modal title={edit === "new" ? "New plan" : edit ? `Edit ${edit.name}` : ""} open={Boolean(edit)} onClose={() => setEdit(null)}>
        {edit ? <PlanForm plan={edit === "new" ? null : edit} onDone={() => { setEdit(null); resetPlatformSite(); void q.reload(); }} /> : null}
      </Modal>
    </div>
  );
}

function PlanForm({ plan, onDone }: { plan: Plan | null; onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState({
    code: plan?.code || "",
    name: plan?.name || "",
    description: plan?.description || "",
    maxActiveUsers: String(plan?.maxActiveUsers ?? 120),
    maxBranches: String(plan?.maxBranches ?? 1),
    storageLimitMb: String(plan?.storageLimitMb ?? 2048),
    ramBudgetMb: String(plan?.ramBudgetMb ?? 2048),
    priceMonthly: String(plan?.priceMonthly ?? 0),
    priceYearly: String(plan?.priceYearly ?? 0),
    sortOrder: String(plan?.sortOrder ?? 100),
    features: (plan?.features || []).join("\n"),
  });
  const [isPublic, setIsPublic] = useState(plan?.isPublic ?? true);
  const [isActive, setIsActive] = useState(plan?.isActive ?? true);
  const [isDefault, setIsDefault] = useState(plan?.isDefault ?? false);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  return (
    <Form
      onSubmit={async () => {
        const body = {
          code: f.code,
          name: f.name,
          description: f.description,
          maxActiveUsers: Number(f.maxActiveUsers),
          maxBranches: Number(f.maxBranches),
          storageLimitMb: Number(f.storageLimitMb),
          ramBudgetMb: Number(f.ramBudgetMb),
          priceMonthly: Number(f.priceMonthly),
          priceYearly: Number(f.priceYearly),
          sortOrder: Number(f.sortOrder),
          features: f.features.split("\n").map((x) => x.trim()).filter(Boolean),
          isPublic,
          isActive,
          isDefault,
        };
        if (plan) await platformApi.updatePlan(plan.id, body);
        else await platformApi.createPlan(body);
        toast("ok", "Plan saved.");
        onDone();
      }}
    >
      <div className="reg-grid">
        <Field label="Name"><input value={f.name} onChange={set("name")} maxLength={80} required /></Field>
        <Field label="Code"><input value={f.code} onChange={set("code")} maxLength={40} required disabled={Boolean(plan)} placeholder="For example PRO" /></Field>
        <Field label="Active users"><input type="number" min={1} value={f.maxActiveUsers} onChange={set("maxActiveUsers")} required /></Field>
        <Field label="Branches"><input type="number" min={1} value={f.maxBranches} onChange={set("maxBranches")} required /></Field>
        <Field label="Storage (MB)"><input type="number" min={1} value={f.storageLimitMb} onChange={set("storageLimitMb")} required /></Field>
        <Field label="RAM budget (MB)"><input type="number" min={1} value={f.ramBudgetMb} onChange={set("ramBudgetMb")} required /></Field>
        <Field label="Price per month"><input type="number" min={0} step="0.01" value={f.priceMonthly} onChange={set("priceMonthly")} /></Field>
        <Field label="Price per year"><input type="number" min={0} step="0.01" value={f.priceYearly} onChange={set("priceYearly")} /></Field>
        <Field label="Order on the website"><input type="number" value={f.sortOrder} onChange={set("sortOrder")} /></Field>
      </div>
      <Field label="Description"><input value={f.description} onChange={set("description")} maxLength={300} /></Field>
      <Field label="Extra points shown on the website (one per line)"><textarea value={f.features} onChange={set("features")} /></Field>
      <div className="module-grid">
        <label className="module"><input type="checkbox" checked={isPublic} onChange={(e) => setIsPublic(e.target.checked)} /><span>Show on the public website</span></label>
        <label className="module"><input type="checkbox" checked={isActive} onChange={(e) => setIsActive(e.target.checked)} /><span>Active (can be given to schools)</span></label>
        <label className="module"><input type="checkbox" checked={isDefault} onChange={(e) => setIsDefault(e.target.checked)} /><span>Default plan for new schools</span></label>
      </div>
      <Button type="submit" kind="brass">Save plan</Button>
    </Form>
  );
}

type Settings = Omit<PlatformSite, "plans"> & { monitoring: { cpuWarnPercent: number; ramWarnPercent: number; diskWarnPercent: number; nearLimitPercent: number } };

const SITE_TABS = [
  { id: "branding", label: "Branding" },
  { id: "homepage", label: "Homepage" },
  { id: "navigation", label: "Menu and footer" },
  { id: "contact", label: "Contact" },
  { id: "announcement", label: "Announcement" },
  { id: "registration", label: "Registration" },
  { id: "monitoring", label: "Alerts" },
] as const;

/** Platform Owner: edit the public website without touching code. */
export function PlatformSitePage() {
  const toast = useToast();
  const q = useAsync(() => platformApi.settings() as Promise<unknown> as Promise<Settings>, []);
  const [tab, setTab] = useState<(typeof SITE_TABS)[number]["id"]>("branding");
  const [draft, setDraft] = useState<Settings | null>(null);
  useEffect(() => {
    if (q.data && !draft) setDraft(structuredClone(q.data));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q.data]);

  const save = async (...keys: (keyof Settings)[]) => {
    if (!draft) return;
    const tidy = (list?: string[]) => (list || []).map((x) => x.trim()).filter(Boolean);
    const clean: Settings = {
      ...draft,
      registration: { ...draft.registration, schoolTypes: tidy(draft.registration.schoolTypes), documentTypes: tidy(draft.registration.documentTypes) },
      navigation: (draft.navigation || []).filter((l) => l.label.trim() && l.to.trim()),
      footer: { ...draft.footer, links: (draft.footer.links || []).filter((l) => l.label.trim() && l.to.trim()) },
      homepage: {
        ...draft.homepage,
        features: (draft.homepage.features || []).filter((f) => f.title.trim()),
        steps: (draft.homepage.steps || []).filter((f) => f.title.trim()),
      },
    };
    for (const key of keys) await platformApi.saveSetting(key, clean[key]);
    resetPlatformSite();
    toast("ok", "Saved. The public website now shows this.");
  };
  const patch = <K extends keyof Settings>(key: K, value: Partial<Settings[K]>) =>
    setDraft((d) => (d ? { ...d, [key]: { ...(d[key] as object), ...(value as object) } } as Settings : d));

  return (
    <div>
      <div className="page-title">
        <div>
          <h1>Website</h1>
          <p>Everything visitors see on the public website. Changes are live as soon as you save.</p>
        </div>
        <a className="btn ghost" href="/" target="_blank" rel="noreferrer">View website</a>
      </div>
      <div className="children">
        {SITE_TABS.map((t) => <button key={t.id} type="button" className={`chip ${tab === t.id ? "on" : ""}`} onClick={() => setTab(t.id)}>{t.label}</button>)}
      </div>
      <QueryState status={q} label="website settings">
        {draft ? (
          <div className="card">
            {tab === "branding" ? (
              <Form onSubmit={() => save("branding")}>
                <div className="reg-grid">
                  <Field label="Product name"><input value={draft.branding.name || ""} onChange={(e) => patch("branding", { name: e.target.value })} maxLength={80} required /></Field>
                  <Field label="Short name"><input value={draft.branding.shortName || ""} onChange={(e) => patch("branding", { shortName: e.target.value })} maxLength={40} /></Field>
                  <Field label="Main colour"><input type="color" value={draft.branding.primaryColor || "#1d4ed8"} onChange={(e) => patch("branding", { primaryColor: e.target.value })} /></Field>
                  <Field label="Accent colour"><input type="color" value={draft.branding.accentColor || "#0ea5e9"} onChange={(e) => patch("branding", { accentColor: e.target.value })} /></Field>
                </div>
                <Field label="Tagline"><input value={draft.branding.tagline || ""} onChange={(e) => patch("branding", { tagline: e.target.value })} maxLength={200} /></Field>
                <AssetField label="Logo" value={draft.branding.logoUrl || ""} onChange={(url) => patch("branding", { logoUrl: url })} />
                <Button type="submit" kind="brass">Save branding</Button>
              </Form>
            ) : null}

            {tab === "homepage" ? (
              <Form onSubmit={() => save("homepage")}>
                <h3 style={{ marginTop: 0 }}>Top of the page</h3>
                <Field label="Small line above the title"><input value={draft.homepage.hero.eyebrow || ""} onChange={(e) => patch("homepage", { hero: { ...draft.homepage.hero, eyebrow: e.target.value } })} maxLength={120} /></Field>
                <Field label="Title"><input value={draft.homepage.hero.title || ""} onChange={(e) => patch("homepage", { hero: { ...draft.homepage.hero, title: e.target.value } })} maxLength={160} required /></Field>
                <Field label="Text under the title"><textarea value={draft.homepage.hero.subtitle || ""} onChange={(e) => patch("homepage", { hero: { ...draft.homepage.hero, subtitle: e.target.value } })} maxLength={500} /></Field>
                <div className="reg-grid">
                  <Field label="Main button"><input value={draft.homepage.hero.primaryCta || ""} onChange={(e) => patch("homepage", { hero: { ...draft.homepage.hero, primaryCta: e.target.value } })} maxLength={40} /></Field>
                  <Field label="Second button"><input value={draft.homepage.hero.secondaryCta || ""} onChange={(e) => patch("homepage", { hero: { ...draft.homepage.hero, secondaryCta: e.target.value } })} maxLength={40} /></Field>
                </div>
                <AssetField label="Picture beside the title (optional)" value={draft.homepage.hero.imageUrl || ""} onChange={(url) => patch("homepage", { hero: { ...draft.homepage.hero, imageUrl: url } })} />
                <Rows
                  title="Features"
                  rows={draft.homepage.features || []}
                  blank={{ title: "", body: "", icon: "check" }}
                  onChange={(rows) => patch("homepage", { features: rows })}
                  render={(row, set) => (
                    <>
                      <input aria-label="Feature title" value={row.title} placeholder="Title" onChange={(e) => set({ ...row, title: e.target.value })} maxLength={80} />
                      <input aria-label="Feature text" value={row.body || ""} placeholder="Short text" onChange={(e) => set({ ...row, body: e.target.value })} maxLength={300} />
                      <select aria-label="Icon" value={row.icon || "check"} onChange={(e) => set({ ...row, icon: e.target.value })}>
                        {["users", "calendar", "check", "wallet", "branch", "shield", "book", "chart"].map((i) => <option key={i}>{i}</option>)}
                      </select>
                    </>
                  )}
                />
                <Rows
                  title="How it works"
                  rows={draft.homepage.steps || []}
                  blank={{ title: "", body: "" }}
                  onChange={(rows) => patch("homepage", { steps: rows })}
                  render={(row, set) => (
                    <>
                      <input aria-label="Step title" value={row.title} placeholder="Title" onChange={(e) => set({ ...row, title: e.target.value })} maxLength={80} />
                      <input aria-label="Step text" value={row.body || ""} placeholder="Short text" onChange={(e) => set({ ...row, body: e.target.value })} maxLength={300} />
                    </>
                  )}
                />
                <h3>About</h3>
                <Field label="Heading"><input value={draft.homepage.about?.title || ""} onChange={(e) => patch("homepage", { about: { ...draft.homepage.about, title: e.target.value } })} maxLength={120} /></Field>
                <Field label="Text"><textarea style={{ minHeight: 140 }} value={draft.homepage.about?.body || ""} onChange={(e) => patch("homepage", { about: { ...draft.homepage.about, body: e.target.value } })} maxLength={4000} /></Field>
                <Button type="submit" kind="brass">Save homepage</Button>
              </Form>
            ) : null}

            {tab === "navigation" ? (
              <Form onSubmit={() => save("navigation", "footer")}>
                <p className="hint">Use /features, /pricing, /about or /contact to jump to a part of the homepage, or a full https:// address.</p>
                <Rows
                  title="Top menu"
                  rows={draft.navigation || []}
                  blank={{ label: "", to: "/" }}
                  onChange={(rows) => setDraft((d) => (d ? { ...d, navigation: rows } : d))}
                  render={(row, set) => (
                    <>
                      <input aria-label="Label" value={row.label} placeholder="Label" onChange={(e) => set({ ...row, label: e.target.value })} maxLength={40} />
                      <input aria-label="Link" value={row.to} placeholder="/pricing" onChange={(e) => set({ ...row, to: e.target.value })} maxLength={200} />
                    </>
                  )}
                />
                <h3>Footer</h3>
                <Field label="Footer text"><input value={draft.footer.text || ""} onChange={(e) => patch("footer", { text: e.target.value })} maxLength={200} /></Field>
                <Rows
                  title="Footer links"
                  rows={draft.footer.links || []}
                  blank={{ label: "", to: "/" }}
                  onChange={(rows) => patch("footer", { links: rows })}
                  render={(row, set) => (
                    <>
                      <input aria-label="Label" value={row.label} placeholder="Label" onChange={(e) => set({ ...row, label: e.target.value })} maxLength={40} />
                      <input aria-label="Link" value={row.to} placeholder="/contact" onChange={(e) => set({ ...row, to: e.target.value })} maxLength={200} />
                    </>
                  )}
                />
                <Button type="submit" kind="brass">Save menu and footer</Button>
              </Form>
            ) : null}

            {tab === "contact" ? (
              <Form onSubmit={() => save("contact")}>
                <div className="reg-grid">
                  <Field label="Email"><input type="email" value={draft.contact.email || ""} onChange={(e) => patch("contact", { email: e.target.value })} maxLength={150} /></Field>
                  <Field label="Phone"><input value={draft.contact.phone || ""} onChange={(e) => patch("contact", { phone: e.target.value })} maxLength={40} /></Field>
                  <Field label="WhatsApp number"><input value={draft.contact.whatsapp || ""} onChange={(e) => patch("contact", { whatsapp: e.target.value })} maxLength={40} /></Field>
                  <Field label="Office hours"><input value={draft.contact.hours || ""} onChange={(e) => patch("contact", { hours: e.target.value })} maxLength={120} /></Field>
                </div>
                <Field label="Address"><input value={draft.contact.address || ""} onChange={(e) => patch("contact", { address: e.target.value })} maxLength={300} /></Field>
                <Button type="submit" kind="brass">Save contact details</Button>
              </Form>
            ) : null}

            {tab === "announcement" ? (
              <Form onSubmit={() => save("announcement")}>
                <label className="module" style={{ marginBottom: 14 }}>
                  <input type="checkbox" checked={Boolean(draft.announcement.enabled)} onChange={(e) => patch("announcement", { enabled: e.target.checked })} />
                  <span>Show a banner at the top of the website</span>
                </label>
                <Field label="Banner text"><input value={draft.announcement.text || ""} onChange={(e) => patch("announcement", { text: e.target.value })} maxLength={300} /></Field>
                <div className="reg-grid">
                  <Field label="Style">
                    <select value={draft.announcement.tone || "info"} onChange={(e) => patch("announcement", { tone: e.target.value })}>
                      <option value="info">Information</option>
                      <option value="success">Good news</option>
                      <option value="warning">Warning</option>
                    </select>
                  </Field>
                  <span />
                  <Field label="Link label (optional)"><input value={draft.announcement.linkLabel || ""} onChange={(e) => patch("announcement", { linkLabel: e.target.value })} maxLength={40} /></Field>
                  <Field label="Link (optional)"><input value={draft.announcement.linkTo || ""} onChange={(e) => patch("announcement", { linkTo: e.target.value })} maxLength={200} placeholder="/pricing" /></Field>
                </div>
                <Button type="submit" kind="brass">Save announcement</Button>
              </Form>
            ) : null}

            {tab === "registration" ? (
              <Form onSubmit={() => save("registration")}>
                <label className="module" style={{ marginBottom: 14 }}>
                  <input type="checkbox" checked={draft.registration.open !== false} onChange={(e) => patch("registration", { open: e.target.checked })} />
                  <span>Accept new school registrations</span>
                </label>
                <Field label="Documents required with each application">
                  <input type="number" min={0} max={10} value={draft.registration.minimumDocuments ?? 1} onChange={(e) => patch("registration", { minimumDocuments: Number(e.target.value) })} />
                </Field>
                <Field label="School types (one per line)">
                  <textarea style={{ minHeight: 140 }} value={(draft.registration.schoolTypes || []).join("\n")} onChange={(e) => patch("registration", { schoolTypes: e.target.value.split("\n") })} />
                </Field>
                <Field label="Document types (one per line)">
                  <textarea style={{ minHeight: 140 }} value={(draft.registration.documentTypes || []).join("\n")} onChange={(e) => patch("registration", { documentTypes: e.target.value.split("\n") })} />
                </Field>
                <Button type="submit" kind="brass">Save registration settings</Button>
              </Form>
            ) : null}

            {tab === "monitoring" ? (
              <Form onSubmit={() => save("monitoring")}>
                <p className="hint">When a figure on the Platform page passes one of these, it turns red.</p>
                <div className="reg-grid">
                  <Field label="CPU warning (%)"><input type="number" min={1} max={100} value={draft.monitoring.cpuWarnPercent} onChange={(e) => patch("monitoring", { cpuWarnPercent: Number(e.target.value) })} /></Field>
                  <Field label="RAM warning (%)"><input type="number" min={1} max={100} value={draft.monitoring.ramWarnPercent} onChange={(e) => patch("monitoring", { ramWarnPercent: Number(e.target.value) })} /></Field>
                  <Field label="Disk warning (%)"><input type="number" min={1} max={100} value={draft.monitoring.diskWarnPercent} onChange={(e) => patch("monitoring", { diskWarnPercent: Number(e.target.value) })} /></Field>
                  <Field label="School near its limit (%)"><input type="number" min={1} max={100} value={draft.monitoring.nearLimitPercent} onChange={(e) => patch("monitoring", { nearLimitPercent: Number(e.target.value) })} /></Field>
                </div>
                <Button type="submit" kind="brass">Save alert levels</Button>
              </Form>
            ) : null}
          </div>
        ) : null}
      </QueryState>
    </div>
  );
}

function AssetField({ label, value, onChange }: { label: string; value: string; onChange: (url: string) => void }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  return (
    <div className="field">
      <span>{label}</span>
      <div className="row">
        {value ? <img src={value} alt="" className="asset-thumb" /> : null}
        <input
          type="file"
          accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (!file) return;
            setBusy(true);
            platformApi.uploadAsset(file)
              .then((r) => { onChange(r.url); toast("ok", "Uploaded. Save to publish it."); })
              .catch((ex) => toast("err", ex instanceof Error ? ex.message : "Upload failed."))
              .finally(() => setBusy(false));
          }}
          style={{ flex: 1, minWidth: 180 }}
        />
        {value ? <button type="button" className="btn ghost sm" onClick={() => onChange("")}>Remove</button> : null}
      </div>
    </div>
  );
}

function Rows<T extends object>({ title, rows, blank, onChange, render }: {
  title: string;
  rows: T[];
  blank: T;
  onChange: (rows: T[]) => void;
  render: (row: T, set: (next: T) => void) => React.ReactNode;
}) {
  return (
    <div className="rows">
      <h3>{title}</h3>
      {rows.map((row, i) => (
        <div key={i} className="rows-line">
          {render(row, (next) => onChange(rows.map((r, j) => (j === i ? next : r))))}
          <button type="button" className="btn ghost sm" disabled={i === 0} aria-label="Move up" onClick={() => { const c = [...rows]; [c[i - 1], c[i]] = [c[i], c[i - 1]]; onChange(c); }}>↑</button>
          <button type="button" className="btn ghost sm" aria-label="Remove" onClick={() => onChange(rows.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn ghost sm" onClick={() => onChange([...rows, { ...blank }])}>+ Add</button>
    </div>
  );
}
