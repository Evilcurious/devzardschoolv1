-- =====================================================================
-- V30  DEVZARD eSchool: default plans + settings, backfill of existing
--      schools into the SaaS model, and database-level guards.
--
-- ADDITIVE ONLY. Existing rows are never deleted. The only updates to
-- pre-existing rows are:
--   * tenants        : new columns (slug, verification_status, owner_user_id)
--   * campuses       : is_main flag on one existing branch per school
--   * students / school_classes with NO branch : assigned to the school's
--                      main branch
--   * the new campus_id columns added in V29
-- `updated_at` is preserved on backfilled ERP rows where the database user
-- is allowed to pause the updated_at triggers (it is, when it owns the
-- tables, which is the case for a Flyway-managed schema).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Default plans. Every number here is editable by the Platform Owner.
--    Paid plans ship with price 0 = "not priced yet" (shown as "Contact us").
-- ---------------------------------------------------------------------
INSERT INTO subscription_plans
    (code, name, description, price_monthly, price_yearly, max_active_users, storage_limit_mb,
     ram_budget_mb, max_branches, features, is_public, is_default, sort_order)
VALUES
    ('FREE', 'Free', 'Everything a single-campus school needs to get started.', 0, 0,
     120, 2048, 2048, 1,
     '["120 active users", "2 GB file storage", "1 branch (Main Campus)", "All core ERP modules"]'::jsonb,
     TRUE, TRUE, 10),
    ('BASIC', 'Basic', 'For growing schools that need more seats and a second campus.', 0, 0,
     300, 10240, 3072, 3,
     '["300 active users", "10 GB file storage", "Up to 3 branches", "All core ERP modules"]'::jsonb,
     TRUE, FALSE, 20),
    ('STANDARD', 'Standard', 'For established schools running several branches.', 0, 0,
     800, 25600, 4096, 5,
     '["800 active users", "25 GB file storage", "Up to 5 branches", "All core ERP modules"]'::jsonb,
     TRUE, FALSE, 30),
    ('PREMIUM', 'Premium', 'For large school networks.', 0, 0,
     2000, 102400, 8192, 15,
     '["2,000 active users", "100 GB file storage", "Up to 15 branches", "All core ERP modules"]'::jsonb,
     TRUE, FALSE, 40),
    ('CUSTOM', 'Custom', 'Limits agreed individually with the platform owner.', 0, 0,
     5000, 204800, 8192, 50,
     '["Limits tailored to your school", "Dedicated onboarding"]'::jsonb,
     FALSE, FALSE, 50);

-- ---------------------------------------------------------------------
-- 2. Platform settings (branding + public site content)
-- ---------------------------------------------------------------------
INSERT INTO platform_settings (key, value, is_public) VALUES
('branding', '{
  "name": "DEVZARD eSchool",
  "shortName": "eSchool",
  "tagline": "One platform for every school, every branch.",
  "logoUrl": "",
  "primaryColor": "#1d4ed8",
  "accentColor": "#0ea5e9",
  "theme": "light",
  "layout": "wide"
}'::jsonb, TRUE),
('homepage', '{
  "hero": {
    "eyebrow": "School management, simplified",
    "title": "Run your whole school from one secure place",
    "subtitle": "Admissions, attendance, exams, fees, staff and parent communication. Register your school for free and start as soon as it is verified.",
    "primaryCta": "Register your school",
    "secondaryCta": "See features"
  },
  "features": [
    {"icon": "users", "title": "Students and staff", "body": "Profiles, classes, sections, promotions and staff records in one place."},
    {"icon": "check", "title": "Attendance", "body": "Daily student and teacher attendance with history and reports."},
    {"icon": "award", "title": "Exams and results", "body": "Date sheets, marks entry, online exams and published results."},
    {"icon": "wallet", "title": "Fees and salaries", "body": "Fee structures, challans, payment proofs, discounts and payroll."},
    {"icon": "building", "title": "Private branches", "body": "Manage every campus privately from the school owner dashboard."},
    {"icon": "shield", "title": "Isolated by design", "body": "Each school only ever sees its own data. Access is checked on the server for every request."}
  ],
  "steps": [
    {"title": "Register", "body": "Create your school account and tell us about your school."},
    {"title": "Verify", "body": "Upload your registration documents. Our team reviews them."},
    {"title": "Start", "body": "Once approved, your Main Campus is ready and the full ERP unlocks."}
  ],
  "about": {
    "title": "About DEVZARD eSchool",
    "body": "DEVZARD eSchool is a multi-school management platform. Every school works in its own private space with its own users, branches and records, on one shared and centrally maintained system."
  }
}'::jsonb, TRUE),
('navigation', '[
  {"label": "Home", "to": "/"},
  {"label": "Features", "to": "/features"},
  {"label": "Plans", "to": "/plans"},
  {"label": "About", "to": "/about"},
  {"label": "Contact", "to": "/contact"}
]'::jsonb, TRUE),
('footer', '{
  "text": "DEVZARD eSchool",
  "links": [
    {"label": "Features", "to": "/features"},
    {"label": "Plans", "to": "/plans"},
    {"label": "Contact", "to": "/contact"}
  ]
}'::jsonb, TRUE),
('contact', '{"email": "", "phone": "", "whatsapp": "", "address": "", "hours": ""}'::jsonb, TRUE),
('announcement', '{"enabled": false, "text": "", "tone": "info", "linkLabel": "", "linkTo": ""}'::jsonb, TRUE),
('registration', '{
  "open": true,
  "schoolTypes": ["Pre-school", "Primary", "Middle", "Secondary", "Higher secondary", "College", "Academy", "Madrasa", "Other"],
  "documentTypes": ["Registration certificate", "Affiliation or board letter", "Owner CNIC or ID", "Tax or NTN certificate", "Utility bill or address proof", "Other"],
  "minimumDocuments": 1
}'::jsonb, TRUE),
('monitoring', '{"ramWarnPercent": 80, "cpuWarnPercent": 85, "diskWarnPercent": 85, "nearLimitPercent": 80}'::jsonb, FALSE);

-- ---------------------------------------------------------------------
-- 3. Backfill existing schools
-- ---------------------------------------------------------------------
-- Schools that already use the ERP stay fully usable: they are verified.
UPDATE tenants
SET verification_status = CASE WHEN status = 'SUSPENDED' THEN 'SUSPENDED' ELSE 'VERIFIED' END,
    verified_at         = COALESCE(verified_at, created_at),
    slug                = COALESCE(slug, LOWER(code)),
    -- Their public website already shows their branches; that stays as it is.
    -- Schools created from now on start with this off and can opt in.
    public_branches_visible = TRUE;

-- One existing branch per school becomes its main branch (name unchanged).
WITH pick AS (
    SELECT DISTINCT ON (tenant_id) id
    FROM campuses
    ORDER BY tenant_id, (UPPER(code) = 'MAIN') DESC, (LOWER(name) LIKE 'main%') DESC, created_at, id
)
UPDATE campuses c
SET is_main = TRUE
FROM pick
WHERE c.id = pick.id;

-- Schools that never had a branch get exactly one: "Main Campus".
INSERT INTO campuses (tenant_id, name, code, is_main, address, city, phone, email)
SELECT t.id, 'Main Campus', 'MAIN', TRUE, t.address_line, t.city, t.phone, t.email
FROM tenants t
WHERE NOT EXISTS (SELECT 1 FROM campuses c WHERE c.tenant_id = t.id);

-- Branch backfill. updated_at triggers are paused where permitted so the
-- backfill does not make every historical row look freshly edited.
DO $$
DECLARE
    t TEXT;
    paused TEXT[] := ARRAY[]::TEXT[];
    tables TEXT[] := ARRAY[
        'school_classes', 'students', 'sections', 'class_subjects', 'teacher_assignments',
        'timetable_slots', 'fee_structures', 'homework', 'announcements', 'student_attendance',
        'exam_results', 'fee_challans', 'student_fee_discounts', 'homework_submissions',
        'online_exam_attempts', 'teacher_attendance', 'salary_deductions', 'staff_profiles',
        'staff_salaries'];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %I DISABLE TRIGGER USER', t);
            paused := paused || t;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE NOTICE 'updated_at trigger on % could not be paused; rows will show a new updated_at', t;
        END;
    END LOOP;

    -- Classes and students without a branch join the school's main branch.
    UPDATE school_classes k SET campus_id = m.id
    FROM campuses m WHERE m.tenant_id = k.tenant_id AND m.is_main AND k.campus_id IS NULL;

    UPDATE students s SET campus_id = k.campus_id
    FROM school_classes k WHERE k.id = s.class_id AND s.campus_id IS NULL AND k.campus_id IS NOT NULL;

    UPDATE students s SET campus_id = m.id
    FROM campuses m WHERE m.tenant_id = s.tenant_id AND m.is_main AND s.campus_id IS NULL;

    -- Rows that hang off a class.
    UPDATE sections x            SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE class_subjects x      SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE teacher_assignments x SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE timetable_slots x     SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE fee_structures x      SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE homework x            SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;
    UPDATE announcements x       SET campus_id = k.campus_id FROM school_classes k WHERE k.id = x.class_id AND x.campus_id IS NULL;

    -- Rows that hang off a student.
    UPDATE student_attendance x    SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;
    UPDATE exam_results x          SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;
    UPDATE fee_challans x          SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;
    UPDATE student_fee_discounts x SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;
    UPDATE homework_submissions x  SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;
    UPDATE online_exam_attempts x  SET campus_id = s.campus_id FROM students s WHERE s.id = x.student_id AND x.campus_id IS NULL;

    -- Rows that hang off a staff account.
    UPDATE teacher_attendance x SET campus_id = u.campus_id FROM users u WHERE u.id = x.teacher_user_id AND x.campus_id IS NULL AND u.campus_id IS NOT NULL;
    UPDATE salary_deductions x  SET campus_id = u.campus_id FROM users u WHERE u.id = x.teacher_user_id AND x.campus_id IS NULL AND u.campus_id IS NOT NULL;
    UPDATE staff_profiles x     SET campus_id = u.campus_id FROM users u WHERE u.id = x.user_id         AND x.campus_id IS NULL AND u.campus_id IS NOT NULL;
    UPDATE staff_salaries x     SET campus_id = u.campus_id FROM users u WHERE u.id = x.staff_user_id   AND x.campus_id IS NULL AND u.campus_id IS NOT NULL;

    FOREACH t IN ARRAY paused LOOP
        EXECUTE format('ALTER TABLE %I ENABLE TRIGGER USER', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 4. Role mapping (existing ERP role -> SaaS role)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION saas_role_for(p_erp_role TEXT, p_has_branch BOOLEAN)
RETURNS TEXT LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_erp_role
        WHEN 'SCHOOL_ADMIN'    THEN CASE WHEN p_has_branch THEN 'BRANCH_ADMIN' ELSE 'SCHOOL_ADMIN' END
        WHEN 'PRINCIPAL'       THEN 'SCHOOL_ADMIN'
        WHEN 'ACCOUNT_OFFICER' THEN 'STAFF'
        WHEN 'TEACHER'         THEN 'TEACHER'
        WHEN 'PARENT'          THEN 'PARENT'
        WHEN 'STUDENT'         THEN 'STUDENT'
    END;
$$;

-- Every existing school user becomes a member of their school ...
INSERT INTO user_school_memberships (user_id, tenant_id, role, erp_role, status, is_home, created_at)
SELECT u.id, u.tenant_id, saas_role_for(u.role, u.campus_id IS NOT NULL), u.role, 'ACTIVE', TRUE, u.created_at
FROM users u
WHERE u.tenant_id IS NOT NULL AND u.role <> 'ERP_OWNER'
ON CONFLICT (user_id, tenant_id) DO NOTHING;

-- ... and of the branch they were already assigned to.
INSERT INTO user_branch_memberships (user_id, tenant_id, branch_id, status, is_home, created_at)
SELECT u.id, u.tenant_id, u.campus_id, 'ACTIVE', TRUE, u.created_at
FROM users u
JOIN campuses c ON c.id = u.campus_id AND c.tenant_id = u.tenant_id
WHERE u.tenant_id IS NOT NULL AND u.role <> 'ERP_OWNER'
ON CONFLICT (user_id, branch_id) DO NOTHING;

-- The longest-standing active school admin becomes the School Owner.
WITH pick AS (
    SELECT DISTINCT ON (u.tenant_id) u.id, u.tenant_id
    FROM users u
    WHERE u.role = 'SCHOOL_ADMIN' AND u.status <> 'INACTIVE' AND u.tenant_id IS NOT NULL
    ORDER BY u.tenant_id, u.created_at, u.id
)
UPDATE user_school_memberships m
SET role = 'SCHOOL_OWNER'
FROM pick
WHERE m.user_id = pick.id AND m.tenant_id = pick.tenant_id;

UPDATE tenants t
SET owner_user_id = m.user_id
FROM user_school_memberships m
WHERE m.tenant_id = t.id AND m.role = 'SCHOOL_OWNER' AND m.status = 'ACTIVE' AND t.owner_user_id IS NULL;

-- A school admin who was already tied to one branch is shown as that branch's
-- admin (the longest-standing one when a branch has several). The School Owner
-- is never listed as a branch admin: the owner manages every branch anyway.
WITH pick AS (
    SELECT DISTINCT ON (u.campus_id) u.id, u.campus_id
    FROM users u
    JOIN user_school_memberships m ON m.user_id = u.id AND m.tenant_id = u.tenant_id AND m.role = 'BRANCH_ADMIN'
    WHERE u.role = 'SCHOOL_ADMIN' AND u.status <> 'INACTIVE' AND u.campus_id IS NOT NULL
    ORDER BY u.campus_id, u.created_at, u.id
)
UPDATE campuses c
SET branch_admin_user_id = pick.id
FROM pick
WHERE c.id = pick.campus_id AND c.branch_admin_user_id IS NULL;

-- Existing schools keep generous limits: they go on the Custom plan, so the
-- new Free-plan defaults can never lock an existing school out.
INSERT INTO school_subscriptions (tenant_id, plan_id, status, notes)
SELECT t.id, p.id, 'ACTIVE', 'Assigned automatically when the platform became multi-tenant (V30).'
FROM tenants t
CROSS JOIN subscription_plans p
WHERE p.code = 'CUSTOM'
  AND NOT EXISTS (SELECT 1 FROM school_subscriptions s WHERE s.tenant_id = t.id AND s.status = 'ACTIVE');

-- Sizes of files uploaded before sizes were always recorded are filled in by
-- the application on its first usage refresh (it has to read the disk).

-- ---------------------------------------------------------------------
-- 5. Effective limits: per-school override > current plan > default plan
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION saas_effective_limits(p_tenant UUID)
RETURNS TABLE (
    plan_id          UUID,
    plan_code        VARCHAR,
    plan_name        VARCHAR,
    max_active_users INTEGER,
    storage_limit_mb INTEGER,
    ram_budget_mb    INTEGER,
    max_branches     INTEGER,
    resource_policy  VARCHAR,
    expires_at       TIMESTAMPTZ,
    plan_expired     BOOLEAN,
    has_override     BOOLEAN
) LANGUAGE sql STABLE AS $$
    WITH sub AS (
        SELECT s.plan_id, s.expires_at
        FROM school_subscriptions s
        WHERE s.tenant_id = p_tenant AND s.status = 'ACTIVE'
        LIMIT 1
    ),
    chosen AS (
        SELECT p.*
        FROM subscription_plans p
        WHERE p.id = COALESCE(
            (SELECT sub.plan_id FROM sub WHERE sub.expires_at IS NULL OR sub.expires_at > NOW()),
            (SELECT d.id FROM subscription_plans d WHERE d.is_default LIMIT 1))
    )
    SELECT c.id,
           c.code,
           c.name,
           COALESCE(o.max_active_users, c.max_active_users),
           COALESCE(o.storage_limit_mb, c.storage_limit_mb),
           COALESCE(o.ram_budget_mb, c.ram_budget_mb),
           COALESCE(o.max_branches, c.max_branches),
           c.resource_policy,
           (SELECT sub.expires_at FROM sub),
           COALESCE((SELECT sub.expires_at <= NOW() FROM sub), FALSE),
           (o.tenant_id IS NOT NULL)
    FROM chosen c
    LEFT JOIN tenant_resource_limits o ON o.tenant_id = p_tenant;
$$;

-- Active users of a school = active memberships whose account is not deactivated.
CREATE OR REPLACE FUNCTION saas_active_user_count(p_tenant UUID)
RETURNS INTEGER LANGUAGE sql STABLE AS $$
    SELECT COUNT(*)::INTEGER
    FROM user_school_memberships m
    JOIN users u ON u.id = m.user_id
    WHERE m.tenant_id = p_tenant AND m.status = 'ACTIVE' AND u.status <> 'INACTIVE';
$$;

-- Branches that count against the plan = everything not archived.
CREATE OR REPLACE FUNCTION saas_branch_count(p_tenant UUID)
RETURNS INTEGER LANGUAGE sql STABLE AS $$
    SELECT COUNT(*)::INTEGER FROM campuses c WHERE c.tenant_id = p_tenant AND c.status <> 'ARCHIVED';
$$;

-- Raises SQLSTATE DZ001 when the school has no free seat. Serialised per
-- school so two concurrent requests cannot both take the last seat.
CREATE OR REPLACE FUNCTION saas_assert_user_capacity(p_tenant UUID, p_seats INTEGER DEFAULT 1)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    lim  RECORD;
    used INTEGER;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(p_tenant::TEXT, 7301));
    SELECT * INTO lim FROM saas_effective_limits(p_tenant);
    IF NOT FOUND OR lim.max_active_users IS NULL OR lim.resource_policy = 'WARN_ONLY' THEN
        RETURN;
    END IF;
    used := saas_active_user_count(p_tenant);
    IF used + p_seats > lim.max_active_users THEN
        RAISE EXCEPTION 'USER_LIMIT_REACHED: % of % active users are in use', used, lim.max_active_users
            USING ERRCODE = 'DZ001';
    END IF;
END;
$$;

-- Raises SQLSTATE DZ002 when the school is at its branch limit.
CREATE OR REPLACE FUNCTION saas_assert_branch_capacity(p_tenant UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    lim  RECORD;
    used INTEGER;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(p_tenant::TEXT, 7302));
    SELECT * INTO lim FROM saas_effective_limits(p_tenant);
    IF NOT FOUND OR lim.max_branches IS NULL OR lim.resource_policy = 'WARN_ONLY' THEN
        RETURN;
    END IF;
    used := saas_branch_count(p_tenant);
    IF used + 1 > lim.max_branches THEN
        RAISE EXCEPTION 'BRANCH_LIMIT_REACHED: % of % branches are in use', used, lim.max_branches
            USING ERRCODE = 'DZ002';
    END IF;
END;
$$;

-- ---------------------------------------------------------------------
-- 6. Guards (triggers). They cover every code path, including the ERP
--    modules that predate the SaaS layer.
-- ---------------------------------------------------------------------

-- 6a. A new school always has a plan: the platform default.
CREATE OR REPLACE FUNCTION saas_tenant_default_plan() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO school_subscriptions (tenant_id, plan_id, status, notes)
    SELECT NEW.id, p.id, 'ACTIVE', 'Default plan'
    FROM subscription_plans p
    WHERE p.is_default
      AND NOT EXISTS (SELECT 1 FROM school_subscriptions s WHERE s.tenant_id = NEW.id AND s.status = 'ACTIVE');
    IF NEW.slug IS NULL THEN
        UPDATE tenants SET slug = LOWER(NEW.code) WHERE id = NEW.id AND slug IS NULL;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_tenants_default_plan
    AFTER INSERT ON tenants
    FOR EACH ROW EXECUTE PROCEDURE saas_tenant_default_plan();

-- 6b. Seat limit when a membership is created or re-activated.
CREATE OR REPLACE FUNCTION saas_membership_capacity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_status TEXT;
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status = 'ACTIVE' AND OLD.tenant_id = NEW.tenant_id THEN
        RETURN NEW;
    END IF;
    SELECT u.status INTO account_status FROM users u WHERE u.id = NEW.user_id;
    IF account_status IS DISTINCT FROM 'INACTIVE' THEN
        PERFORM saas_assert_user_capacity(NEW.tenant_id, 1);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_usm_capacity
    BEFORE INSERT OR UPDATE OF status, tenant_id ON user_school_memberships
    FOR EACH ROW EXECUTE PROCEDURE saas_membership_capacity();

-- 6c. Seat limit when a deactivated account is switched back on.
CREATE OR REPLACE FUNCTION saas_user_reactivation_capacity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    m RECORD;
BEGIN
    IF OLD.status = 'INACTIVE' AND NEW.status <> 'INACTIVE' THEN
        FOR m IN SELECT tenant_id FROM user_school_memberships
                 WHERE user_id = NEW.id AND status = 'ACTIVE' ORDER BY tenant_id LOOP
            PERFORM saas_assert_user_capacity(m.tenant_id, 1);
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_users_reactivation_capacity
    BEFORE UPDATE OF status ON users
    FOR EACH ROW EXECUTE PROCEDURE saas_user_reactivation_capacity();

-- 6d. Keep memberships in step with the columns the ERP modules still write
--     (users.tenant_id / role / campus_id), so no ERP code path can create a
--     user that the central login does not know about.
CREATE OR REPLACE FUNCTION saas_user_membership_sync() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_role TEXT;
BEGIN
    IF NEW.tenant_id IS NULL OR NEW.role = 'ERP_OWNER' THEN
        RETURN NULL;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- The first school admin of a school is its owner (registration, first-time
        -- setup, a school created by the platform owner, and the demo seed all
        -- create the admin first). Serialised per school so two cannot both win.
        v_role := saas_role_for(NEW.role, NEW.campus_id IS NOT NULL);
        IF NEW.role = 'SCHOOL_ADMIN' THEN
            PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::TEXT, 7304));
            IF NOT EXISTS (SELECT 1 FROM user_school_memberships o
                           WHERE o.tenant_id = NEW.tenant_id AND o.role = 'SCHOOL_OWNER' AND o.status = 'ACTIVE') THEN
                v_role := 'SCHOOL_OWNER';
                UPDATE tenants SET owner_user_id = NEW.id WHERE id = NEW.tenant_id AND owner_user_id IS NULL;
            END IF;
        END IF;
        INSERT INTO user_school_memberships (user_id, tenant_id, role, erp_role, status, is_home, created_by)
        VALUES (NEW.id, NEW.tenant_id, v_role, NEW.role, 'ACTIVE', TRUE, NEW.created_by)
        ON CONFLICT (user_id, tenant_id) DO NOTHING;
    ELSIF NEW.role IS DISTINCT FROM OLD.role OR NEW.campus_id IS DISTINCT FROM OLD.campus_id THEN
        UPDATE user_school_memberships m
        SET erp_role = NEW.role,
            role = CASE
                       -- An owner stays an owner; an explicitly appointed branch
                       -- admin stays one while they still administer.
                       WHEN m.role = 'SCHOOL_OWNER' AND NEW.role = 'SCHOOL_ADMIN' THEN m.role
                       ELSE saas_role_for(NEW.role, NEW.campus_id IS NOT NULL)
                   END
        WHERE m.user_id = NEW.id AND m.tenant_id = NEW.tenant_id AND m.is_home;
    END IF;

    IF TG_OP = 'UPDATE' AND NEW.campus_id IS DISTINCT FROM OLD.campus_id AND OLD.campus_id IS NOT NULL THEN
        DELETE FROM user_branch_memberships
        WHERE user_id = NEW.id AND branch_id = OLD.campus_id AND is_home;
    END IF;

    IF NEW.campus_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.campus_id IS DISTINCT FROM OLD.campus_id) THEN
        INSERT INTO user_branch_memberships (user_id, tenant_id, branch_id, status, is_home, created_by)
        SELECT NEW.id, NEW.tenant_id, c.id, 'ACTIVE', TRUE, NEW.updated_by
        FROM campuses c
        WHERE c.id = NEW.campus_id AND c.tenant_id = NEW.tenant_id
        ON CONFLICT (user_id, branch_id) DO UPDATE SET is_home = TRUE, status = 'ACTIVE';

        -- A school admin created for one branch is that branch's admin when it has none.
        IF NEW.role = 'SCHOOL_ADMIN' AND EXISTS (
                SELECT 1 FROM user_school_memberships m
                WHERE m.user_id = NEW.id AND m.tenant_id = NEW.tenant_id AND m.role = 'BRANCH_ADMIN') THEN
            UPDATE campuses SET branch_admin_user_id = NEW.id
            WHERE id = NEW.campus_id AND tenant_id = NEW.tenant_id AND branch_admin_user_id IS NULL;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_users_membership_sync
    AFTER INSERT OR UPDATE OF role, campus_id ON users
    FOR EACH ROW EXECUTE PROCEDURE saas_user_membership_sync();

-- 6e. Branch limit when a branch is created or restored from the archive.
CREATE OR REPLACE FUNCTION saas_branch_capacity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'ARCHIVED' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'ARCHIVED' THEN
        RETURN NEW;
    END IF;
    PERFORM saas_assert_branch_capacity(NEW.tenant_id);
    -- A school's first branch is its main branch, whichever code path creates it.
    IF TG_OP = 'INSERT' AND NOT NEW.is_main
       AND NOT EXISTS (SELECT 1 FROM campuses c WHERE c.tenant_id = NEW.tenant_id AND c.is_main) THEN
        NEW.is_main := TRUE;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_campuses_capacity
    BEFORE INSERT OR UPDATE OF status ON campuses
    FOR EACH ROW EXECUTE PROCEDURE saas_branch_capacity();

-- 6f. Stamp the branch on new ERP rows (the ERP modules do not set it).
CREATE OR REPLACE FUNCTION saas_fill_campus_id() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    ref UUID;
BEGIN
    IF NEW.campus_id IS NOT NULL THEN
        RETURN NEW;
    END IF;
    EXECUTE format('SELECT ($1).%I', TG_ARGV[1]) USING NEW INTO ref;
    IF ref IS NULL THEN
        RETURN NEW;
    END IF;
    IF TG_ARGV[0] = 'student' THEN
        SELECT s.campus_id INTO NEW.campus_id FROM students s WHERE s.id = ref;
    ELSIF TG_ARGV[0] = 'class' THEN
        SELECT k.campus_id INTO NEW.campus_id FROM school_classes k WHERE k.id = ref;
    ELSIF TG_ARGV[0] = 'user' THEN
        SELECT u.campus_id INTO NEW.campus_id FROM users u WHERE u.id = ref;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_sections_campus              BEFORE INSERT ON sections              FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_class_subjects_campus        BEFORE INSERT ON class_subjects        FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_teacher_assignments_campus   BEFORE INSERT ON teacher_assignments   FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_timetable_slots_campus       BEFORE INSERT ON timetable_slots       FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_fee_structures_campus        BEFORE INSERT ON fee_structures        FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_homework_campus              BEFORE INSERT ON homework              FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_announcements_campus         BEFORE INSERT ON announcements         FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('class', 'class_id');
CREATE TRIGGER trg_student_attendance_campus    BEFORE INSERT ON student_attendance    FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_exam_results_campus          BEFORE INSERT ON exam_results          FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_fee_challans_campus          BEFORE INSERT ON fee_challans          FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_student_fee_discounts_campus BEFORE INSERT ON student_fee_discounts FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_homework_submissions_campus  BEFORE INSERT ON homework_submissions  FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_online_exam_attempts_campus  BEFORE INSERT ON online_exam_attempts  FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('student', 'student_id');
CREATE TRIGGER trg_teacher_attendance_campus    BEFORE INSERT ON teacher_attendance    FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('user', 'teacher_user_id');
CREATE TRIGGER trg_salary_deductions_campus     BEFORE INSERT ON salary_deductions     FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('user', 'teacher_user_id');
CREATE TRIGGER trg_staff_profiles_campus        BEFORE INSERT ON staff_profiles        FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('user', 'user_id');
CREATE TRIGGER trg_staff_salaries_campus        BEFORE INSERT ON staff_salaries        FOR EACH ROW EXECUTE PROCEDURE saas_fill_campus_id('user', 'staff_user_id');

-- ---------------------------------------------------------------------
-- 7. First usage snapshot
-- ---------------------------------------------------------------------
INSERT INTO tenant_usage (tenant_id, active_users, total_users, branches, students, storage_bytes)
SELECT t.id,
       saas_active_user_count(t.id),
       (SELECT COUNT(*) FROM user_school_memberships m WHERE m.tenant_id = t.id),
       saas_branch_count(t.id),
       (SELECT COUNT(*) FROM students s WHERE s.tenant_id = t.id),
       COALESCE((SELECT SUM(f.size_bytes) FROM stored_files f WHERE f.tenant_id = t.id), 0)
FROM tenants t
ON CONFLICT (tenant_id) DO NOTHING;
