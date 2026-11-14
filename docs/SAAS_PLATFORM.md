# DEVZARD eSchool: multi-school platform

Technical analysis and design of the change that turns the single-school ERP into a
platform where many schools register, are verified, and work in private spaces.

Contents

1. [What existed](#1-what-existed)
2. [What was wrong for a public platform](#2-what-was-wrong-for-a-public-platform)
3. [What changed, in one page](#3-what-changed-in-one-page)
4. [Roles](#4-roles)
5. [Sign-in and choosing a school or branch](#5-sign-in-and-choosing-a-school-or-branch)
6. [School registration and verification](#6-school-registration-and-verification)
7. [Data isolation](#7-data-isolation)
8. [Branches](#8-branches)
9. [Plans and limits](#9-plans-and-limits)
10. [Platform website and settings](#10-platform-website-and-settings)
11. [Files](#11-files)
12. [Rate limiting and abuse protection](#12-rate-limiting-and-abuse-protection)
13. [Database changes](#13-database-changes)
14. [API reference](#14-api-reference)
15. [Web application](#15-web-application)
16. [How it was tested](#16-how-it-was-tested)
17. [Known gaps and honest limits](#17-known-gaps-and-honest-limits)

---

## 1. What existed

- Spring Boot 3.4 / Java 21 modular monolith, PostgreSQL, Flyway `V1`–`V28`, 56 tables.
- A `tenants` table (schools) and `tenant_id` on school-owned rows, with a Hibernate
  tenant filter. Campuses (`campuses`) inside a school.
- Seven roles: `ERP_OWNER`, `SCHOOL_ADMIN`, `PRINCIPAL`, `TEACHER`, `ACCOUNT_OFFICER`,
  `PARENT`, `STUDENT`. Authorisation by `@PreAuthorize` on controllers.
- JWT access and refresh tokens. Usernames and emails unique across the whole system.
- React + Vite web app with one sign-in screen per role, and the home page `/` showing
  **one school's** public website (the first school in the database).
- Manual subscription tracking with payment slips. No online payment.

So the data model was already multi-tenant. What was missing was everything around it:
how a school gets in, who decides it is real, what a visitor can see, and limits.

## 2. What was wrong for a public platform

| Area | Problem found |
| --- | --- |
| Home page | `/` and `/api/v1/public/school` returned a school without being asked for one. On a shared platform that shows one school's name, branches and photos to every visitor. |
| Onboarding | A school could only be created by the ERP owner, or by an open "first-time setup" call that needed no sign-in. |
| Verification | No concept of a school being pending, reviewed, rejected or suspended. |
| Sign-in | Seven role portals; the user had to know their role. No way to belong to more than one branch. |
| Branch privacy | Campus scoping existed for lists, but a school admin tied to one campus could still open another campus's student or staff record by its id. |
| Uploaded files | `/api/v1/files/{tenant}/{name}` was readable by anyone who had the address. |
| Limits | None. Any school could add any number of users, campuses and files. |
| Abuse | No rate limiting on sign-in or public forms. |
| Monitoring | No view of real server load for the platform owner. |

## 3. What changed, in one page

- **Platform website** at `/` (home, features, plans, about, contact) that never shows a
  school. A school's own site lives at `/s/<SCHOOLCODE>`.
- **School registration** (`/register`) with documents, and a **verification workflow**
  run by the Platform Owner: pending → under review → verified / rejected, plus suspend
  and reactivate. An unverified school can only see its application.
- **One sign-in** (`/login`) for everyone. The server works out the role. People with
  access to several branches choose one; a branch can have its own extra password.
- **Platform roles** layered on top of the existing roles (nothing existing was removed).
- **Branch management** for the School Owner: create, edit, suspend, archive, appoint a
  Branch Admin, grant staff access, set a branch password, switch modules per branch.
- **Plans and limits**: active users, branches and storage are enforced on the server
  and in the database. Five seeded plans; per-school overrides.
- **Private files**: uploads need a signed-in user of the same school, the Platform
  Owner, or a short-lived file token. Only files a verified school deliberately
  publishes on its website are public.
- **Rate limiting** on sign-in, registration and public endpoints.
- **Platform console** with real CPU, memory, disk, database size, request counts and
  schools near a limit; plan editor; website editor.

Design rules that were followed:

1. **Additive only.** `V29`/`V30` add tables and columns. Existing rows are backfilled,
   never rewritten destructively. The previous build still runs on the new schema.
2. **Existing authorisation stays.** Every `@PreAuthorize` rule and the Hibernate tenant
   filter are untouched. New rules are extra checks in front of them.
3. **The server decides.** The web app hides what a user may not do, but every rule is
   enforced by the API; the web checks are for convenience only.
4. **Same answer for "not allowed" and "does not exist"** wherever telling the two apart
   would reveal that another school, branch or record exists (uniform `404`).

## 4. Roles

The existing role (`users.role`) is kept and still drives `@PreAuthorize`. A second,
platform-level role is stored per school in `user_school_memberships.role` and added to
the token (`srole` claim) and to the granted authorities (`ROLE_<role>`).

| Platform role | Existing role it sits on | Scope |
| --- | --- | --- |
| `PLATFORM_OWNER` | `ERP_OWNER` | Whole platform. Verifies schools, sets plans, edits the website. |
| `SCHOOL_OWNER` | `SCHOOL_ADMIN` | Whole school, every branch. Only role that manages branches. Never asked for a branch password. |
| `SCHOOL_ADMIN` | `SCHOOL_ADMIN` without a branch, or `PRINCIPAL` | Whole school. |
| `BRANCH_ADMIN` | `SCHOOL_ADMIN` tied to a branch | Their branch(es) only. |
| `STAFF` | `ACCOUNT_OFFICER` | Their branch(es) only. |
| `TEACHER`, `PARENT`, `STUDENT` | same | Their branch; parents and students only their own records. |

How existing users were mapped: the longest-standing active school admin of each school
became its School Owner; a school admin already tied to a campus became that branch's
Branch Admin; principals became School Admin; account officers became Staff. The first
school admin created for any new school becomes its owner (database trigger, serialised
per school so two cannot both win).

## 5. Sign-in and choosing a school or branch

`POST /api/v1/auth/login` with a username or email and password.

- If there is exactly one place the user can work, the response contains tokens and a
  `context` (`role`, `tenantId`, `branchId`, `allBranches`, `canSwitch`).
- If there are several (for example an owner with three branches, or a teacher given
  access to a second branch), the response is
  `{ selectionRequired: true, selectionToken, schools: [...] }` with **only that user's**
  schools and branches. The selection token lives 300 seconds and can do nothing except
  be exchanged at `POST /auth/select`.
- The last place a user worked is remembered and reused at the next sign-in while it is
  still allowed.
- `POST /auth/switch` moves a signed-in user to another permitted branch (or back to
  "All branches" for school-wide roles) and issues new tokens. `GET /auth/contexts`
  lists the choices.

Branch password (optional, set by the School Owner):

- Asked when a non-owner enters that branch. Stored as a BCrypt hash.
- Five wrong attempts lock that user out of that branch for 15 minutes
  (`BRANCH_LOCKED`), even with the right password.
- Setting or changing it ends existing sessions for that branch.

Every authenticated request is re-checked (`TenantAccessGuardFilter`): the school must
still be verified and active, the branch must still be active, and the user must still
be a member. Access removed by an owner takes effect on the next request
(`401 ACCESS_REVOKED`), not when the token expires.

## 6. School registration and verification

Registration (`POST /api/v1/public/register`, multipart) creates, in one transaction:
the school (`verification_status = PENDING`), the School Owner account, the application
record and the uploaded documents. Rules: password 8–100 characters with a letter and a
digit; documents PDF, JPG, PNG or WEBP checked by content, 10 MB each, at most 10.
Validation errors come back as a list per field. Registration can be closed, and the
school types, document types and minimum document count are edited by the Platform
Owner.

States and who can move between them:

```
PENDING ──► UNDER_REVIEW ──► VERIFIED ◄──► SUSPENDED
   │             │   ▲
   │             │   └── REQUEST_DOCUMENTS (stays under review, school is told what to send)
   └──────► REJECTED ──(school edits and resubmits)──► PENDING
```

- **Approve** marks the school verified and makes sure it has a Main Campus.
- **Reject**, **Request documents** and **Suspend** need a note; the school sees it.
- Each document can be accepted or rejected separately with a reason.
- A rejected school can edit its details, upload new documents and resubmit. A rejected
  document does not count towards the required minimum.
- Nothing is ever deleted by rejecting or suspending.

What an unverified, rejected or suspended school can reach: sign-in and sign-out, its own
application and documents, its status. Every other API answers
`403 SCHOOL_NOT_VERIFIED` or `403 SCHOOL_SUSPENDED`, and the web app shows a status page
instead of the ERP. Its public website and admission form answer `404`.

## 7. Data isolation

Three layers, all on the server:

1. **School.** The tenant comes from the token, never from the request, for everyone but
   the Platform Owner. The existing Hibernate tenant filter applies to every school
   session. New SQL in the `saas` package always filters by the caller's `tenant_id`.
2. **Branch.** A branch session carries its `branchId`. Lists are scoped to it. Opening
   a student or staff member of another branch by id returns `404`.
3. **Person.** Unchanged: parents see linked children, students see themselves.

Public surface:

- `GET /public/school?code=` and the admission endpoints require a school code and answer
  only for a **verified, active** school. Wrong code, unverified and suspended all give
  the same `404`. Without a code there is no school (unless `DEFAULT_PUBLIC_SCHOOL` is
  set for a single-school install).
- A school's branches are listed publicly only when the school has chosen to show them
  (`public_branches_visible`; on for schools that existed before, off for new ones).
- `GET /public/site` returns platform branding and public plans only. Monitoring
  thresholds and non-public plans are never included.

## 8. Branches

A branch is the existing "campus". Only the School Owner manages them.

- **Create / edit**, limited by the plan's branch count.
- **Status:** active, suspended, archived. The Main branch cannot be suspended or
  archived. Archiving keeps every record, blocks entry, and frees a plan slot.
- **Branch Admin:** pick existing staff or create a new account (a temporary password is
  shown once). The School Owner cannot be made a branch admin.
- **Staff access:** give a staff member access to an additional branch, or remove it.
- **Password:** optional extra password for entering the branch (6–100 characters).
- **Modules:** switch ERP modules off for one branch. People in that branch get
  `403 BRANCH_MODULE_DISABLED` for that module; other branches are unaffected.

## 9. Plans and limits

| Plan | Active users | Branches | Storage | RAM budget | Public |
| --- | ---: | ---: | ---: | ---: | :---: |
| Free (default for new schools) | 120 | 1 | 2 GB | 2 GB | yes |
| Basic | 300 | 3 | 10 GB | 3 GB | yes |
| Standard | 800 | 5 | 25 GB | 4 GB | yes |
| Premium | 2,000 | 15 | 100 GB | 8 GB | yes |
| Custom (existing schools) | 5,000 | 50 | 200 GB | 8 GB | no |

- "Active users" are all accounts that can sign in (students, parents, teachers, staff,
  admins). Deactivated accounts do not count.
- Effective limit = per-school override, else the school's current plan, else the
  default plan.
- **Enforced twice**: in the service (clear messages `USER_LIMIT_REACHED`,
  `BRANCH_LIMIT_REACHED`, `STORAGE_LIMIT_REACHED`) and by database triggers, so bulk
  imports and concurrent requests cannot slip past.
- Lowering a limit or plan never deletes anything. The school simply cannot add more
  until it is under the limit again.
- Plan changes keep history. Plans are priced and changed by the platform team; there is
  no online payment. A paid plan with no price set shows "Contact us".
- **RAM is a planning figure.** All schools share one Java process and one database, so
  memory cannot be reserved per school. The console shows the total promised next to
  what the server really has, so the platform is not oversold by accident.

## 10. Platform website and settings

Stored as JSON in `platform_settings` and edited in the Platform Owner console:

| Key | Contents |
| --- | --- |
| `branding` | Name, logo, colours, tagline |
| `homepage` | Hero text and image, feature cards, "how it works" steps, about text |
| `navigation`, `footer` | Menu and footer links |
| `contact` | Email, phone, address |
| `announcement` | Optional banner with tone and link |
| `registration` | Open/closed, school types, document types, minimum documents |
| `monitoring` | Warning thresholds for the console (never public) |

Images are uploaded with `POST /platform/assets` (images only, checked by content) and
served from `/public/assets/{name}`.

## 11. Files

`GET /api/v1/files/{tenantId}/{name}` now answers `404` unless one of these is true:

- the caller is signed in to that same school, or is the Platform Owner;
- the request carries a valid file token: the `dz_files` cookie (HttpOnly, SameSite=Lax,
  Secure over HTTPS, 12 hours, set at sign-in and cleared at sign-out) or `?t=`;
- the file is referenced by the public website of a **verified** school (logo, banners,
  gallery).

Verification documents are stored outside the normal upload area and are only served
through the application and platform document endpoints. Storage used is counted per
school against its plan.

## 12. Rate limiting and abuse protection

In-memory, per IP address, on by default:

| Group | Default |
| --- | --- |
| Sign-in, select, refresh | 20 per minute |
| School registration | 10 per hour |
| Other public writes (admission form and similar) | 30 per minute |
| Public reads | 240 per minute |

Over the limit: `429 RATE_LIMITED` with `Retry-After`. Also: a wrong password and an
unknown user give the same answer; uploads over the size limit get `413 FILE_TOO_LARGE`;
error responses never include stack traces.

The limiter keeps its counters in the application's memory. That is correct for the
single-server deployment this project targets. With more than one API instance each
would count separately; put the limit in Nginx or a shared store in that case.

## 13. Database changes

`V29__saas_platform_schema.sql` (structure) and `V30__saas_seed_backfill_and_guards.sql`
(seed data, backfill, functions and triggers).

New tables: `subscription_plans`, `school_subscriptions`, `tenant_resource_limits`,
`school_applications`, `school_verifications`, `platform_settings`,
`user_school_memberships`, `user_branch_memberships`, `branch_access_credentials`,
`tenant_usage`.

New columns: `tenants` (verification status, owner, slug, school type,
`public_branches_visible`, verified at/by), `campuses` (status, `is_main`, branch admin,
permissions), `users` (last school and branch used), `refresh_tokens` (branch).

Functions and triggers: effective limits; user-seat and branch guards (`DZ001`,
`DZ002`); membership kept in step with `users`; first school admin becomes owner; first
branch becomes Main; new schools start on the default plan; `campus_id` filled on rows
that belong to a class, student or staff member.

Existing data: see "What the migration does to existing data" in
[DEPLOYMENT.md](DEPLOYMENT.md#8-what-the-migration-does-to-existing-data).

## 14. API reference

Base path `/api/v1`. Responses use the existing envelope
`{ success, message, data, errorCode, timestamp }`. Exact request and response types are
in `web/src/api/saas.ts`.

**Sign-in**

| Method and path | Who | Purpose |
| --- | --- | --- |
| `POST /auth/login` | public | Tokens and context, or a selection |
| `POST /auth/select` | selection token | Choose school and branch (and branch password) |
| `POST /auth/switch` | signed in | Move to another permitted branch |
| `GET /auth/contexts` | signed in | Places this user may work |

**Public**

| Method and path | Purpose |
| --- | --- |
| `GET /public/site` | Platform branding, home page content, public plans, registration rules |
| `POST /public/register` | Register a school (multipart) → `201` |
| `GET /public/assets/{name}` | Platform website images |
| `GET /public/school?code=` | One verified school's public website |

**School account** (signed in to the school)

| Method and path | Purpose |
| --- | --- |
| `GET /school/overview` | Plan, limits, usage meters, verification status |
| `GET /school/application`, `PUT /school/application` | View and edit the application (owner; edit while not verified) |
| `POST /school/application/resubmit` | Send again after rejection or a document request |
| `POST /school/application/documents` | Add a document |
| `GET`, `DELETE /school/application/documents/{id}` | Download or remove a document |

**Branches** (School Owner; a branch session sees only its own branch)

| Method and path | Purpose |
| --- | --- |
| `GET /branches`, `POST /branches`, `PUT /branches/{id}` | List, create, edit |
| `POST /branches/{id}/status` | Activate, suspend, archive |
| `PUT /branches/{id}/admin` | Appoint a branch admin (existing user or new account) |
| `PUT /branches/{id}/password` | Set or remove (blank) the branch password |
| `PUT /branches/{id}/permissions` | Modules on or off for the branch |
| `GET`, `POST /branches/{id}/members`, `DELETE /branches/{id}/members/{userId}` | Staff access |
| `GET /branches/staff` | Staff who can be given access |

**Platform** (`PLATFORM_OWNER` only)

| Method and path | Purpose |
| --- | --- |
| `GET /platform/overview` | Counts, real server load, schools near a limit |
| `GET /platform/schools?status=&q=`, `GET /platform/schools/{id}` | Verification queue and detail |
| `POST /platform/schools/{id}/review` | `UNDER_REVIEW`, `REQUEST_DOCUMENTS`, `APPROVE`, `REJECT`, `SUSPEND`, `REACTIVATE` |
| `GET /platform/schools/{id}/documents/{docId}`, `POST .../review` | Open and accept or reject a document |
| `PUT /platform/schools/{id}/plan`, `PUT /platform/schools/{id}/limits` | Change plan; set or clear overrides |
| `GET`, `POST /platform/plans`, `PUT /platform/plans/{id}` | Plans |
| `GET /platform/settings`, `PUT /platform/settings/{key}` | Website and rules |
| `POST /platform/assets` | Upload a website image |

**Error codes added**

| Status | Codes |
| --- | --- |
| 400 | `VALIDATION_FAILED` (with `errors`), `FILE_TYPE`, `NO_APPLICATION`, `DOCUMENT_LIMIT`, `DOCUMENTS_REQUIRED`, `NOTE_REQUIRED`, `INVALID_TRANSITION`, `ACTION_UNKNOWN`, `INVALID_ID`, `BRANCH_ADMIN_ROLE`, `BRANCH_ADMIN_OWNER`, `BRANCH_ADMIN_NAME` |
| 401 | `ACCESS_REVOKED` |
| 403 | `ACCESS_DENIED`, `SCHOOL_NOT_VERIFIED`, `SCHOOL_SUSPENDED`, `BRANCH_UNAVAILABLE`, `BRANCH_REQUIRED`, `BRANCH_LOCKED`, `BRANCH_PASSWORD_REQUIRED`, `BRANCH_PASSWORD_INVALID`, `BRANCH_MODULE_DISABLED`, `REGISTRATION_CLOSED` |
| 409 | `USER_LIMIT_REACHED`, `BRANCH_LIMIT_REACHED`, `STORAGE_LIMIT_REACHED` |
| 413 | `FILE_TOO_LARGE` |
| 429 | `RATE_LIMITED` |

## 15. Web application

| Address | Page |
| --- | --- |
| `/`, `/features`, `/pricing`, `/about`, `/contact` | Platform website |
| `/register` | School registration |
| `/login` | Central sign-in, school and branch chooser, branch password |
| `/s/<CODE>`, `/s/<CODE>/apply` | A school's public website and admission form |
| `/app/...` | School ERP. Shows the verification status page instead while the school is not verified. |
| `/app/branches`, `/app/plan` | Branch management; plan and usage |
| `/admin/app/platform`, `/verification`, `/plans`, `/site` | Platform Owner console |

Old addresses `/portals`, `/login/<role>` and `/admin` redirect to `/login`. The
per-role sign-in screens were removed. The top bar of the school app shows the current
branch and role, and lets permitted users switch.

Code layout: API under `src/main/java/com/erpschool/saas/` (`access`, `branch`,
`limits`, `plan`, `platform`, `registration`, `security`, `support`); web under
`web/src/api/saas.ts`, `web/src/pages/{PlatformHome,RegisterSchool,LoginPage,SchoolStatus}.tsx`,
`web/src/pages/school/Branches.tsx`, `web/src/pages/platform/PlatformAdmin.tsx`.

## 16. How it was tested

| Check | Result |
| --- | --- |
| Unit tests (`mvn test`) | 70 of 70 pass |
| `scripts/saas_smoke_test.py` on a migrated copy of the old database | 137 of 137 |
| Same, on an empty database built from `V1`–`V30` | 137 of 137 |
| `scripts/saas_extra_checks.py` on both databases | 48 of 48 |
| Row counts of all 56 old tables before and after migration (demo seeding off) | identical |
| Previous build started against the migrated database and signed in | works |
| Production-style first start (`SEED_DEMO_DATA=false`, empty database): owner created, school registered, approved, used | works |
| Web: `vite build` | builds |
| Web: real browser walk-through (headless Chromium) of home, register, sign-in, chooser, status page, branches, plan, platform console, school site; desktop 1366 px, mobile 390 px, light and dark | no page errors, no horizontal overflow |

The two scripts are black-box HTTP tests. Among other things they prove: one school
cannot read, list or change another school's students, users, branches, application or
files; a branch admin cannot open another branch's people; a pending, rejected or
suspended school is refused by the ERP; user and branch limits are enforced; a wrong
school code and an unverified school look the same from outside.

## 17. Known gaps and honest limits

These are real and should be decided on, not discovered later.

1. **Branch isolation by record id is complete for students and users only.** Lists in
   every module are branch-scoped, but opening a single record by id inside academics,
   fees, exams, remarks, salaries, notifications and announcements was not individually
   reviewed for cross-**branch** access. Cross-**school** access is blocked everywhere by
   the tenant filter. Until reviewed, treat branches of one school as "private in normal
   use" rather than "hardened against a determined insider of the same school".
2. **The packaged jar was not built in the development sandbox** (the Maven jar plugin
   could not be downloaded there). The application was compiled and run from classes,
   and all tests ran that way. Run `mvn package` once on a normal machine before the
   first production deploy.
3. **Rate-limit counters and the "recent requests" figures are in memory.** They reset
   on restart and are per process. Fine for one server.
4. **RAM per school is not enforceable** on a shared process; it is shown as a budget.
5. **No email is sent.** Schools see decisions and notes when they sign in. Password
   reset by email and "your school was approved" emails need an SMTP integration.
6. **No online payment**, by design. Plans are assigned by the platform team.
7. **Four TypeScript type warnings** in `web/src/pages/school/{Academics,Finance,Teaching}.tsx`
   existed before this work and are unchanged. They do not affect the build (`vite build`
   does not type-check); `npx tsc --noEmit` reports them.
8. **The mobile app (`mobile/`) was not changed.** It still signs in with the same
   `/auth/login` call and works for users with a single place to work. Users who must
   choose a branch need the chooser added there.
9. **Subscription status cache.** A suspension by the old subscription screen can take
   up to the cache lifetime to apply. Suspension through **School verification** applies
   on the next request.
10. **Limit guards under load.** The user and branch guards take a per-school database
    lock, so two simultaneous requests cannot both take the last seat. This is by design
    and covered by sequential tests; it was not load-tested with parallel requests.
11. **Fonts** load from Google Fonts. If the network blocks it the site falls back to
    system fonts.
