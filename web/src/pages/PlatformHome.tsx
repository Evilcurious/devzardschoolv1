import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { usePlatformSite } from "../lib/platform";
import { useSession } from "../lib/session";
import { homeFor } from "../lib/routes";
import { fmtMb, type Plan, type PlatformSite } from "../api/saas";
import { ErrorBox, Loading } from "../ui/kit";
import ThemeToggle from "../ui/ThemeToggle";

const SECTION_FOR: Record<string, string> = {
  "/features": "features",
  "/pricing": "pricing",
  "/about": "about",
  "/contact": "contact",
};

export function PlatformLogo({ site }: { site: PlatformSite | null }) {
  const name = site?.branding.name || "DEVZARD eSchool";
  return (
    <Link to="/" className="site-brand">
      {site?.branding.logoUrl ? <img src={site.branding.logoUrl} alt="" /> : <div className="mark">{name.trim().charAt(0).toUpperCase()}</div>}
      <div>
        <strong>{name}</strong>
      </div>
    </Link>
  );
}

export default function PlatformHome() {
  const { site, error } = usePlatformSite();
  const { user } = useSession();
  const loc = useLocation();
  const [menu, setMenu] = useState(false);

  useEffect(() => {
    setMenu(false);
    const id = SECTION_FOR[loc.pathname];
    if (!site) return;
    if (!id) {
      window.scrollTo({ top: 0 });
      return;
    }
    const t = window.setTimeout(() => document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" }), 30);
    return () => window.clearTimeout(t);
  }, [loc.pathname, loc.key, site]);

  if (error && !site) {
    return <div className="site-boot"><ErrorBox error={error} title="Unable to load the website" onRetry={() => window.location.reload()} /></div>;
  }
  if (!site) return <Loading label="Loading…" />;

  const { homepage, navigation, contact, footer, announcement, registration } = site;
  const hero = homepage.hero || {};
  const open = registration.open !== false;
  const hasContact = Boolean(contact.email || contact.phone || contact.whatsapp || contact.address);
  const links = (navigation || []).filter((l) => l && l.label && l.to);
  const account = user
    ? <Link to={homeFor(user)} className="btn brass sm">Open dashboard</Link>
    : (
      <>
        <Link to="/login" className="btn ghost sm">Sign in</Link>
        {open ? <Link to="/register" className="btn brass sm pf-nav-register">Register school</Link> : null}
      </>
    );

  return (
    <div className="site pf">
      {announcement.enabled && announcement.text ? (
        <div className={`pf-announce ${announcement.tone || "info"}`} role="status">
          <span>{announcement.text}</span>
          {announcement.linkTo && announcement.linkLabel ? <SmartLink to={announcement.linkTo}>{announcement.linkLabel}</SmartLink> : null}
        </div>
      ) : null}

      <header className="pf-nav">
        <PlatformLogo site={site} />
        <nav className={`pf-links ${menu ? "open" : ""}`} aria-label="Main">
          {links.map((l) => <SmartLink key={l.to + l.label} to={l.to}>{l.label}</SmartLink>)}
        </nav>
        <div className="pf-tools">
          <ThemeToggle />
          {account}
          <button type="button" className="icon-btn pf-burger" aria-label="Menu" aria-expanded={menu} onClick={() => setMenu((v) => !v)}>☰</button>
        </div>
      </header>

      <section className="pf-hero">
        <div className="pf-hero-copy">
          {hero.eyebrow ? <p className="kicker">{hero.eyebrow}</p> : null}
          <h1>{hero.title || site.branding.name}</h1>
          {hero.subtitle ? <p className="pf-lead">{hero.subtitle}</p> : null}
          <div className="row" style={{ marginTop: 22 }}>
            {open
              ? <Link to="/register" className="btn brass">{hero.primaryCta || "Register your school"}</Link>
              : <Link to="/login" className="btn brass">Sign in</Link>}
            <Link to="/features" className="btn ghost">{hero.secondaryCta || "See features"}</Link>
          </div>
          <p className="pf-note">Already registered? <Link to="/login">Sign in to your school</Link></p>
        </div>
        <div className="pf-hero-art" aria-hidden>
          {hero.imageUrl ? <img src={hero.imageUrl} alt="" /> : <HeroArt />}
        </div>
      </section>

      {homepage.features?.length ? (
        <section className="pf-section" id="features">
          <p className="kicker">Features</p>
          <h2>Everything your school runs on</h2>
          <div className="pf-grid">
            {homepage.features.map((f, i) => (
              <article key={i} className="card pf-feature">
                <span className="pf-icon" aria-hidden><FeatureIcon name={f.icon} /></span>
                <h3>{f.title}</h3>
                {f.body ? <p>{f.body}</p> : null}
              </article>
            ))}
          </div>
        </section>
      ) : null}

      {homepage.steps?.length ? (
        <section className="pf-section alt">
          <p className="kicker">How it works</p>
          <h2>From registration to your first class</h2>
          <ol className="pf-steps">
            {homepage.steps.map((s, i) => (
              <li key={i} className="card">
                <b>{i + 1}</b>
                <div>
                  <h3>{s.title}</h3>
                  {s.body ? <p>{s.body}</p> : null}
                </div>
              </li>
            ))}
          </ol>
        </section>
      ) : null}

      {site.plans?.length ? (
        <section className="pf-section" id="pricing">
          <p className="kicker">Plans</p>
          <h2>Start free. Grow when you need to.</h2>
          <p className="hint">Each plan sets how many active users, branches and how much storage a school can use. Your plan is changed by our team; there is no online payment.</p>
          <div className="pf-plans">
            {site.plans.map((p) => <PlanCard key={p.id} plan={p} cta={open && !user ? <Link to="/register" className="btn ghost">Choose {p.name}</Link> : null} />)}
          </div>
        </section>
      ) : null}

      {homepage.about?.body ? (
        <section className="pf-section alt" id="about">
          <p className="kicker">About</p>
          <h2>{homepage.about.title || `About ${site.branding.name}`}</h2>
          {homepage.about.body.split(/\n{2,}/).map((para, i) => <p key={i}>{para}</p>)}
        </section>
      ) : null}

      <section className="pf-section" id="contact">
        <p className="kicker">Contact</p>
        <h2>Talk to us</h2>
        {hasContact ? (
          <div className="pf-contact">
            {contact.email ? <a className="card" href={`mailto:${contact.email}`}><small>Email</small><strong>{contact.email}</strong></a> : null}
            {contact.phone ? <a className="card" href={`tel:${contact.phone.replace(/[^+\d]/g, "")}`}><small>Phone</small><strong>{contact.phone}</strong></a> : null}
            {contact.whatsapp ? <a className="card" href={`https://wa.me/${contact.whatsapp.replace(/\D/g, "")}`} target="_blank" rel="noreferrer"><small>WhatsApp</small><strong>{contact.whatsapp}</strong></a> : null}
            {contact.address ? <div className="card"><small>Address</small><strong>{contact.address}</strong></div> : null}
            {contact.hours ? <div className="card"><small>Hours</small><strong>{contact.hours}</strong></div> : null}
          </div>
        ) : (
          <p className="hint">Contact details will appear here soon.</p>
        )}
      </section>

      <footer className="site-foot pf-foot">
        <span>{footer.text || site.branding.name}</span>
        <nav className="row" aria-label="Footer">
          {(footer.links || []).filter((l) => l && l.label && l.to).map((l) => <SmartLink key={l.to + l.label} to={l.to}>{l.label}</SmartLink>)}
          <Link to="/login">Sign in</Link>
        </nav>
      </footer>
    </div>
  );
}

export function PlanCard({ plan, cta, current }: { plan: Plan; cta?: React.ReactNode; current?: boolean }) {
  const price = Number(plan.priceMonthly || 0);
  return (
    <article className={`card pf-plan ${current ? "on" : ""}`}>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h3>{plan.name}</h3>
        {current ? <span className="badge ok">Current plan</span> : plan.isDefault ? <span className="badge mute">Default</span> : null}
      </div>
      <p className="pf-price">
        {price > 0 ? <>{plan.currency || "PKR"} {price.toLocaleString()}<small> / month</small></> : plan.isDefault ? "Free" : "Contact us"}
      </p>
      {plan.description ? <p className="hint" style={{ marginBottom: 12 }}>{plan.description}</p> : null}
      <ul>
        <li><b>{plan.maxActiveUsers.toLocaleString()}</b> active users</li>
        <li><b>{plan.maxBranches}</b> {plan.maxBranches === 1 ? "branch" : "branches"}</li>
        <li><b>{fmtMb(plan.storageLimitMb)}</b> storage</li>
        <li><b>{fmtMb(plan.ramBudgetMb)}</b> RAM budget</li>
        {(plan.features || []).filter((f) => !/active users|branch|storage|ram/i.test(f)).map((f) => <li key={f}>{f}</li>)}
      </ul>
      {cta}
    </article>
  );
}

/** Internal paths use the router; anything else opens as a normal link. */
function SmartLink({ to, children }: { to: string; children: React.ReactNode }) {
  if (/^(https?:|mailto:|tel:)/i.test(to)) return <a href={to} target="_blank" rel="noreferrer">{children}</a>;
  return <Link to={to.startsWith("/") ? to : `/${to}`}>{children}</Link>;
}

function FeatureIcon({ name }: { name?: string }) {
  const paths: Record<string, string> = {
    award: "M12 2a6 6 0 0 0-3.5 10.9L7 21l5-2.5 5 2.5-1.5-8.1A6 6 0 0 0 12 2zm0 2.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z",
    building: "M4 21V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v4h3a2 2 0 0 1 2 2v10zm4-13h3V6H8zm0 4h3v-2H8zm0 4h3v-2H8zm7 0h3v-2h-3zm0 3h3v-2h-3zM8 19h3v-2H8z",
    users: "M9 11a3 3 0 1 0-3-3 3 3 0 0 0 3 3zm8 0a2.5 2.5 0 1 0-2.5-2.5A2.5 2.5 0 0 0 17 11zM4 20v-1.4A5 5 0 0 1 9 14a5 5 0 0 1 5 4.6V20zm10.2 0v-1.2A6.2 6.2 0 0 0 14 16a4.4 4.4 0 0 1 6 3.4V20z",
    calendar: "M7 3v2H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2V3h-2v2H9V3zm12 8v8H5v-8z",
    check: "M9.5 16.2 5.3 12l-1.4 1.4 5.6 5.6 11-11-1.4-1.4z",
    wallet: "M4 6h14a2 2 0 0 1 2 2v1h-6a3 3 0 0 0 0 6h6v1a2 2 0 0 1-2 2H4zm10 5h7v2h-7a1 1 0 0 1 0-2z",
    branch: "M4 20V9l8-5 8 5v11h-6v-6h-4v6z",
    shield: "M12 3 4 6v6c0 4.6 3.2 8.4 8 9.6 4.8-1.2 8-5 8-9.6V6zm-1.2 12.4L7.4 12l1.4-1.4 2 2 4.4-4.4L16.6 9.6z",
    book: "M5 4h9a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zm3 13h7V7a1 1 0 0 0-1-1H7v10.2A3 3 0 0 1 8 17zm11-11h1v14h-1z",
    chart: "M5 19V9h3v10zm5.5 0V5h3v14zM16 19v-7h3v7z",
  };
  return (
    <svg viewBox="0 0 24 24" width="22" height="22">
      <path fill="currentColor" d={paths[name || ""] || paths.check} />
    </svg>
  );
}

function HeroArt() {
  return (
    <div className="pf-art">
      <div className="pf-art-card a"><small>Students</small><b>1,248</b><i style={{ width: "72%" }} /></div>
      <div className="pf-art-card b"><small>Attendance today</small><b>96%</b><i style={{ width: "96%" }} /></div>
      <div className="pf-art-card c"><small>Branches</small><b>3</b><i style={{ width: "40%" }} /></div>
    </div>
  );
}
