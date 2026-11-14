-- =====================================================================
-- V29  DEVZARD eSchool: multi-tenant SaaS platform schema
--
-- ADDITIVE ONLY. No table, column or row that existed before V29 is
-- dropped, renamed or rewritten. The previous application build keeps
-- working against this schema (see docs/DEPLOYMENT.md, "Rollback").
--
-- Naming note: the existing ERP calls a branch a "campus" and already
-- stores it in `campuses` / `campus_id`. That table stays the physical
-- home of branches; the read-only view `school_branches` exposes it under
-- the SaaS name.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tenants (schools): verification + ownership
-- ---------------------------------------------------------------------
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS slug                    VARCHAR(60),
    ADD COLUMN IF NOT EXISTS school_type             VARCHAR(40),
    ADD COLUMN IF NOT EXISTS registration_number     VARCHAR(120),
    ADD COLUMN IF NOT EXISTS verification_status     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS verified_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verified_by             UUID,
    ADD COLUMN IF NOT EXISTS owner_user_id           UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS public_branches_visible BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_verification_status CHECK (verification_status IN (
        'PENDING', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', 'SUSPENDED'));

CREATE UNIQUE INDEX uq_tenants_slug ON tenants (LOWER(slug)) WHERE slug IS NOT NULL;
CREATE INDEX idx_tenants_verification ON tenants (verification_status);

-- ---------------------------------------------------------------------
-- 2. Branches (existing table `campuses`): lifecycle + privacy controls
-- ---------------------------------------------------------------------
ALTER TABLE campuses
    ADD COLUMN IF NOT EXISTS status               VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS is_main              BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS branch_admin_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS permissions          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS suspended_at         TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspended_reason     TEXT,
    ADD COLUMN IF NOT EXISTS deleted_at           TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by           UUID;

ALTER TABLE campuses
    ADD CONSTRAINT chk_campuses_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    ADD CONSTRAINT uq_campuses_id_tenant UNIQUE (id, tenant_id);

-- A school can never have two main branches.
CREATE UNIQUE INDEX uq_campuses_one_main ON campuses (tenant_id) WHERE is_main;
CREATE INDEX idx_campuses_tenant_status ON campuses (tenant_id, status);

-- ---------------------------------------------------------------------
-- 3. Plans, subscriptions, limits, usage
-- ---------------------------------------------------------------------
CREATE TABLE subscription_plans (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code             VARCHAR(30)   NOT NULL,
    name             VARCHAR(80)   NOT NULL,
    description      TEXT,
    price_monthly    NUMERIC(12,2) NOT NULL DEFAULT 0,
    price_yearly     NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency         VARCHAR(10)   NOT NULL DEFAULT 'PKR',
    max_active_users INTEGER       NOT NULL,
    storage_limit_mb INTEGER       NOT NULL,
    ram_budget_mb    INTEGER       NOT NULL,
    max_branches     INTEGER       NOT NULL,
    -- NULL = every ERP module; otherwise a JSON array of module keys.
    modules          JSONB,
    -- Marketing bullet points shown on the public Plans page.
    features         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    -- BLOCK_NEW: refuse new users / uploads / branches at the limit.
    -- WARN_ONLY: allow, but report the school as over its limit.
    resource_policy  VARCHAR(20)   NOT NULL DEFAULT 'BLOCK_NEW',
    duration_days    INTEGER,
    is_public        BOOLEAN       NOT NULL DEFAULT TRUE,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    is_default       BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order       INTEGER       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by       UUID,
    CONSTRAINT uq_subscription_plans_code UNIQUE (code),
    CONSTRAINT chk_plan_code CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    CONSTRAINT chk_plan_limits CHECK (max_active_users >= 1 AND storage_limit_mb >= 1
                                      AND ram_budget_mb >= 1 AND max_branches >= 1),
    CONSTRAINT chk_plan_prices CHECK (price_monthly >= 0 AND price_yearly >= 0),
    CONSTRAINT chk_plan_policy CHECK (resource_policy IN ('BLOCK_NEW', 'WARN_ONLY')),
    CONSTRAINT chk_plan_duration CHECK (duration_days IS NULL OR duration_days >= 1)
);
-- Exactly one plan supplies the platform-wide default limits.
CREATE UNIQUE INDEX uq_subscription_plans_default ON subscription_plans (is_default) WHERE is_default;
CREATE TRIGGER trg_subscription_plans_updated_at
    BEFORE UPDATE ON subscription_plans
    FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- Which plan a school is on. Distinct from the existing `subscriptions`
-- table, which stays the manual invoice / payment-proof ledger.
CREATE TABLE school_subscriptions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID          NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    plan_id            UUID          NOT NULL REFERENCES subscription_plans (id) ON DELETE RESTRICT,
    status             VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    billing_cycle      VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    price_snapshot     NUMERIC(12,2),
    currency           VARCHAR(10),
    started_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    expires_at         TIMESTAMPTZ,
    -- Reserved for a future payment gateway; unused today.
    payment_provider   VARCHAR(40),
    external_reference VARCHAR(200),
    notes              TEXT,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by         UUID,
    CONSTRAINT chk_school_sub_status CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED')),
    CONSTRAINT chk_school_sub_cycle CHECK (billing_cycle IN ('NONE', 'MONTHLY', 'YEARLY'))
);
-- One current plan per school; older rows are kept as history.
CREATE UNIQUE INDEX uq_school_subscriptions_current ON school_subscriptions (tenant_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_school_subscriptions_plan ON school_subscriptions (plan_id);
CREATE INDEX idx_school_subscriptions_tenant ON school_subscriptions (tenant_id, created_at DESC);
CREATE TRIGGER trg_school_subscriptions_updated_at
    BEFORE UPDATE ON school_subscriptions
    FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- Per-school overrides. NULL = inherit from the school's plan.
CREATE TABLE tenant_resource_limits (
    tenant_id        UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    max_active_users INTEGER,
    storage_limit_mb INTEGER,
    ram_budget_mb    INTEGER,
    max_branches     INTEGER,
    note             TEXT,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by       UUID,
    CONSTRAINT chk_tenant_limits CHECK (
        (max_active_users IS NULL OR max_active_users >= 0)
        AND (storage_limit_mb IS NULL OR storage_limit_mb >= 0)
        AND (ram_budget_mb IS NULL OR ram_budget_mb >= 0)
        AND (max_branches IS NULL OR max_branches >= 0))
);

-- Last measured usage per school (refreshed by the application).
CREATE TABLE tenant_usage (
    tenant_id     UUID PRIMARY KEY REFERENCES tenants (id) ON DELETE CASCADE,
    active_users  INTEGER     NOT NULL DEFAULT 0,
    total_users   INTEGER     NOT NULL DEFAULT 0,
    branches      INTEGER     NOT NULL DEFAULT 0,
    students      INTEGER     NOT NULL DEFAULT 0,
    storage_bytes BIGINT      NOT NULL DEFAULT 0,
    measured_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------
-- 4. Memberships: who may enter which school and which branch
-- ---------------------------------------------------------------------
CREATE TABLE user_school_memberships (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tenant_id  UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    -- SaaS role inside this school.
    role       VARCHAR(30) NOT NULL,
    -- Permission profile used by the existing ERP modules.
    erp_role   VARCHAR(30) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- TRUE when this row mirrors users.tenant_id (kept in sync by trigger).
    is_home    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    CONSTRAINT uq_user_school_membership UNIQUE (user_id, tenant_id),
    CONSTRAINT chk_usm_role CHECK (role IN (
        'SCHOOL_OWNER', 'SCHOOL_ADMIN', 'BRANCH_ADMIN', 'TEACHER', 'STAFF', 'STUDENT', 'PARENT')),
    CONSTRAINT chk_usm_erp_role CHECK (erp_role IN (
        'SCHOOL_ADMIN', 'PRINCIPAL', 'TEACHER', 'ACCOUNT_OFFICER', 'PARENT', 'STUDENT')),
    CONSTRAINT chk_usm_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REMOVED'))
);
CREATE INDEX idx_usm_tenant ON user_school_memberships (tenant_id, status, role);
-- One active owner per school.
CREATE UNIQUE INDEX uq_usm_single_owner ON user_school_memberships (tenant_id)
    WHERE role = 'SCHOOL_OWNER' AND status = 'ACTIVE';
CREATE TRIGGER trg_usm_updated_at
    BEFORE UPDATE ON user_school_memberships
    FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

CREATE TABLE user_branch_memberships (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    tenant_id  UUID        NOT NULL,
    branch_id  UUID        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    -- TRUE when this row mirrors users.campus_id (kept in sync by trigger).
    is_home    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID,
    CONSTRAINT uq_user_branch_membership UNIQUE (user_id, branch_id),
    CONSTRAINT chk_ubm_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    -- The branch must belong to the same school as the membership ...
    CONSTRAINT fk_ubm_branch_same_tenant FOREIGN KEY (branch_id, tenant_id)
        REFERENCES campuses (id, tenant_id) ON DELETE CASCADE,
    -- ... and the user must already be a member of that school.
    CONSTRAINT fk_ubm_school_membership FOREIGN KEY (user_id, tenant_id)
        REFERENCES user_school_memberships (user_id, tenant_id) ON DELETE CASCADE
);
CREATE INDEX idx_ubm_branch ON user_branch_memberships (branch_id, status);
CREATE INDEX idx_ubm_user ON user_branch_memberships (user_id, tenant_id);

-- Optional second factor for entering a branch. Only a BCrypt hash is stored.
CREATE TABLE branch_access_credentials (
    branch_id       UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until    TIMESTAMPTZ,
    rotated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by      UUID,
    CONSTRAINT fk_bac_branch FOREIGN KEY (branch_id, tenant_id)
        REFERENCES campuses (id, tenant_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- 5. School registration and verification
-- ---------------------------------------------------------------------
CREATE TABLE school_applications (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    applicant_user_id      UUID REFERENCES users (id) ON DELETE SET NULL,
    school_name            VARCHAR(200) NOT NULL,
    logo_url               VARCHAR(500),
    address                VARCHAR(300) NOT NULL,
    city                   VARCHAR(100) NOT NULL,
    country                VARCHAR(100) NOT NULL,
    contact_number         VARCHAR(30)  NOT NULL,
    official_email         VARCHAR(150) NOT NULL,
    principal_name         VARCHAR(200) NOT NULL,
    school_type            VARCHAR(40)  NOT NULL,
    registration_details   TEXT,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    documents_requested    BOOLEAN      NOT NULL DEFAULT FALSE,
    documents_request_note TEXT,
    rejection_reason       TEXT,
    suspension_reason      TEXT,
    review_note            TEXT,
    submitted_at           TIMESTAMPTZ,
    reviewed_at            TIMESTAMPTZ,
    reviewed_by            UUID,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_school_application_tenant UNIQUE (tenant_id),
    CONSTRAINT chk_school_application_status CHECK (status IN (
        'PENDING', 'UNDER_REVIEW', 'VERIFIED', 'REJECTED', 'SUSPENDED'))
);
CREATE INDEX idx_school_applications_status ON school_applications (status, created_at DESC);
CREATE TRIGGER trg_school_applications_updated_at
    BEFORE UPDATE ON school_applications
    FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- Verification documents. Files live outside the public upload path and are
-- only served through authenticated endpoints.
CREATE TABLE school_verifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    application_id UUID         NOT NULL REFERENCES school_applications (id) ON DELETE CASCADE,
    document_type  VARCHAR(40)  NOT NULL,
    original_name  VARCHAR(200) NOT NULL,
    storage_key    VARCHAR(500) NOT NULL,
    content_type   VARCHAR(120) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'SUBMITTED',
    review_note    TEXT,
    uploaded_by    UUID,
    uploaded_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reviewed_by    UUID,
    reviewed_at    TIMESTAMPTZ,
    CONSTRAINT chk_school_verification_status CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_school_verification_size CHECK (size_bytes >= 0)
);
CREATE INDEX idx_school_verifications_app ON school_verifications (application_id, uploaded_at);
CREATE INDEX idx_school_verifications_tenant ON school_verifications (tenant_id);

-- ---------------------------------------------------------------------
-- 6. Platform-wide settings (branding, public site content, defaults)
-- ---------------------------------------------------------------------
CREATE TABLE platform_settings (
    key        VARCHAR(60) PRIMARY KEY,
    value      JSONB       NOT NULL,
    -- TRUE = may be returned by the unauthenticated public endpoint.
    is_public  BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by UUID
);

-- ---------------------------------------------------------------------
-- 7. Session context: remembered selection + branch on refresh tokens
-- ---------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_tenant_id UUID REFERENCES tenants (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS last_campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;

ALTER TABLE refresh_tokens
    ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created ON audit_logs (action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stored_files_tenant ON stored_files (tenant_id);

-- ---------------------------------------------------------------------
-- 8. Branch column on ERP tables that could only reach a branch through
--    a join. (Tables that already had campus_id are unchanged.)
--    Filled on insert by trigger (V30) and backfilled for old rows (V30).
-- ---------------------------------------------------------------------
ALTER TABLE sections              ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE class_subjects        ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE teacher_assignments   ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE timetable_slots       ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE fee_structures        ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE homework              ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE announcements         ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE student_attendance    ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE exam_results          ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE fee_challans          ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE student_fee_discounts ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE homework_submissions  ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE online_exam_attempts  ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE teacher_attendance    ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE salary_deductions     ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE staff_profiles        ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;
ALTER TABLE staff_salaries        ADD COLUMN IF NOT EXISTS campus_id UUID REFERENCES campuses (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_sections_tenant_campus              ON sections (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_class_subjects_tenant_campus        ON class_subjects (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_teacher_assignments_tenant_campus   ON teacher_assignments (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_timetable_slots_tenant_campus       ON timetable_slots (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_fee_structures_tenant_campus        ON fee_structures (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_homework_tenant_campus              ON homework (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_announcements_tenant_campus         ON announcements (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_student_attendance_tenant_campus    ON student_attendance (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_exam_results_tenant_campus          ON exam_results (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_fee_challans_tenant_campus          ON fee_challans (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_student_fee_discounts_tenant_campus ON student_fee_discounts (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_homework_submissions_tenant_campus  ON homework_submissions (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_online_exam_attempts_tenant_campus  ON online_exam_attempts (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_teacher_attendance_tenant_campus    ON teacher_attendance (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_salary_deductions_tenant_campus     ON salary_deductions (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_staff_profiles_tenant_campus        ON staff_profiles (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_staff_salaries_tenant_campus        ON staff_salaries (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_students_tenant_campus              ON students (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_users_tenant_campus                 ON users (tenant_id, campus_id);
CREATE INDEX IF NOT EXISTS idx_school_classes_tenant_campus        ON school_classes (tenant_id, campus_id);

-- ---------------------------------------------------------------------
-- 9. Compatibility views under the SaaS names
-- ---------------------------------------------------------------------
CREATE VIEW school_branches AS
SELECT c.id,
       c.tenant_id,
       c.name,
       c.code,
       c.address,
       c.city,
       c.phone,
       c.email,
       c.status,
       c.is_main,
       c.branch_admin_user_id,
       c.permissions,
       c.suspended_at,
       c.deleted_at,
       c.created_at,
       c.updated_at
FROM campuses c;

CREATE VIEW tenant_settings AS
SELECT s.*
FROM school_settings s;
