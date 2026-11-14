import { useState } from "react";
import { branchApi, schoolAccountApi, fmtBytes, type Branch, type StaffOption } from "../../api/saas";
import { ApiError } from "../../api/client";
import { FEATURE_KEYS, FEATURE_LABELS } from "../../lib/features";
import { useSession } from "../../lib/session";
import { useToast } from "../../lib/toast";
import { Badge, Button, Empty, Field, Form, Modal, QueryState, Table, useAsync } from "../../ui/kit";
import Meter from "../../ui/Meter";
import { PlanCard } from "../PlatformHome";

type Dialog =
  | { kind: "edit"; branch: Branch | null }
  | { kind: "admin"; branch: Branch }
  | { kind: "password"; branch: Branch }
  | { kind: "modules"; branch: Branch }
  | { kind: "members"; branch: Branch }
  | null;

/** School Owner only: create and manage branches inside the plan's branch limit. */
export function BranchesPage() {
  const { context } = useSession();
  const toast = useToast();
  const owner = context?.role === "SCHOOL_OWNER";
  const list = useAsync(() => branchApi.list(), []);
  const overview = useAsync(() => schoolAccountApi.overview(), []);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [issued, setIssued] = useState<{ branch: string; username: string; password?: string } | null>(null);
  const meter = overview.data?.resources?.branches;
  const refresh = () => { void list.reload(); void overview.reload(); };
  const full = Boolean(meter && (meter.full || meter.over));

  const setStatus = async (b: Branch, status: Branch["status"]) => {
    try {
      await branchApi.setStatus(b.id, status);
      toast("ok", status === "ACTIVE" ? `${b.name} is active.` : status === "SUSPENDED" ? `${b.name} is suspended.` : `${b.name} is archived.`);
      refresh();
    } catch (e) {
      if (e instanceof ApiError && e.errorCode === "BRANCH_LIMIT_REACHED") {
        toast("err", "Your plan's branch limit is reached. Archive another branch or ask for a higher plan.");
        return;
      }
      throw e;
    }
  };

  return (
    <div>
      <div className="page-title">
        <div>
          <h1>Branches</h1>
          <p>{owner ? "Create branches, give each one an admin and control who can open it." : "Branches you are allowed to open."}</p>
        </div>
        {owner ? (
          <Button kind="brass" disabled={full} onClick={() => setDialog({ kind: "edit", branch: null })}>New branch</Button>
        ) : null}
      </div>
      {owner && context && !context.allBranches ? (
        <div className="notice">
          You are working inside <strong>{context.branchName}</strong>, so only this branch is listed.
          Use the switcher at the top and choose "All branches" to manage every branch.
        </div>
      ) : null}

      {meter ? (
        <div className="card" style={{ marginBottom: 14 }}>
          <Meter label="Branches used" data={meter} />
          {full && owner ? <p className="hint" style={{ margin: "10px 0 0" }}>To add another branch, ask for a higher plan on the Plan & usage page.</p> : null}
        </div>
      ) : null}

      {issued ? (
        <div className="notice" role="status">
          <strong>Branch admin for {issued.branch}</strong>
          <div className="pwd" style={{ margin: "8px 0" }}>
            Username: <strong>{issued.username}</strong>
            {issued.password ? <><br />Temporary password: <strong>{issued.password}</strong></> : null}
          </div>
          {issued.password ? <span>Copy this password now. It is shown only once and must be changed at first sign-in. </span> : null}
          <button type="button" className="btn ghost sm" onClick={() => setIssued(null)}>Done</button>
        </div>
      ) : null}

      <QueryState status={list} label="branches">
        {list.data?.length ? (
          <div className="branch-grid">
            {list.data.map((b) => (
              <article key={b.id} className={`card branch ${b.status !== "ACTIVE" ? "off" : ""}`}>
                <div className="row" style={{ justifyContent: "space-between" }}>
                  <h3>{b.name}</h3>
                  <span className="row">
                    {b.main ? <span className="badge mute">Main</span> : null}
                    <Badge value={b.status} />
                  </span>
                </div>
                <p className="hint" style={{ margin: "2px 0 10px" }}>{[b.code, b.city].filter(Boolean).join(" · ")}</p>
                <dl className="facts compact">
                  <div><dt>Users</dt><dd>{b.users ?? 0}</dd></div>
                  <div><dt>Students</dt><dd>{b.students ?? 0}</dd></div>
                  <div><dt>Branch admin</dt><dd>{b.adminName || "Not set"}</dd></div>
                  <div><dt>Access password</dt><dd>{b.hasPassword ? "On" : "Off"}</dd></div>
                </dl>
                {owner ? (
                  <div className="row" style={{ marginTop: 12 }}>
                    <button type="button" className="btn ghost sm" onClick={() => setDialog({ kind: "edit", branch: b })}>Edit</button>
                    {b.status !== "ARCHIVED" ? (
                      <>
                        <button type="button" className="btn ghost sm" onClick={() => setDialog({ kind: "admin", branch: b })}>Branch admin</button>
                        <button type="button" className="btn ghost sm" onClick={() => setDialog({ kind: "members", branch: b })}>Staff access</button>
                        <button type="button" className="btn ghost sm" onClick={() => setDialog({ kind: "password", branch: b })}>Password</button>
                        <button type="button" className="btn ghost sm" onClick={() => setDialog({ kind: "modules", branch: b })}>Modules</button>
                      </>
                    ) : null}
                    {!b.main && b.status === "ACTIVE" ? <Button kind="ghost" loadingText="Suspending…" onClick={() => setStatus(b, "SUSPENDED")}>Suspend</Button> : null}
                    {!b.main && b.status !== "ACTIVE" ? <Button kind="ghost" loadingText="Activating…" onClick={() => setStatus(b, "ACTIVE")}>{b.status === "ARCHIVED" ? "Restore" : "Activate"}</Button> : null}
                    {!b.main && b.status !== "ARCHIVED" ? (
                      <Button
                        kind="ghost"
                        loadingText="Archiving…"
                        onClick={async () => {
                          if (!window.confirm(`Archive ${b.name}? Its users will no longer be able to open it. No data is deleted and you can restore it later.`)) return;
                          await setStatus(b, "ARCHIVED");
                        }}
                      >
                        Archive
                      </Button>
                    ) : null}
                  </div>
                ) : null}
              </article>
            ))}
          </div>
        ) : <Empty title="No branches yet." hint={owner ? "Create your first branch to start." : undefined} />}
      </QueryState>

      <Modal title={dialog?.kind === "edit" ? (dialog.branch ? `Edit ${dialog.branch.name}` : "New branch") : ""} open={dialog?.kind === "edit"} onClose={() => setDialog(null)}>
        {dialog?.kind === "edit" ? <BranchForm branch={dialog.branch} onDone={() => { setDialog(null); refresh(); }} /> : null}
      </Modal>
      <Modal title={dialog?.kind === "admin" ? `Branch admin · ${dialog.branch.name}` : ""} open={dialog?.kind === "admin"} onClose={() => setDialog(null)}>
        {dialog?.kind === "admin" ? (
          <AdminForm
            branch={dialog.branch}
            onDone={(username, password) => {
              setIssued({ branch: dialog.branch.name, username, password });
              setDialog(null);
              refresh();
            }}
          />
        ) : null}
      </Modal>
      <Modal title={dialog?.kind === "password" ? `Access password · ${dialog.branch.name}` : ""} open={dialog?.kind === "password"} onClose={() => setDialog(null)}>
        {dialog?.kind === "password" ? <PasswordForm branch={dialog.branch} onDone={() => { setDialog(null); refresh(); }} /> : null}
      </Modal>
      <Modal title={dialog?.kind === "modules" ? `Modules · ${dialog.branch.name}` : ""} open={dialog?.kind === "modules"} onClose={() => setDialog(null)}>
        {dialog?.kind === "modules" ? <ModulesForm branch={dialog.branch} onDone={() => { setDialog(null); refresh(); }} /> : null}
      </Modal>
      <Modal title={dialog?.kind === "members" ? `Staff access · ${dialog.branch.name}` : ""} open={dialog?.kind === "members"} onClose={() => setDialog(null)}>
        {dialog?.kind === "members" ? <MembersForm branch={dialog.branch} /> : null}
      </Modal>
    </div>
  );
}

function BranchForm({ branch, onDone }: { branch: Branch | null; onDone: () => void }) {
  const toast = useToast();
  const [f, setF] = useState({
    name: branch?.name || "",
    code: branch?.code || "",
    city: branch?.city || "",
    address: branch?.address || "",
    phone: branch?.phone || "",
    email: branch?.email || "",
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  return (
    <Form
      onSubmit={async () => {
        if (branch) await branchApi.update(branch.id, f);
        else await branchApi.create(f);
        toast("ok", branch ? "Branch saved." : "Branch created.");
        onDone();
      }}
    >
      <div className="reg-grid">
        <Field label="Branch name"><input value={f.name} onChange={set("name")} maxLength={150} required autoFocus /></Field>
        <Field label="Short code (optional)"><input value={f.code} onChange={set("code")} maxLength={20} placeholder="Made for you if empty" disabled={Boolean(branch)} /></Field>
        <Field label="City"><input value={f.city} onChange={set("city")} maxLength={100} /></Field>
        <Field label="Phone"><input value={f.phone} onChange={set("phone")} maxLength={30} /></Field>
        <Field label="Address"><input value={f.address} onChange={set("address")} maxLength={300} /></Field>
        <Field label="Email"><input type="email" value={f.email} onChange={set("email")} maxLength={150} /></Field>
      </div>
      <Button type="submit" kind="brass">{branch ? "Save branch" : "Create branch"}</Button>
    </Form>
  );
}

function AdminForm({ branch, onDone }: { branch: Branch; onDone: (username: string, password?: string) => void }) {
  const staff = useAsync(() => branchApi.staff(), []);
  const [mode, setMode] = useState<"new" | "existing">("new");
  const [userId, setUserId] = useState("");
  const [f, setF] = useState({ firstName: "", lastName: "", email: "", phone: "" });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  const admins = (staff.data || []).filter((s) => s.erpRole === "SCHOOL_ADMIN" && s.role !== "SCHOOL_OWNER");
  return (
    <Form
      onSubmit={async () => {
        const res = await branchApi.setAdmin(branch.id, mode === "existing" ? { userId } : f);
        onDone(res.username || "", res.temporaryPassword);
      }}
    >
      {branch.adminName ? <p className="hint">Current branch admin: <strong>{branch.adminName}</strong>{branch.adminUsername ? ` (${branch.adminUsername})` : ""}</p> : null}
      <div className="children">
        <button type="button" className={`chip ${mode === "new" ? "on" : ""}`} onClick={() => setMode("new")}>Create a new account</button>
        <button type="button" className={`chip ${mode === "existing" ? "on" : ""}`} onClick={() => setMode("existing")}>Use an existing admin</button>
      </div>
      {mode === "new" ? (
        <div className="reg-grid">
          <Field label="First name"><input value={f.firstName} onChange={set("firstName")} maxLength={100} required /></Field>
          <Field label="Last name"><input value={f.lastName} onChange={set("lastName")} maxLength={100} required /></Field>
          <Field label="Email (optional)"><input type="email" value={f.email} onChange={set("email")} maxLength={150} /></Field>
          <Field label="Phone (optional)"><input value={f.phone} onChange={set("phone")} maxLength={30} /></Field>
        </div>
      ) : (
        <Field label="Admin account">
          <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
            <option value="">{staff.loading ? "Loading…" : "Choose…"}</option>
            {admins.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.username}){s.branchName ? ` · ${s.branchName}` : ""}</option>)}
          </select>
        </Field>
      )}
      <p className="hint">A branch admin can manage only this branch. They never see other branches.</p>
      <Button type="submit" kind="brass">Save branch admin</Button>
    </Form>
  );
}

function PasswordForm({ branch, onDone }: { branch: Branch; onDone: () => void }) {
  const toast = useToast();
  const [password, setPassword] = useState("");
  return (
    <Form
      onSubmit={async () => {
        if (password.length < 6) throw new Error("Use at least 6 characters.");
        await branchApi.setPassword(branch.id, password);
        toast("ok", "Branch access password saved. People in this branch must sign in again.");
        onDone();
      }}
    >
      <p className="hint">
        An extra password that staff must enter to open this branch, after their own sign-in. You are never asked for it.
        {branch.hasPassword ? " A password is set now." : " No password is set now."}
      </p>
      <Field label={branch.hasPassword ? "New branch password" : "Branch password"}>
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={6} maxLength={100} autoComplete="new-password" required />
      </Field>
      <div className="row">
        <Button type="submit" kind="brass">Save password</Button>
        {branch.hasPassword ? (
          <Button
            kind="ghost"
            loadingText="Removing…"
            onClick={async () => {
              await branchApi.setPassword(branch.id, "");
              toast("ok", "Branch access password removed.");
              onDone();
            }}
          >
            Remove password
          </Button>
        ) : null}
      </div>
    </Form>
  );
}

function ModulesForm({ branch, onDone }: { branch: Branch; onDone: () => void }) {
  const toast = useToast();
  const [off, setOff] = useState<Record<string, boolean>>(() => {
    const out: Record<string, boolean> = {};
    Object.entries(branch.permissions || {}).forEach(([k, v]) => { if (v === false) out[k] = true; });
    return out;
  });
  return (
    <Form
      onSubmit={async () => {
        const body: Record<string, boolean> = {};
        FEATURE_KEYS.forEach((k) => { body[k] = !off[k]; });
        await branchApi.setPermissions(branch.id, body);
        toast("ok", "Branch modules saved.");
        onDone();
      }}
    >
      <p className="hint">Switch a module off to hide it for this branch only. A module that is off for the whole school stays off.</p>
      <div className="module-grid">
        {FEATURE_KEYS.map((k) => (
          <label key={k} className="module">
            <input type="checkbox" checked={!off[k]} onChange={(e) => setOff((p) => ({ ...p, [k]: !e.target.checked }))} />
            <span>{FEATURE_LABELS[k]}</span>
          </label>
        ))}
      </div>
      <Button type="submit" kind="brass">Save modules</Button>
    </Form>
  );
}

function MembersForm({ branch }: { branch: Branch }) {
  const toast = useToast();
  const members = useAsync(() => branchApi.members(branch.id), [branch.id]);
  const staff = useAsync(() => branchApi.staff(), []);
  const [userId, setUserId] = useState("");
  const inside = new Set((members.data || []).map((m) => m.id));
  const candidates = (staff.data || []).filter((s: StaffOption) => !inside.has(s.id) && s.role !== "SCHOOL_OWNER");
  return (
    <div>
      <p className="hint">People listed here may open this branch. Give a teacher or staff member access to a second branch by adding them.</p>
      <Form
        onSubmit={async () => {
          if (!userId) throw new Error("Choose a person.");
          members.setData(await branchApi.addMember(branch.id, userId));
          setUserId("");
          toast("ok", "Access added.");
        }}
      >
        <div className="reg-doc">
          <select aria-label="Staff member" value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">{staff.loading ? "Loading…" : "Add a staff member…"}</option>
            {candidates.map((s) => <option key={s.id} value={s.id}>{s.name} ({s.username})</option>)}
          </select>
          <Button type="submit" kind="ghost">Add</Button>
        </div>
      </Form>
      <QueryState status={members} label="staff">
        <Table
          headers={["Name", "Username", "Role", ""]}
          rows={(members.data || []).map((m) => [
            m.name,
            m.username,
            <Badge key="r" value={m.role} />,
            <Button
              key="x"
              kind="ghost"
              loadingText="Removing…"
              onClick={async () => {
                members.setData(await branchApi.removeMember(branch.id, m.id));
                toast("ok", "Access removed.");
              }}
            >
              Remove
            </Button>,
          ])}
        />
      </QueryState>
    </div>
  );
}

/** Plan, limits and live usage for the School Owner and school admins. */
export function PlanUsagePage() {
  const q = useAsync(() => schoolAccountApi.overview(), []);
  const data = q.data;
  const res = data?.resources;
  const contact = data?.contact;
  return (
    <div>
      <div className="page-title">
        <div>
          <h1>Plan & usage</h1>
          <p>What your school can use, and how much is used right now.</p>
        </div>
        {res ? <span className="badge ok">{res.limits.planName} plan</span> : null}
      </div>
      <QueryState status={q} label="plan">
        {res ? (
          <>
            {res.limits.planExpired ? <div className="notice">Your paid plan has expired, so the Free plan limits apply. Nothing was deleted.</div> : null}
            {res.limits.hasOverride ? <div className="notice">Our team set custom limits for your school. They replace the plan limits.</div> : null}
            <div className="grid three meters">
              <div className="card"><Meter label="Active users" data={res.users} /></div>
              <div className="card"><Meter label="Branches" data={res.branches} /></div>
              <div className="card"><Meter label="Storage" data={res.storage} format={fmtBytes} /></div>
            </div>
            <p className="hint" style={{ marginTop: 12 }}>
              Active users are all accounts that can sign in: students, parents, teachers, staff and admins. Deactivated accounts do not count.
            </p>
            <h2>Plans</h2>
            <div className="pf-plans">
              {(data?.plans || []).map((p) => <PlanCard key={p.id} plan={p} current={p.code === res.limits.subscribedPlanCode} />)}
            </div>
            <div className="card" style={{ marginTop: 14 }}>
              <h3 style={{ marginTop: 0 }}>Need a higher plan?</h3>
              <p className="hint" style={{ marginBottom: 0 }}>
                Plans are changed by our team. There is no online payment.
                {contact?.email || contact?.phone || contact?.whatsapp ? " Contact us: " : " Please contact the platform team."}
                {contact?.email ? <a href={`mailto:${contact.email}`}>{contact.email}</a> : null}
                {contact?.email && (contact?.phone || contact?.whatsapp) ? " · " : ""}
                {contact?.phone || contact?.whatsapp || ""}
              </p>
            </div>
          </>
        ) : <Empty title="Plan details are shown to the school owner and school admins." />}
      </QueryState>
    </div>
  );
}
