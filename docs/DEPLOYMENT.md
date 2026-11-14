# Deployment guide

DEVZARD eSchool is two parts on one server:

| Part | What it is | How it runs |
| --- | --- | --- |
| API | Spring Boot 3.4, Java 21, PostgreSQL | One `java -jar` process on `127.0.0.1:8080` |
| Web | React + Vite static files (`web/dist`) | Served by Nginx, which also forwards `/api` to the API |

The web app only ever calls relative `/api/...` addresses, so the website and the API
must be reachable on the same domain. Nginx does that.

> **Read first.** The database migration (`V29`, `V30`) and the old application build
> were tested together: the previous build starts and signs in against a migrated
> database. That is what makes the rollback in this guide safe. Still take the backup
> in step 1 every time.

---

## 1. Before you start: back up

```bash
# Database
pg_dump --format=custom --file=/var/backups/erp_$(date +%F_%H%M).dump "$DATABASE_NAME"

# Uploaded files (the folder UPLOAD_DIR points to)
tar -czf /var/backups/erp_uploads_$(date +%F_%H%M).tar.gz -C /var/lib/devzard uploads

# The build that is running now, so you can put it back
cp /opt/devzard/erp-school.jar /opt/devzard/erp-school.previous.jar
cp -a /var/www/devzard /var/www/devzard.previous
```

## 2. Server requirements

- Linux with Java 21 (JRE is enough to run; a JDK and Maven 3.9+ are needed to build)
- PostgreSQL 14 or newer (tested on 16)
- Node.js 20 or newer (build machine only)
- Nginx

One 8 GB server is the intended size. Suggested split: 3 GB Java heap, about 2 GB for
PostgreSQL, the rest for the operating system and file cache. Plan "RAM" figures shown
in the product are planning budgets, not reservations: all schools share this one
process. Users, branches and storage are the limits that are actually enforced.

## 3. Environment variables

Set these for the API process (for example in `/etc/devzard/erp.env`, mode `600`).

### Required in production

| Variable | Example | Notes |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://127.0.0.1:5432/erp_school` | |
| `SPRING_DATASOURCE_USERNAME` | `erp` | |
| `SPRING_DATASOURCE_PASSWORD` | *(secret)* | |
| `JWT_SECRET` | 64+ random characters | **Must be changed.** The default in `application.yml` is public. Changing it signs everyone out once. |
| `UPLOAD_DIR` | `/var/lib/devzard/uploads` | Must be writable by the API user and **outside** the web root. Back it up. |
| `CORS_ORIGINS` | `https://eschool.example.com` | Your real domain. The default `*` is for development. |
| `SEED_DEMO_DATA` | `false` | `true` creates the demo school and well-known demo passwords. Never leave it on in production. |
| `ERP_OWNER_PASSWORD` | *(secret, 10+ characters)* | Used once, to create the Platform Owner when none exists. See "First Platform Owner". |

### Optional

| Variable | Default | Notes |
| --- | --- | --- |
| `PORT` | `8080` | |
| `DB_POOL_SIZE` | `5` | 10 is reasonable for one 8 GB server |
| `ERP_OWNER_USERNAME` | `erp.owner` | Platform Owner sign-in name |
| `ERP_OWNER_EMAIL` | `owner@erpschool.local` | Platform Owner email (can also be used to sign in) |
| `JWT_ACCESS_TTL` | `900` | Seconds |
| `JWT_REFRESH_TTL` | `604800` | Seconds |
| `ALLOW_FIRST_TIME_SETUP` | `false` | Old "create a school without signing in" screen. Keep `false`: schools now register and are verified. |
| `DEFAULT_PUBLIC_SCHOOL` | *(empty)* | A school code. Only set this for a single-school install that wants `/api/v1/public/school` to answer without a code. Leave empty on a multi-school platform. |
| `RATE_LIMIT_ENABLED` | `true` | |
| `RATE_LIMIT_LOGIN_PER_MINUTE` | `20` | Per IP address |
| `RATE_LIMIT_REGISTER_PER_HOUR` | `10` | Per IP address |
| `RATE_LIMIT_PUBLIC_WRITE_PER_MINUTE` | `30` | Per IP address |
| `RATE_LIMIT_PUBLIC_READ_PER_MINUTE` | `240` | Per IP address |
| `DEMO_USER_PASSWORD` | `ChangeMe@123` | Only used when `SEED_DEMO_DATA=true` |

Upload sizes are fixed in `application.yml`: 25 MB per file, 60 MB per request.
Verification documents are limited to 10 MB each by the application.

### First Platform Owner

- **Existing installation:** nothing to do. The existing `erp.owner` account is the
  Platform Owner and keeps its password.
- **New installation with `SEED_DEMO_DATA=false`:** set `ERP_OWNER_PASSWORD` (10 or more
  characters, not the demo password) before the first start. The account is created
  once. If the variable is missing the API still starts and logs
  `No Platform Owner account exists...`; set it and restart.
- After the first sign-in, change the password from the user menu and remove
  `ERP_OWNER_PASSWORD` from the environment file.

## 4. Build

```bash
# API
mvn -DskipTests package          # produces target/erp-school-0.2.0.jar
mvn test                         # 70 unit tests

# Web
cd web
npm ci
npm run build                    # produces web/dist
```

## 5. Install

```bash
sudo useradd --system --home /opt/devzard --shell /usr/sbin/nologin devzard
sudo mkdir -p /opt/devzard /var/lib/devzard/uploads /var/www/devzard /etc/devzard
sudo cp target/erp-school-0.2.0.jar /opt/devzard/erp-school.jar
sudo rsync -a --delete web/dist/ /var/www/devzard/
sudo chown -R devzard:devzard /opt/devzard /var/lib/devzard
sudo chmod 600 /etc/devzard/erp.env
```

`/etc/systemd/system/devzard-erp.service`:

```ini
[Unit]
Description=DEVZARD eSchool API
After=network.target postgresql.service

[Service]
User=devzard
WorkingDirectory=/opt/devzard
EnvironmentFile=/etc/devzard/erp.env
ExecStart=/usr/bin/java -Xms1g -Xmx3g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -jar /opt/devzard/erp-school.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now devzard-erp
journalctl -u devzard-erp -f      # wait for "Started ErpSchoolApplication"
```

On start the API applies database migrations automatically (Flyway). On an existing
database you should see `Successfully applied 2 migrations ... now at version v30`.

## 6. Nginx

```nginx
# Extra connection-level limit in front of the application's own rate limiter.
limit_req_zone $binary_remote_addr zone=devzard_auth:10m rate=30r/m;

server {
    listen 80;
    server_name eschool.example.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name eschool.example.com;

    ssl_certificate     /etc/letsencrypt/live/eschool.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/eschool.example.com/privkey.pem;

    # Must be at least the API's request limit (60 MB) so the API can answer
    # with its own clear "file too large" message.
    client_max_body_size 64m;

    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    add_header Strict-Transport-Security "max-age=31536000" always;

    root /var/www/devzard;
    index index.html;

    # API
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        # Overwrite, do not append. The API trusts the first address in this
        # header when the request comes from a local proxy; appending would let
        # a visitor fake their address and dodge the rate limits.
        proxy_set_header X-Forwarded-For   $remote_addr;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_read_timeout 120s;
        proxy_request_buffering off;
    }

    location = /api/v1/auth/login       { limit_req zone=devzard_auth burst=20 nodelay; proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-For $remote_addr; }
    location = /api/v1/public/register  { limit_req zone=devzard_auth burst=5  nodelay; proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-For $remote_addr; proxy_request_buffering off; }

    # Built assets have a hash in the file name and can be cached for a long time.
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # Single-page app: every other address returns index.html, never cached.
    location / {
        add_header Cache-Control "no-cache";
        try_files $uri $uri/ /index.html;
    }
}
```

Notes:

- Keep port 8080 closed to the internet (firewall). Only Nginx should reach it.
- Do **not** add an Nginx `location` for the uploads folder. Uploaded files are private
  and are only served through `/api/v1/files/...`, which checks who is asking.
- `X-Forwarded-Proto` matters: with it the file cookie is marked `Secure`.
- If you use Cloudflare in front of Nginx, the API reads `CF-Connecting-IP` first.

```bash
sudo nginx -t && sudo systemctl reload nginx
```

## 7. Check after deploy

```bash
curl -s https://eschool.example.com/api/v1/public/site | head -c 200     # 200, platform branding
curl -s -o /dev/null -w "%{http_code}\n" https://eschool.example.com/    # 200
```

Then in a browser:

1. `/` shows the platform home page and no school.
2. `/login` signs in an existing school user and lands in their own school.
3. Sign in as the Platform Owner and open **Server and usage** and **School verification**.
4. An existing school shows as **Verified** on the **Custom** plan, with all its users.

On a **test** copy only (the scripts create throw-away schools and need the demo school):

```bash
python3 scripts/saas_smoke_test.py   http://127.0.0.1:8080
python3 scripts/saas_extra_checks.py http://127.0.0.1:8080
```

## 8. What the migration does to existing data

`V29` and `V30` only add. Nothing is dropped, renamed or deleted.

- New tables: plans, subscriptions, per-school limits, applications, verification
  documents, platform settings, school and branch memberships, usage, branch passwords.
- New columns on `tenants` and `campuses` (all nullable or with a default).
- Every existing school becomes **Verified** on the **Custom** plan (5,000 users,
  50 branches, 200 GB), so no existing school can be locked out by the new Free limits.
- Every existing user gets a membership in their school and branch.
- The longest-standing school admin of each school becomes its **School Owner**.
  A school admin already tied to one branch becomes that branch's **Branch Admin**.
  Principals map to School Admin, account officers to Staff.
- Existing schools keep their branch pages public (`public_branches_visible = true`).

Checked on a copy of the old database with demo seeding off: every one of the 56
existing tables had exactly the same number of rows before and after.

## 9. Rollback

Rolling back the **application** does not require rolling back the **database**. The
previous build ignores the new tables and columns and was tested signing in against a
migrated database.

```bash
sudo systemctl stop devzard-erp
sudo cp /opt/devzard/erp-school.previous.jar /opt/devzard/erp-school.jar
sudo rsync -a --delete /var/www/devzard.previous/ /var/www/devzard/
sudo systemctl start devzard-erp
```

The previous build was compiled before `V29`/`V30` existed. Flyway only complains about
"unknown applied migrations" when validation is strict; this project already starts with
a newer schema history, as tested. If your previous build refuses to start with a Flyway
validation message, start it once with `SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS=*:future`.

Things to know after an application rollback:

- Schools that registered through the new flow exist in the database. The old build has
  no verification gate, so a **pending** school's owner could sign in and use it. If any
  pending or rejected schools exist, lock them before starting the old build:

  ```sql
  UPDATE tenants SET status = 'SUSPENDED'
  WHERE verification_status IN ('PENDING', 'UNDER_REVIEW', 'REJECTED', 'SUSPENDED');
  ```

  When you later return to the new build, undo that for schools that are still waiting,
  so their owners can see the status screen again:

  ```sql
  UPDATE tenants SET status = 'ACTIVE'
  WHERE verification_status IN ('PENDING', 'UNDER_REVIEW', 'REJECTED');
  ```

- The two database guards stay active with the old build: adding a user past a school's
  limit, or a branch past its limit, is refused by the database (the old build shows it
  as a generic conflict). Existing schools are on the Custom plan, so this does not
  affect them in practice.

Only if you must remove the schema too, restore the backup from step 1:

```bash
sudo systemctl stop devzard-erp
dropdb erp_school && createdb erp_school
pg_restore --dbname=erp_school /var/backups/erp_YYYY-MM-DD_HHMM.dump
tar -xzf /var/backups/erp_uploads_YYYY-MM-DD_HHMM.tar.gz -C /var/lib/devzard
sudo systemctl start devzard-erp
```

This loses everything entered after the backup. There is no "down" migration script on
purpose: restoring the backup is the only way that is certain to be correct.

## 10. Routine operations

- **Backups:** nightly `pg_dump` and a copy of `UPLOAD_DIR`. Test a restore.
- **Logs:** `journalctl -u devzard-erp`. Sign-in failures, rate-limit hits and refused
  cross-school requests are logged without passwords or tokens.
- **Watching load:** Platform Owner → **Server and usage** shows real CPU, memory, disk,
  database size, recent requests and schools close to a limit.
- **Changing a school's plan or limits:** Platform Owner → **School verification** →
  open the school. Lowering a limit never deletes data; it only stops new users,
  branches or uploads until the school is back under the limit.
