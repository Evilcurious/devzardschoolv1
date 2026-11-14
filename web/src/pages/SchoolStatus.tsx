import { useEffect, useState } from "react";
import { schoolAccountApi, type ApplicationView, type VerificationStatus } from "../api/saas";
import { usePlatformSite } from "../lib/platform";
import { useSession } from "../lib/session";
import { useToast } from "../lib/toast";
import { fmtDate } from "../lib/format";
import { Badge, Button, ErrorBox, Field, FileLink, Form, Loading, useAsync } from "../ui/kit";
import ThemeToggle from "../ui/ThemeToggle";

const HEADLINE: Record<string, { title: string; body: string }> = {
  PENDING: {
    title: "Waiting for verification",
    body: "Your application has been received. Our team will review your school and documents. The school system unlocks as soon as you are approved.",
  },
  UNDER_REVIEW: {
    title: "Your school is under review",
    body: "Our team is checking your details and documents. You will be able to use the school system once the review is complete.",
  },
  REJECTED: {
    title: "Your application was not approved",
    body: "Please read the reason below, correct your details or documents, and send the application again.",
  },
  SUSPENDED: {
    title: "This school is suspended",
    body: "Access to the school system is paused. Your data is safe and nothing has been deleted.",
  },
};

/**
 * Shown instead of the ERP while a school is not verified. The School Owner can
 * follow the status, fix details, add documents and resubmit. Everyone else only
 * sees that the school is not available yet.
 */
export default function SchoolStatus({ status }: { status: VerificationStatus }) {
  const { user, tenant, context, logout, reload } = useSession();
  const { site } = usePlatformSite(false);
  const owner = context?.role === "SCHOOL_OWNER";
  const copy = HEADLINE[status] || HEADLINE.PENDING;
  const contact = site?.contact;

  // Pick up an approval without asking the owner to sign in again.
  useEffect(() => {
    const id = window.setInterval(() => { void reload(); }, 30_000);
    return () => window.clearInterval(id);
  }, [reload]);

  return (
    <div className="status-page">
      <header className="status-top">
        <div>
          <strong>{tenant?.name || context?.tenantName || "Your school"}</strong>
          <div className="hint" style={{ margin: 0 }}>{user?.fullName}</div>
        </div>
        <div className="row">
          <ThemeToggle />
          <Button kind="ghost" loadingText="Signing out…" onClick={() => logout()}>Sign out</Button>
        </div>
      </header>
      <main className="status-main">
        <div className="card status-card">
          <div className="row" style={{ justifyContent: "space-between" }}>
            <p className="kicker" style={{ margin: 0 }}>School verification</p>
            <Badge value={status} />
          </div>
          <h1>{copy.title}</h1>
          <p>{copy.body}</p>
          {!owner ? <p className="hint">Please contact your school owner for more information.</p> : null}
          {contact && (contact.email || contact.phone) ? (
            <p className="hint" style={{ marginBottom: 0 }}>
              Need help? {contact.email ? <a href={`mailto:${contact.email}`}>{contact.email}</a> : null}
              {contact.email && contact.phone ? " · " : ""}
              {contact.phone || ""}
            </p>
          ) : null}
        </div>
        {owner ? <OwnerApplication status={status} onChanged={reload} /> : null}
      </main>
    </div>
  );
}

function OwnerApplication({ status, onChanged }: { status: VerificationStatus; onChanged: () => Promise<void> }) {
  const toast = useToast();
  const { site } = usePlatformSite(false);
  const q = useAsync(() => schoolAccountApi.application(), [status]);
  const [view, setView] = useState<ApplicationView | null>(null);
  const [edit, setEdit] = useState(false);
  const [docType, setDocType] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0);

  useEffect(() => {
    if (q.data) setView(q.data);
  }, [q.data]);

  if (q.loading && !view) return <Loading label="Loading your application…" />;
  if (q.error && !view) return <ErrorBox error={q.error} onRetry={() => void q.reload()} title="Unable to load your application" />;
  if (!view) return null;

  const app = view.application;
  const canEdit = Boolean(view.canEdit) && status !== "SUSPENDED";
  const docTypes = site?.registration.documentTypes?.length ? site.registration.documentTypes : ["Registration certificate", "Other"];
  const requestNote = (app as (typeof app & { documentsRequestNote?: string }) | null | undefined)?.documentsRequestNote;

  return (
    <>
      {status === "REJECTED" && app?.rejectionReason ? (
        <div className="notice" role="alert"><strong>Reason: </strong>{app.rejectionReason}</div>
      ) : null}
      {status === "SUSPENDED" && app?.suspensionReason ? (
        <div className="notice" role="alert"><strong>Reason: </strong>{app.suspensionReason}</div>
      ) : null}
      {app?.documentsRequested ? (
        <div className="notice" role="alert">
          <strong>More documents requested. </strong>
          {requestNote || "Please upload the additional documents our team asked for."}
        </div>
      ) : null}

      <div className="card">
        <div className="page-title" style={{ marginBottom: 10 }}>
          <h2 style={{ margin: 0 }}>School details</h2>
          {canEdit && !edit ? <Button kind="ghost" onClick={() => setEdit(true)}>Edit details</Button> : null}
        </div>
        {edit && app ? (
          <EditDetails
            view={view}
            types={site?.registration.schoolTypes || []}
            onCancel={() => setEdit(false)}
            onSaved={(next) => { setView(next); setEdit(false); toast("ok", "Details saved."); void onChanged(); }}
          />
        ) : (
          <dl className="facts">
            <div><dt>School name</dt><dd>{app?.schoolName || view.schoolName}</dd></div>
            <div><dt>School type</dt><dd>{app?.schoolType || "—"}</dd></div>
            <div><dt>Address</dt><dd>{[app?.address, app?.city, app?.country].filter(Boolean).join(", ") || "—"}</dd></div>
            <div><dt>Contact number</dt><dd>{app?.contactNumber || "—"}</dd></div>
            <div><dt>Official email</dt><dd>{app?.officialEmail || "—"}</dd></div>
            <div><dt>Owner or principal</dt><dd>{app?.principalName || "—"}</dd></div>
            <div><dt>Registration details</dt><dd>{app?.registrationDetails || "—"}</dd></div>
            <div><dt>Submitted</dt><dd>{app?.submittedAt ? fmtDate(app.submittedAt) : "—"}</dd></div>
          </dl>
        )}
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Verification documents</h2>
        {view.documents.length ? (
          <ul className="doc-list">
            {view.documents.map((d) => (
              <li key={d.id}>
                <div>
                  <strong>{d.documentType}</strong>
                  <small>{d.fileName}{d.uploadedAt ? ` · ${fmtDate(d.uploadedAt)}` : ""}</small>
                  {d.reviewNote ? <small className="field-err">{d.reviewNote}</small> : null}
                </div>
                <Badge value={d.status} />
                <FileLink href={schoolAccountApi.documentUrl(d.id)} label="View" />
                {canEdit && d.status !== "ACCEPTED" ? (
                  <Button kind="ghost" loadingText="Removing…" onClick={async () => { setView(await schoolAccountApi.removeDocument(d.id)); toast("ok", "Document removed."); }}>Remove</Button>
                ) : null}
              </li>
            ))}
          </ul>
        ) : <p className="hint">No documents uploaded yet.</p>}
        {canEdit ? (
          <Form
            busyLabel="Uploading…"
            onSubmit={async () => {
              if (!file) throw new Error("Choose a file first.");
              if (!docType) throw new Error("Choose the document type.");
              if (file.size > 10 * 1024 * 1024) throw new Error("Each document must be 10 MB or smaller.");
              setView(await schoolAccountApi.addDocument(file, docType));
              setFile(null);
              setFileKey((k) => k + 1);
              toast("ok", "Document uploaded.");
            }}
          >
            <div className="reg-doc" style={{ marginTop: 12 }}>
              <select aria-label="Document type" value={docType} onChange={(e) => setDocType(e.target.value)}>
                <option value="">Document type…</option>
                {docTypes.map((t) => <option key={t}>{t}</option>)}
              </select>
              <input key={fileKey} aria-label="Document file" type="file" accept=".pdf,.jpg,.jpeg,.png,.webp" onChange={(e) => setFile(e.target.files?.[0] || null)} />
              <Button type="submit" kind="ghost" loadingText="Uploading…">Upload</Button>
            </div>
          </Form>
        ) : null}
      </div>

      {status === "REJECTED" ? (
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Send again for review</h2>
          <p className="hint">When your details and documents are corrected, send the application back to our team.</p>
          <Button
            kind="brass"
            loadingText="Sending…"
            onClick={async () => {
              setView(await schoolAccountApi.resubmit());
              toast("ok", "Application sent for review.");
              await onChanged();
            }}
          >
            Resubmit application
          </Button>
        </div>
      ) : null}
    </>
  );
}

function EditDetails({ view, types, onSaved, onCancel }: { view: ApplicationView; types: string[]; onSaved: (v: ApplicationView) => void; onCancel: () => void }) {
  const a = view.application;
  const [f, setF] = useState({
    schoolName: a?.schoolName || "",
    schoolType: a?.schoolType || "",
    address: a?.address || "",
    city: a?.city || "",
    country: a?.country || "",
    contactNumber: a?.contactNumber || "",
    officialEmail: a?.officialEmail || "",
    principalName: a?.principalName || "",
    registrationDetails: a?.registrationDetails || "",
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => setF((p) => ({ ...p, [k]: e.target.value }));
  const typeList = f.schoolType && !types.includes(f.schoolType) ? [f.schoolType, ...types] : types;
  return (
    <Form onSubmit={async () => onSaved(await schoolAccountApi.saveApplication(f))}>
      <div className="reg-grid">
        <Field label="School name"><input value={f.schoolName} onChange={set("schoolName")} maxLength={150} required /></Field>
        <Field label="School type">
          <select value={f.schoolType} onChange={set("schoolType")} required>
            <option value="">Choose…</option>
            {typeList.map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Address"><input value={f.address} onChange={set("address")} maxLength={250} required /></Field>
        <Field label="City"><input value={f.city} onChange={set("city")} maxLength={80} required /></Field>
        <Field label="Country"><input value={f.country} onChange={set("country")} maxLength={80} required /></Field>
        <Field label="Contact number"><input value={f.contactNumber} onChange={set("contactNumber")} maxLength={30} required /></Field>
        <Field label="Official email"><input type="email" value={f.officialEmail} onChange={set("officialEmail")} maxLength={150} required /></Field>
        <Field label="Owner or principal name"><input value={f.principalName} onChange={set("principalName")} maxLength={120} required /></Field>
      </div>
      <Field label="Registration details"><textarea value={f.registrationDetails} onChange={set("registrationDetails")} maxLength={2000} /></Field>
      <div className="row">
        <Button type="submit" kind="brass">Save details</Button>
        <Button kind="ghost" onClick={onCancel}>Cancel</Button>
      </div>
    </Form>
  );
}
