import { useEffect, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useSession, type PendingSelection } from "../lib/session";
import { usePlatformSite } from "../lib/platform";
import { homeFor } from "../lib/routes";
import { Badge, Button, Field, Form } from "../ui/kit";
import ThemeToggle from "../ui/ThemeToggle";
import { useToast } from "../lib/toast";
import { prefetchPortal } from "../lib/prefetch";
import { SAAS_ROLE_LABEL, type BranchOption, type SchoolOption } from "../api/saas";
import type { User } from "../lib/types";

/**
 * One central sign-in for every account. The server works out the school, the
 * branch and the role from the account itself. Nothing about any school is shown
 * until the password has been accepted, and then only that user's own schools.
 */
export default function LoginPage() {
  const { user, login, select } = useSession();
  const { site } = usePlatformSite();
  const nav = useNavigate();
  const toast = useToast();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState<PendingSelection | null>(null);

  useEffect(() => {
    void import("./SchoolApp");
  }, []);

  if (user) return <Navigate to={homeFor(user)} replace />;

  const name = site?.branding.name || "DEVZARD eSchool";
  const done = (u: User) => {
    prefetchPortal(u.role);
    toast("ok", `Signed in as ${u.fullName || u.username}`);
    nav(homeFor(u), { replace: true });
  };

  return (
    <div className="auth-page">
      <div className="auth-top">
        <Link to="/" className="back-link">← {name}</Link>
        <ThemeToggle />
      </div>
      {pending ? (
        <SchoolPicker
          pending={pending}
          onCancel={() => { setPending(null); setPassword(""); }}
          onChoose={async (tenantId, branchId, branchPassword) => done(await select(pending.token, tenantId, branchId, branchPassword))}
        />
      ) : (
        <div className="auth-card">
          <div className="auth-logo">
            {site?.branding.logoUrl ? <img src={site.branding.logoUrl} alt="" /> : <div className="mark">{name.trim().charAt(0).toUpperCase()}</div>}
          </div>
          <p className="kicker">{name}</p>
          <h1>Sign in</h1>
          <p className="hint">Use your username or email. You will be taken to your own school.</p>
          <Form
            busyLabel="Signing in…"
            onSubmit={async () => {
              const result = await login(username, password);
              if (result.selection) setPending(result.selection);
              else done(result.user);
            }}
          >
            <Field label="Username or email">
              <input value={username} autoComplete="username" autoCapitalize="none" spellCheck={false} onChange={(e) => setUsername(e.target.value)} required autoFocus />
            </Field>
            <Field label="Password">
              <input type="password" value={password} autoComplete="current-password" onChange={(e) => setPassword(e.target.value)} required />
            </Field>
            <Button type="submit" kind="brass" loadingText="Signing in…">Sign in</Button>
          </Form>
          {site?.registration.open !== false ? (
            <p className="auth-foot">New school? <Link to="/register">Register your school</Link></p>
          ) : null}
        </div>
      )}
    </div>
  );
}

/** Shown only after a correct password, and only with this user's own schools and branches. */
export function SchoolPicker({
  pending,
  onChoose,
  onCancel,
  title = "Choose where to work",
  cancelLabel = "Use a different account",
}: {
  pending: { schools: SchoolOption[] };
  onChoose: (tenantId: string, branchId: string | null, branchPassword?: string) => Promise<void>;
  onCancel: () => void;
  title?: string;
  cancelLabel?: string;
}) {
  const schools = pending.schools;
  const [schoolId, setSchoolId] = useState<string | null>(schools.length === 1 ? schools[0].tenantId : null);
  const [ask, setAsk] = useState<BranchOption | null>(null);
  const [branchPassword, setBranchPassword] = useState("");
  const school = schools.find((s) => s.tenantId === schoolId) || null;

  if (!schools.length) {
    return (
      <div className="auth-card">
        <h1>No school available</h1>
        <p className="hint">Your account is not linked to an active school or branch. Please contact your school administrator.</p>
        <Button kind="ghost" onClick={onCancel}>{cancelLabel}</Button>
      </div>
    );
  }

  if (school && ask) {
    return (
      <div className="auth-card">
        <p className="kicker">{school.name}</p>
        <h1>{ask.name}</h1>
        <p className="hint">This branch is protected. Enter the branch access password to continue.</p>
        <Form busyLabel="Checking…" onSubmit={() => onChoose(school.tenantId, ask.id, branchPassword)}>
          <Field label="Branch access password">
            <input type="password" value={branchPassword} autoComplete="off" onChange={(e) => setBranchPassword(e.target.value)} required autoFocus />
          </Field>
          <div className="row">
            <Button type="submit" kind="brass" loadingText="Checking…">Enter branch</Button>
            <Button kind="ghost" onClick={() => { setAsk(null); setBranchPassword(""); }}>Back</Button>
          </div>
        </Form>
      </div>
    );
  }

  return (
    <div className="auth-card wide">
      <p className="kicker">{school ? school.name : "Your schools"}</p>
      <h1>{school ? "Choose a branch" : title}</h1>
      <p className="hint">{school ? "Only the branches you are allowed to open are listed." : "Only schools linked to your account are listed."}</p>
      {school ? (
        <div className="pick-list">
          {school.allBranches ? (
            <PickButton title="All branches" hint="Whole-school view" onPick={() => onChoose(school.tenantId, null)} />
          ) : null}
          {school.branches.map((b) => (
            <PickButton
              key={b.id}
              title={b.name}
              hint={[b.main ? "Main branch" : "", b.requiresPassword ? "Password required" : ""].filter(Boolean).join(" · ")}
              onPick={async () => {
                if (b.requiresPassword) {
                  setAsk(b);
                  return;
                }
                await onChoose(school.tenantId, b.id);
              }}
            />
          ))}
          {!school.allBranches && !school.branches.length ? (
            <p className="notice">{school.blockedReason || "No branch is available for your account in this school."}</p>
          ) : null}
        </div>
      ) : (
        <div className="pick-list">
          {schools.map((s) => {
            const single = !s.allBranches && s.branches.length === 1 && !s.branches[0].requiresPassword;
            const direct = (s.allBranches && s.branches.length === 0) || single;
            return (
              <PickButton
                key={s.tenantId}
                title={s.name}
                logoUrl={s.logoUrl}
                hint={SAAS_ROLE_LABEL[s.role] || s.role}
                badge={s.verificationStatus && s.verificationStatus !== "VERIFIED" ? s.verificationStatus : undefined}
                onPick={async () => {
                  if (direct) await onChoose(s.tenantId, single ? s.branches[0].id : null);
                  else setSchoolId(s.tenantId);
                }}
              />
            );
          })}
        </div>
      )}
      <div className="row" style={{ marginTop: 16 }}>
        {school && schools.length > 1 ? <Button kind="ghost" onClick={() => setSchoolId(null)}>← Schools</Button> : null}
        <Button kind="ghost" onClick={onCancel}>{cancelLabel}</Button>
      </div>
    </div>
  );
}

function PickButton({ title, hint, badge, logoUrl, onPick }: { title: string; hint?: string; badge?: string; logoUrl?: string; onPick: () => void | Promise<void> }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      className="pick"
      disabled={busy}
      onClick={() => {
        const r = onPick();
        if (r && typeof (r as Promise<void>).then === "function") {
          setBusy(true);
          (r as Promise<void>)
            .catch((e) => toast("err", e instanceof Error ? e.message : "Could not open that school."))
            .finally(() => setBusy(false));
        }
      }}
    >
      {logoUrl ? <img src={logoUrl} alt="" /> : <span className="mark">{title.trim().charAt(0).toUpperCase()}</span>}
      <span className="pick-copy">
        <strong>{title}</strong>
        {hint ? <small>{hint}</small> : null}
      </span>
      {badge ? <Badge value={badge} /> : null}
      <span aria-hidden>{busy ? "…" : "→"}</span>
    </button>
  );
}
