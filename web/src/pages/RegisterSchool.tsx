import { useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { ApiError } from "../api/client";
import { platformPublicApi } from "../api/saas";
import { usePlatformSite } from "../lib/platform";
import { useSession } from "../lib/session";
import { homeFor } from "../lib/routes";
import { Button, Field, Loading, friendlyMessage } from "../ui/kit";
import ThemeToggle from "../ui/ThemeToggle";

const MAX_FILE = 10 * 1024 * 1024;
const FILE_TYPES = ".pdf,.jpg,.jpeg,.png,.webp,application/pdf,image/jpeg,image/png,image/webp";

interface DocRow {
  key: number;
  type: string;
  file: File | null;
}

const EMPTY = {
  schoolName: "",
  schoolType: "",
  address: "",
  city: "",
  country: "Pakistan",
  contactNumber: "",
  officialEmail: "",
  principalName: "",
  registrationNumber: "",
  registrationDetails: "",
  ownerFirstName: "",
  ownerLastName: "",
  ownerEmail: "",
  password: "",
  confirm: "",
};

export default function RegisterSchool() {
  const { site } = usePlatformSite();
  const { user } = useSession();
  const [form, setForm] = useState(EMPTY);
  const [logo, setLogo] = useState<File | null>(null);
  const [docs, setDocs] = useState<DocRow[]>([{ key: 1, type: "", file: null }]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState("");
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<{ schoolName: string; loginEmail: string; username: string } | null>(null);

  if (user) return <Navigate to={homeFor(user)} replace />;
  if (!site) return <Loading label="Loading…" />;

  const rules = site.registration;
  const docTypes = rules.documentTypes?.length ? rules.documentTypes : ["Registration certificate"];
  const minDocs = rules.minimumDocuments ?? 1;
  const set = (key: keyof typeof EMPTY) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
    setForm((f) => ({ ...f, [key]: e.target.value }));
    setErrors((prev) => {
      if (!prev[key]) return prev;
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };
  const err = (key: string) => (errors[key] ? <small className="field-err">{errors[key]}</small> : null);

  const validate = () => {
    const out: Record<string, string> = {};
    const need: [keyof typeof EMPTY, string][] = [
      ["schoolName", "School name"], ["schoolType", "School type"], ["address", "Address"], ["city", "City"], ["country", "Country"],
      ["contactNumber", "Contact number"], ["officialEmail", "Official email"], ["principalName", "Owner or principal name"],
      ["ownerFirstName", "First name"], ["ownerLastName", "Last name"], ["ownerEmail", "Login email"], ["password", "Password"],
    ];
    for (const [key, label] of need) if (!form[key].trim()) out[key] = `${label} is required.`;
    const email = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    if (form.officialEmail && !email.test(form.officialEmail.trim())) out.officialEmail = "Enter a valid email address.";
    if (form.ownerEmail && !email.test(form.ownerEmail.trim())) out.ownerEmail = "Enter a valid email address.";
    if (form.password && (form.password.length < 8 || !/[A-Za-z]/.test(form.password) || !/\d/.test(form.password))) {
      out.password = "Use at least 8 characters with a letter and a number.";
    }
    if (form.password !== form.confirm) out.confirm = "Passwords do not match.";
    const chosen = docs.filter((d) => d.file);
    if (chosen.length < minDocs) out.documents = `Upload at least ${minDocs} verification document${minDocs === 1 ? "" : "s"}.`;
    if (chosen.some((d) => !d.type)) out.documents = "Choose a document type for every file.";
    if (chosen.some((d) => (d.file as File).size > MAX_FILE)) out.documents = "Each document must be 10 MB or smaller.";
    if (logo && logo.size > MAX_FILE) out.logo = "The logo must be 10 MB or smaller.";
    return out;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setFailure("");
    const found = validate();
    setErrors(found);
    if (Object.keys(found).length) {
      setFailure("Please correct the highlighted fields.");
      return;
    }
    const fd = new FormData();
    (Object.keys(EMPTY) as (keyof typeof EMPTY)[]).forEach((key) => {
      if (key !== "confirm") fd.append(key, form[key].trim());
    });
    if (logo) fd.append("logo", logo);
    docs.filter((d) => d.file).forEach((d) => {
      fd.append("documents", d.file as File);
      fd.append("documentTypes", d.type);
    });
    setBusy(true);
    try {
      setDone(await platformPublicApi.register(fd));
      window.scrollTo({ top: 0 });
    } catch (ex) {
      if (ex instanceof ApiError && ex.fields?.length) {
        setErrors(Object.fromEntries(ex.fields.map((f) => [f.field, f.message])));
      }
      setFailure(friendlyMessage(ex));
    } finally {
      setBusy(false);
    }
  };

  const top = (
    <div className="auth-top">
      <Link to="/" className="back-link">← {site.branding.name}</Link>
      <ThemeToggle />
    </div>
  );

  if (rules.open === false) {
    return (
      <div className="auth-page">
        {top}
        <div className="auth-card">
          <h1>Registration is closed</h1>
          <p className="hint">{rules.closedMessage || "New school registrations are not being accepted right now. Please check back later."}</p>
          <Link to="/" className="btn ghost">Back to home</Link>
        </div>
      </div>
    );
  }

  if (done) {
    return (
      <div className="auth-page">
        {top}
        <div className="auth-card">
          <p className="kicker">Application received</p>
          <h1>{done.schoolName}</h1>
          <p className="hint">Your school has been registered and is waiting for verification. You can sign in now to follow the status, but the school system unlocks only after approval.</p>
          <div className="pwd" style={{ marginBottom: 16 }}>
            Sign in with <strong>{done.loginEmail}</strong><br />or username <strong>{done.username}</strong>
          </div>
          <Link to="/login" className="btn brass">Sign in</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-page top">
      {top}
      <form className="auth-card reg" onSubmit={submit} noValidate aria-busy={busy}>
        <p className="kicker">{site.branding.name}</p>
        <h1>Register your school</h1>
        <p className="hint">Tell us about your school and upload a document that proves it is real. Our team reviews every application before the school system is unlocked.</p>

        <h2 className="reg-head">School</h2>
        <div className="reg-grid">
          <Field label="School name"><input value={form.schoolName} onChange={set("schoolName")} maxLength={150} />{err("schoolName")}</Field>
          <Field label="School type">
            <select value={form.schoolType} onChange={set("schoolType")}>
              <option value="">Choose…</option>
              {(rules.schoolTypes || []).map((t) => <option key={t}>{t}</option>)}
            </select>
            {err("schoolType")}
          </Field>
          <Field label="Address"><input value={form.address} onChange={set("address")} maxLength={250} autoComplete="street-address" />{err("address")}</Field>
          <Field label="City"><input value={form.city} onChange={set("city")} maxLength={80} autoComplete="address-level2" />{err("city")}</Field>
          <Field label="Country"><input value={form.country} onChange={set("country")} maxLength={80} autoComplete="country-name" />{err("country")}</Field>
          <Field label="Contact number"><input value={form.contactNumber} onChange={set("contactNumber")} maxLength={30} inputMode="tel" autoComplete="tel" />{err("contactNumber")}</Field>
          <Field label="Official email"><input type="email" value={form.officialEmail} onChange={set("officialEmail")} maxLength={150} />{err("officialEmail")}</Field>
          <Field label="Owner or principal name"><input value={form.principalName} onChange={set("principalName")} maxLength={120} />{err("principalName")}</Field>
          <Field label="Registration number (optional)"><input value={form.registrationNumber} onChange={set("registrationNumber")} maxLength={100} />{err("registrationNumber")}</Field>
          <Field label="School logo (optional)">
            <input type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" onChange={(e) => setLogo(e.target.files?.[0] || null)} />
            {err("logo")}
          </Field>
        </div>
        <Field label="Registration details (optional)">
          <textarea value={form.registrationDetails} onChange={set("registrationDetails")} maxLength={2000} placeholder="Board or authority, year registered, anything that helps us verify the school." />
          {err("registrationDetails")}
        </Field>

        <h2 className="reg-head">Verification documents</h2>
        <p className="hint" style={{ marginBottom: 12 }}>PDF, JPG, PNG or WEBP. Up to 10 MB each. Only our verification team can see these files.</p>
        {docs.map((d, i) => (
          <div key={d.key} className="reg-doc">
            <select aria-label="Document type" value={d.type} onChange={(e) => setDocs((list) => list.map((x) => (x.key === d.key ? { ...x, type: e.target.value } : x)))}>
              <option value="">Document type…</option>
              {docTypes.map((t) => <option key={t}>{t}</option>)}
            </select>
            <input aria-label="Document file" type="file" accept={FILE_TYPES} onChange={(e) => setDocs((list) => list.map((x) => (x.key === d.key ? { ...x, file: e.target.files?.[0] || null } : x)))} />
            {docs.length > 1 ? <button type="button" className="btn ghost sm" onClick={() => setDocs((list) => list.filter((x) => x.key !== d.key))} aria-label={`Remove document ${i + 1}`}>Remove</button> : null}
          </div>
        ))}
        {err("documents")}
        {docs.length < 10 ? (
          <button type="button" className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => setDocs((list) => [...list, { key: Date.now(), type: "", file: null }])}>+ Add another document</button>
        ) : null}

        <h2 className="reg-head">Your sign-in</h2>
        <p className="hint" style={{ marginBottom: 12 }}>This account becomes the School Owner. You will use this email to sign in.</p>
        <div className="reg-grid">
          <Field label="First name"><input value={form.ownerFirstName} onChange={set("ownerFirstName")} maxLength={100} autoComplete="given-name" />{err("ownerFirstName")}</Field>
          <Field label="Last name"><input value={form.ownerLastName} onChange={set("ownerLastName")} maxLength={100} autoComplete="family-name" />{err("ownerLastName")}</Field>
          <Field label="Login email"><input type="email" value={form.ownerEmail} onChange={set("ownerEmail")} maxLength={150} autoComplete="email" />{err("ownerEmail")}</Field>
          <span />
          <Field label="Password"><input type="password" value={form.password} onChange={set("password")} maxLength={100} autoComplete="new-password" />{err("password")}</Field>
          <Field label="Confirm password"><input type="password" value={form.confirm} onChange={set("confirm")} maxLength={100} autoComplete="new-password" />{err("confirm")}</Field>
        </div>

        {failure ? <div className="error-box" role="alert" style={{ marginBottom: 14 }}>{failure}</div> : null}
        <div className="row">
          <Button type="submit" kind="brass" loading={busy} loadingText="Submitting…">Submit for verification</Button>
          <Link to="/login" className="btn ghost">I already have an account</Link>
        </div>
      </form>
    </div>
  );
}
