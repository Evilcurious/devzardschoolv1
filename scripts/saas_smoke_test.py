#!/usr/bin/env python3
"""
End-to-end checks for the DEVZARD eSchool platform layer, over HTTP.

Run it against a server that has the demo data (SEED_DEMO_DATA=true):

    python3 scripts/saas_smoke_test.py http://127.0.0.1:8080

It registers throw-away schools, so use a test database, never production.
Needs only the Python standard library. Exit code 0 means every check passed.
"""
import json
import random
import string
import sys
import urllib.error
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/") + "/api/v1"
OWNER_PW = sys.argv[2] if len(sys.argv) > 2 else "Owner@12345"
DEMO_PW = sys.argv[3] if len(sys.argv) > 3 else "ChangeMe@123"
RUN = "".join(random.choice(string.ascii_lowercase) for _ in range(6))
FAKE_IP = "10.%d.%d.%d" % (random.randint(1, 250), random.randint(1, 250), random.randint(1, 250))
PDF = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"
PNG = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
                    "0000000a49444154789c6300010000050001") + b"\x0d\x0a\x2d\xb4" + bytes.fromhex("0000000049454e44ae426082")

passed, failed = 0, []


def call(method, path, token=None, body=None, headers=None, raw=False, ip=None):
    h = {"X-Forwarded-For": ip or FAKE_IP}
    data = None
    if isinstance(body, tuple):  # (content_type, bytes) for multipart
        h["Content-Type"], data = body
    elif body is not None:
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if token:
        h["Authorization"] = "Bearer " + token
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            payload, status, rh = r.read(), r.status, r.headers
    except urllib.error.HTTPError as e:
        payload, status, rh = e.read(), e.code, e.headers
    if raw:
        return status, payload, rh
    try:
        return status, json.loads(payload or b"{}"), rh
    except ValueError:
        return status, {"_raw": payload[:200].decode("latin1")}, rh


def multipart(fields, files):
    boundary = "----dz" + uuid.uuid4().hex
    out = []
    for name, value in fields:
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (boundary, name, value)).encode())
    for name, filename, ctype, content in files:
        out.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n"
                    % (boundary, name, filename, ctype)).encode() + content + b"\r\n")
    out.append(("--%s--\r\n" % boundary).encode())
    return "multipart/form-data; boundary=" + boundary, b"".join(out)


def check(name, condition, detail=""):
    global passed
    if condition:
        passed += 1
        print("  ok   " + name)
    else:
        failed.append(name)
        print("  FAIL " + name + ("   -> " + str(detail)[:300] if detail != "" else ""))


def login(username, password):
    return call("POST", "/auth/login", body={"username": username, "password": password})


def token_of(resp):
    return (resp[1].get("data") or {}).get("accessToken")


def register(tag, extra_files=None, password="Sunrise123"):
    email = "owner.%s.%s@example.test" % (tag, RUN)
    fields = [("schoolName", "Sunrise %s %s School" % (tag.upper(), RUN)), ("address", "12 Test Road"),
              ("city", "Lahore"), ("country", "Pakistan"), ("contactNumber", "+92 300 1234567"),
              ("officialEmail", "info.%s.%s@example.test" % (tag, RUN)), ("principalName", "Ayesha Khan"),
              ("schoolType", "Secondary"), ("registrationDetails", "Reg no 12345"),
              ("ownerFirstName", "Ayesha"), ("ownerLastName", "Khan"), ("ownerEmail", email),
              ("password", password), ("documentTypes", "Registration certificate")]
    files = extra_files if extra_files is not None else [("documents", "certificate.pdf", "application/pdf", PDF)]
    return email, call("POST", "/public/register", body=multipart(fields, files))


print("Target:", BASE, " run:", RUN)

# ---------------------------------------------------------------- existing ERP keeps working
print("\n1. Existing school and accounts still work")
po = login("erp.owner", OWNER_PW)
PO = token_of(po)
check("platform owner signs in", po[0] == 200 and PO, po[1])
check("platform owner context is PLATFORM_OWNER", (po[1]["data"].get("context") or {}).get("role") == "PLATFORM_OWNER")

gvs = login("GVS-ADM-0001", DEMO_PW)
GVS = token_of(gvs)
check("legacy school admin signs in through the central login", gvs[0] == 200 and GVS, gvs[1])
gctx = gvs[1]["data"].get("context") or {}
check("legacy school admin became School Owner of a verified school",
      gctx.get("role") == "SCHOOL_OWNER" and gctx.get("verificationStatus") == "VERIFIED", gctx)
GVS_TENANT = gctx.get("tenantId")
s, students, _ = call("GET", "/students?size=5", GVS)
check("legacy ERP endpoint /students still returns data", s == 200 and students.get("data"), students)
total_students = ((students.get("data") or {}).get("totalElements")
                  if isinstance(students.get("data"), dict) else None)
s, me, _ = call("GET", "/auth/me", GVS)
check("/auth/me works", s == 200 and me["data"]["username"] == "GVS-ADM-0001", me)
for user in ("GVS-TCH-0001", "GVS-PAR-0001", "GVS-PRN-0001", "GVS-ACC-0001"):
    r = login(user, DEMO_PW)
    check("%s signs in" % user, r[0] == 200 and (token_of(r) or r[1]["data"].get("selectionRequired")), r[1])
s, old, _ = call("POST", "/auth/refresh", body={"refreshToken": gvs[1]["data"]["refreshToken"]})
check("refresh token rotates and keeps the context",
      s == 200 and (old["data"].get("context") or {}).get("role") == "SCHOOL_OWNER", old)
GVS = old["data"]["accessToken"]

# ---------------------------------------------------------------- public surface
print("\n2. Nothing about schools is public")
s, site, _ = call("GET", "/public/site")
blob = json.dumps(site)
check("public site document loads", s == 200 and site["data"].get("branding"), site)
check("public site has plans, with Free limits 120 users / 1 branch",
      any(p["code"] == "FREE" and p["maxActiveUsers"] == 120 and p["maxBranches"] == 1 for p in site["data"]["plans"]))
check("public site exposes no school name", "Green Valley" not in blob and "GVS" not in blob)
check("private setting 'monitoring' is not public", "monitoring" not in site["data"])
s, r, _ = call("GET", "/public/school")
check("no default school is served to anonymous visitors", s == 404, (s, r))
s, a, _ = call("GET", "/public/school?code=NOPE99")
s2, b, _ = call("GET", "/public/school?code=ZZZZ")
check("unknown school codes all give the same 404", s == 404 and s2 == 404 and a.get("message") == b.get("message"))
for path in ("/campuses", "/branches", "/students", "/platform/schools", "/auth/contexts", "/school/overview"):
    s, r, _ = call("GET", path)
    check("anonymous GET %s is refused" % path, s in (401, 403), s)
s, r, _ = call("GET", "/setup/status")
check("first-time setup is off", s == 200 and r["data"]["needsSetup"] is False, r)
s, r, _ = call("POST", "/setup", body={"schoolName": "Hack School", "branches": ["a", "b", "c"]})
check("anonymous setup cannot create a school", s in (400, 403), (s, r))

# ---------------------------------------------------------------- registration and verification
print("\n3. Registration, verification gate, approval")
s, bad, _ = call("POST", "/public/register", body=multipart([("schoolName", "X")], []))
check("registration validates fields", s == 400 and bad.get("errors"), bad)
_, r = register("exe", [("documents", "evil.pdf", "application/pdf", b"MZ\x90\x00 this is not a pdf")])
check("a file that is not really a PDF/image is rejected", r[0] == 400 and r[1].get("errorCode") == "FILE_TYPE", r[1])
_, r = register("nodoc", [])
check("registration without a document is rejected", r[0] == 400, r[1])
_, r = register("weak", password="short")
check("weak password is rejected", r[0] == 400, r[1])

A_EMAIL, ra = register("a")
check("school A registers", ra[0] == 201 and ra[1]["data"]["verificationStatus"] == "PENDING", ra[1])
_, dup = register("a")
check("the same login email cannot register twice", dup[0] == 409, dup[1])
a = login(A_EMAIL, "Sunrise123")
A = token_of(a)
actx = (a[1].get("data") or {}).get("context") or {}
check("school A owner signs in and is told the school is PENDING",
      a[0] == 200 and A and actx.get("role") == "SCHOOL_OWNER" and actx.get("verificationStatus") == "PENDING", a[1])
A_TENANT = actx.get("tenantId")
for path in ("/students", "/users", "/campuses", "/branches"):
    s, r, _ = call("GET", path, A)
    check("unverified school cannot use %s" % path, s == 403 and r.get("errorCode") == "SCHOOL_NOT_VERIFIED", (s, r))
s, r, _ = call("POST", "/users", A, {"firstName": "T", "lastName": "One", "role": "TEACHER"})
check("unverified school cannot create users", s == 403, (s, r))
s, app, _ = call("GET", "/school/application", A)
check("owner can see the application and its documents", s == 200 and len(app["data"]["documents"]) == 1, app)
DOC_ID = app["data"]["documents"][0]["id"]
s, body, h = call("GET", "/school/application/documents/" + DOC_ID, A, raw=True)
check("owner can download their own document", s == 200 and body.startswith(b"%PDF"), s)
s, body, _ = call("GET", "/school/application/documents/" + DOC_ID, GVS, raw=True)
check("another school cannot download that document", s in (403, 404), s)
s, body, _ = call("GET", "/school/application/documents/" + DOC_ID, raw=True)
check("anonymous cannot download that document", s in (401, 403), s)

s, lst, _ = call("GET", "/platform/schools?status=PENDING", PO)
check("platform owner sees school A as pending", s == 200 and any(x["id"] == A_TENANT for x in lst["data"]), lst)
s, r, _ = call("GET", "/platform/schools", A)
check("a school owner cannot call the platform API", s == 403, s)
s, r, _ = call("GET", "/platform/schools", GVS)
check("a verified school owner cannot call the platform API either", s == 403, s)
s, body, _ = call("GET", "/platform/schools/%s/documents/%s" % (A_TENANT, DOC_ID), PO, raw=True)
check("platform owner can open the document", s == 200 and body.startswith(b"%PDF"), s)
s, r, _ = call("POST", "/platform/schools/%s/review" % A_TENANT, PO, {"action": "REJECT"})
check("rejecting needs a reason", s == 400, (s, r))
s, r, _ = call("POST", "/platform/schools/%s/review" % A_TENANT, PO,
               {"action": "REQUEST_DOCUMENTS", "note": "Please add the board affiliation letter."})
check("platform owner requests more documents", s == 200 and r["data"]["verificationStatus"] == "UNDER_REVIEW", r)
s, r, _ = call("POST", "/school/application/documents", A,
               multipart([("documentType", "Affiliation or board letter")], [("file", "letter.png", "image/png", PNG)]))
check("owner uploads the requested document", s == 200 and len(r["data"]["documents"]) == 2, r)
s, r, _ = call("POST", "/platform/schools/%s/review" % A_TENANT, PO, {"action": "APPROVE", "note": "Checked."})
check("platform owner approves school A", s == 200 and r["data"]["verificationStatus"] == "VERIFIED", r)
check("approval created exactly one branch, the main one",
      len(r["data"]["branches"]) == 1 and r["data"]["branches"][0]["main"] is True, r["data"].get("branches"))
check("school A is on the Free plan", r["data"]["resources"]["limits"]["planCode"] == "FREE", r["data"]["resources"])
call("GET", "/students", A)  # warm
import time
time.sleep(0.2)
a = login(A_EMAIL, "Sunrise123")
A = token_of(a)
s, r, _ = call("GET", "/students", A)
check("after approval the ERP unlocks for school A", s == 200, (s, r))
s, r, _ = call("PUT", "/school/application", A, {"schoolName": "Changed"})
check("a verified application can no longer be edited", s in (400, 403), (s, r))

# ---------------------------------------------------------------- tenant isolation
print("\n4. Tenant isolation")
s, mine, _ = call("GET", "/students?size=50", A)
rows = mine["data"]["content"] if isinstance(mine.get("data"), dict) else mine.get("data") or []
check("school A sees none of the other school's students", s == 200 and len(rows) == 0, mine)
s, g, _ = call("GET", "/students?size=1", GVS)
grows = g["data"]["content"] if isinstance(g.get("data"), dict) else g["data"]
GVS_STUDENT = grows[0]["id"]
s, r, _ = call("GET", "/students/" + GVS_STUDENT, A)
check("school A cannot open another school's student by id", s in (403, 404), (s, r))
s, r, _ = call("GET", "/students?size=5", A, headers={"X-Tenant-Id": GVS_TENANT})
rows = r["data"]["content"] if isinstance(r.get("data"), dict) else r.get("data") or []
check("a forged X-Tenant-Id header is ignored for school users", s in (200, 403) and len(rows) == 0, r)
s, gu, _ = call("GET", "/users?size=1", GVS)
urows = gu["data"]["content"] if isinstance(gu.get("data"), dict) else gu["data"]
s, r, _ = call("GET", "/users/" + urows[0]["id"], A)
check("school A cannot open another school's user by id", s in (403, 404), (s, r))
s, gb, _ = call("GET", "/branches", GVS)
GVS_BRANCHES = gb["data"]
check("GVS owner sees its three branches", s == 200 and len(GVS_BRANCHES) == 3, gb)
s, r, _ = call("PUT", "/branches/" + GVS_BRANCHES[1]["id"], A, {"name": "Stolen"})
check("school A cannot edit another school's branch", s == 404, (s, r))
s, r, _ = call("POST", "/auth/switch", A, {"tenantId": GVS_TENANT})
check("school A owner cannot switch into another school", s == 403, (s, r))
s, ctxs, _ = call("GET", "/auth/contexts", A)
check("the switcher lists only the user's own school",
      s == 200 and [c["tenantId"] for c in ctxs["data"]] == [A_TENANT], ctxs)

# ---------------------------------------------------------------- limits
print("\n5. Plan limits are enforced on the server")
s, ov, _ = call("GET", "/school/overview", A)
res = ov["data"]["resources"]
check("owner dashboard shows plan, limits and usage",
      s == 200 and res["users"]["limit"] == 120 and res["branches"]["limit"] == 1 and res["users"]["used"] == 1, res)
s, r, _ = call("POST", "/branches", A, {"name": "City Campus"})
check("Free plan: a second branch is refused", s == 409 and r.get("errorCode") == "BRANCH_LIMIT_REACHED", (s, r))
s, r, _ = call("POST", "/campuses", A, {"name": "City Campus", "code": "CITY"})
check("the old /campuses endpoint is refused too", s == 409 and r.get("errorCode") == "BRANCH_LIMIT_REACHED", (s, r))
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, A, {"maxBranches": 9})
check("a school cannot raise its own limits", s == 403, s)
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, PO, {"maxBranches": 2, "maxActiveUsers": 3, "note": "test"})
check("platform owner overrides limits for school A", s == 200 and r["data"]["branches"]["limit"] == 2, r)
s, br, _ = call("POST", "/branches", A, {"name": "City Campus", "city": "Lahore"})
check("with the override the second branch is created", s == 201 and br["data"]["main"] is False, (s, br))
CITY = (br.get("data") or {}).get("id")
s, r, _ = call("POST", "/branches", A, {"name": "Third Campus"})
check("the third branch is refused", s == 409 and r.get("errorCode") == "BRANCH_LIMIT_REACHED", (s, r))
created = []
for i in range(2):
    s, r, _ = call("POST", "/users", A, {"firstName": "Teacher", "lastName": "N%d" % i, "role": "TEACHER"})
    created.append(s)
check("users can be created up to the limit (3 active)", created == [201, 201] or created == [200, 200], created)
s, r, _ = call("POST", "/users", A, {"firstName": "Teacher", "lastName": "Extra", "role": "TEACHER"})
check("the user beyond the limit is refused with USER_LIMIT_REACHED",
      s == 409 and r.get("errorCode") == "USER_LIMIT_REACHED", (s, r))
s, us, _ = call("GET", "/users?size=20", A)
urows = us["data"]["content"] if isinstance(us.get("data"), dict) else us["data"]
teacher = [u for u in urows if u["role"] == "TEACHER"][0]
s, r, _ = call("PATCH", "/users/%s/deactivate" % teacher["id"], A)
if s == 404 or s == 405:
    s, r, _ = call("POST", "/users/%s/deactivate" % teacher["id"], A)
check("deactivating a user works", s == 200, (s, r))
s, r, _ = call("POST", "/users", A, {"firstName": "Teacher", "lastName": "Replacement", "role": "TEACHER"})
check("deactivating frees a seat", s in (200, 201), (s, r))
s, r, _ = call("PATCH", "/users/%s/activate" % teacher["id"], A)
if s == 404 or s == 405:
    s, r, _ = call("POST", "/users/%s/activate" % teacher["id"], A)
check("re-activating beyond the limit is refused", s == 409 and r.get("errorCode") == "USER_LIMIT_REACHED", (s, r))
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, PO, {"maxBranches": 2, "maxActiveUsers": 1})
s, ov, _ = call("GET", "/school/overview", A)
check("lowering a limit below usage deletes nothing (3 users kept, flagged over)",
      ov["data"]["resources"]["users"]["used"] == 3 and ov["data"]["resources"]["users"]["over"] is True,
      ov["data"]["resources"]["users"])
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, PO,
               {"maxBranches": 2, "maxActiveUsers": 20, "storageLimitMb": 0})
s, r, _ = call("POST", "/files", A, multipart([], [("file", "photo.png", "image/png", PNG)]))
check("upload is refused when storage is full", s == 409 and r.get("errorCode") == "STORAGE_LIMIT_REACHED", (s, r))
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, PO,
               {"maxBranches": 2, "maxActiveUsers": 20, "storageLimitMb": 5})
s, up, _ = call("POST", "/files", A, multipart([], [("file", "photo.png", "image/png", PNG)]))
check("upload works inside the storage limit", s == 200 and up["data"].get("url"), (s, up))
FILE_URL = (up.get("data") or {}).get("url", "")

# ---------------------------------------------------------------- files are private
print("\n6. Uploaded files are private")
path = FILE_URL.replace("/api/v1", "", 1)
s, body, _ = call("GET", path, raw=True)
check("anonymous cannot download a school's file", s == 404, s)
s, body, _ = call("GET", path, GVS, raw=True)
check("another school cannot download it", s == 404, s)
s, body, _ = call("GET", path, A, raw=True)
check("the owning school can download it", s == 200 and body[:4] == b"\x89PNG", s)
cookie = (a[2].get_all("Set-Cookie") or [""])[0].split(";")[0]
s, body, _ = call("GET", path, headers={"Cookie": cookie}, raw=True)
check("the HttpOnly file cookie from sign-in works for image tags", s == 200, (s, cookie[:20]))
gcookie = (gvs[2].get_all("Set-Cookie") or [""])[0].split(";")[0]
s, body, _ = call("GET", path, headers={"Cookie": gcookie}, raw=True)
check("another school's file cookie does not", s == 404, s)

# ---------------------------------------------------------------- branches
print("\n7. Branch isolation, branch admin, branch password")
s, r, _ = call("PUT", "/branches/%s/admin" % CITY, A, {"firstName": "Bilal", "lastName": "Ahmed"})
check("owner creates a branch admin for City Campus", s == 200 and r["data"].get("temporaryPassword"), (s, r))
BA_USER, BA_PW = r["data"]["username"], r["data"]["temporaryPassword"]
ba = login(BA_USER, BA_PW)
BA = token_of(ba)
bctx = (ba[1].get("data") or {}).get("context") or {}
check("branch admin lands directly in their branch",
      ba[0] == 200 and bctx.get("role") == "BRANCH_ADMIN" and bctx.get("branchId") == CITY, ba[1])
s, r, _ = call("GET", "/branches", BA)
check("branch admin sees only their own branch", s == 200 and [b["id"] for b in r["data"]] == [CITY], r)
s, r, _ = call("GET", "/campuses", BA)
check("the old /campuses list is branch-scoped too", s == 200 and [b["id"] for b in r["data"]] == [CITY], r)
s, r, _ = call("POST", "/branches", BA, {"name": "Rogue"})
check("branch admin cannot create branches", s == 403, (s, r))
s, r, _ = call("PUT", "/branches/%s/password" % CITY, BA, {"password": "letmein1"})
check("branch admin cannot set the branch password", s == 403, (s, r))
s, r, _ = call("POST", "/auth/switch", BA, {"tenantId": A_TENANT})
check("branch admin asking for the whole-school view stays inside their branch",
      s == 403 or (r["data"]["context"]["branchId"] == CITY and r["data"]["context"]["allBranches"] is False), (s, r))
s, ab, _ = call("GET", "/branches", A)
MAIN = [b for b in ab["data"] if b["main"]][0]["id"]
s, r, _ = call("POST", "/auth/switch", BA, {"tenantId": A_TENANT, "branchId": MAIN})
check("branch admin cannot switch to another branch", s == 403, (s, r))

# GVS: branch admins of the demo school see only their branch's students
g2 = login("GVS-ADM-0002", DEMO_PW)
G2 = token_of(g2)
g2ctx = g2[1]["data"].get("context") or {}
check("legacy branch admin (CANAL) lands in their branch", g2ctx.get("role") == "BRANCH_ADMIN" and g2ctx.get("branchName"), g2ctx)
s, st, _ = call("GET", "/students?size=200", G2)
st_rows = st["data"]["content"] if isinstance(st.get("data"), dict) else st["data"]
campus_ids = {x.get("campusId") for x in st_rows}
check("a branch admin's student list contains only their branch",
      len(st_rows) > 0 and campus_ids <= {g2ctx.get("branchId"), None} and g2ctx.get("branchId") in campus_ids,
      (len(st_rows), campus_ids))
s, allst, _ = call("GET", "/students?size=200", GVS)
all_rows = allst["data"]["content"] if isinstance(allst.get("data"), dict) else allst["data"]
check("the school owner (all branches) sees more students than one branch", len(all_rows) > len(st_rows),
      (len(all_rows), len(st_rows)))
other = [x for x in all_rows if x.get("campusId") and x.get("campusId") != g2ctx.get("branchId")]
if other:
    s, r, _ = call("GET", "/students/" + other[0]["id"], G2)
    check("a branch admin cannot open a student of another branch by id", s in (403, 404), (s, r))
owner_id = gvs[1]["data"]["user"]["id"]
s, r, _ = call("GET", "/users/" + owner_id, G2)
check("a branch admin cannot open the school owner's account by id", s in (403, 404), (s, r))
s, r, _ = call("POST", "/users/%s/reset-password" % owner_id, G2, {})
check("a branch admin cannot reset the school owner's password", s in (403, 404, 405), (s, r))

s, r, _ = call("PUT", "/branches/%s/password" % CITY, A, {"password": "city-secret-1"})
check("owner sets a branch access password", s == 200 and r["data"]["hasPassword"] is True, (s, r))
s, r, _ = call("POST", "/auth/refresh", body={"refreshToken": ba[1]["data"]["refreshToken"]})
check("existing branch sessions are ended when the password is set", s == 401, (s, r))
ba2 = login(BA_USER, BA_PW)
d = ba2[1].get("data") or {}
check("with a branch password, sign-in stops at the private picker (no tokens yet)",
      d.get("selectionRequired") is True and not d.get("accessToken") and d.get("selectionToken"), d)
check("the picker lists only that user's school and branch",
      [x["tenantId"] for x in d.get("schools", [])] == [A_TENANT]
      and [b["id"] for b in d["schools"][0]["branches"]] == [CITY], d.get("schools"))
SEL = d.get("selectionToken")
s, r, _ = call("POST", "/auth/select", body={"selectionToken": SEL, "tenantId": A_TENANT, "branchId": CITY})
check("entering without the branch password is refused", s == 403 and r.get("errorCode") == "BRANCH_PASSWORD_REQUIRED", (s, r))
s, r, _ = call("POST", "/auth/select", body={"selectionToken": SEL, "tenantId": A_TENANT, "branchId": CITY,
                                              "branchPassword": "wrong"})
check("a wrong branch password is refused", s == 403 and r.get("errorCode") == "BRANCH_PASSWORD_INVALID", (s, r))
s, r, _ = call("POST", "/auth/select", body={"selectionToken": SEL, "tenantId": GVS_TENANT})
check("the selection token cannot be used to enter another school", s == 403, (s, r))
s, r, _ = call("POST", "/auth/select", body={"selectionToken": SEL, "tenantId": A_TENANT, "branchId": CITY,
                                              "branchPassword": "city-secret-1"})
check("the correct branch password lets the branch admin in", s == 200 and r["data"].get("accessToken"), (s, r))
BA = r["data"]["accessToken"]
s, r, _ = call("POST", "/auth/select", body={"selectionToken": GVS, "tenantId": A_TENANT})
check("an access token is not accepted as a selection token", s == 401, (s, r))
s, r, _ = call("GET", "/students", SEL)
check("a selection token is not accepted as an access token", s in (401, 403), (s, r))
s, r, _ = call("POST", "/auth/switch", A, {"tenantId": A_TENANT, "branchId": CITY})
check("the owner enters any branch without the branch password", s == 200 and r["data"]["context"]["branchId"] == CITY, (s, r))

s, r, _ = call("POST", "/branches/%s/status" % MAIN, A, {"status": "ARCHIVED"})
check("the main branch cannot be archived", s == 400, (s, r))
s, r, _ = call("POST", "/branches/%s/status" % CITY, A, {"status": "SUSPENDED"})
check("owner suspends City Campus", s == 200 and r["data"]["status"] == "SUSPENDED", (s, r))
time.sleep(0.1)
s, r, _ = call("GET", "/students", BA)
check("a suspended branch locks its sessions out immediately", s == 403 and r.get("errorCode") == "BRANCH_UNAVAILABLE", (s, r))
ba3 = login(BA_USER, BA_PW)
check("and its users cannot sign in to it", ba3[0] == 401, ba3[1])
s, r, _ = call("POST", "/branches/%s/status" % CITY, A, {"status": "ARCHIVED"})
s, r, _ = call("POST", "/branches", A, {"name": "North Campus"})
check("archiving a branch frees a branch slot", s == 201, (s, r))
s, r, _ = call("POST", "/branches/%s/status" % CITY, A, {"status": "ACTIVE"})
check("restoring an archived branch respects the limit", s == 409 and r.get("errorCode") == "BRANCH_LIMIT_REACHED", (s, r))

# ---------------------------------------------------------------- plans and settings
print("\n8. Plans and platform settings")
s, plans, _ = call("GET", "/platform/plans", PO)
check("platform owner lists plans", s == 200 and len(plans["data"]) >= 5, plans)
s, r, _ = call("PUT", "/platform/schools/%s/plan" % A_TENANT, PO, {"planCode": "BASIC", "billingCycle": "MONTHLY"})
check("platform owner moves school A to Basic", s == 200 and r["data"]["limits"]["subscribedPlanCode"] == "BASIC", r)
s, r, _ = call("PUT", "/platform/schools/%s/limits" % A_TENANT, PO, {})
check("clearing the override returns the school to plan limits (Basic: 300 users, 3 branches)",
      s == 200 and r["data"]["users"]["limit"] == 300 and r["data"]["branches"]["limit"] == 3, r)
code = "T" + RUN.upper()
s, r, _ = call("POST", "/platform/plans", PO, {"code": code, "name": "Trial " + RUN, "maxActiveUsers": 50,
                                               "storageLimitMb": 500, "ramBudgetMb": 512, "maxBranches": 1,
                                               "isPublic": False})
check("platform owner creates a plan", s == 200 and r["data"]["code"] == code, (s, r))
s, r2, _ = call("PUT", "/platform/plans/" + r["data"]["id"], PO, {"maxActiveUsers": 60, "isActive": False})
check("platform owner edits and deactivates the plan", s == 200 and r2["data"]["maxActiveUsers"] == 60, (s, r2))
s, r, _ = call("POST", "/platform/plans", A, {"code": "HACK", "name": "x"})
check("a school cannot create plans", s == 403, s)
s, settings, _ = call("GET", "/platform/settings", PO)
ann = {"enabled": True, "text": "Maintenance tonight " + RUN, "tone": "info", "linkTo": "", "linkLabel": ""}
s, r, _ = call("PUT", "/platform/settings/announcement", PO, ann)
s2, site, _ = call("GET", "/public/site?x=" + RUN)
check("platform owner edits the announcement and visitors see it",
      s == 200 and site["data"]["announcement"]["text"] == ann["text"], site["data"].get("announcement"))
call("PUT", "/platform/settings/announcement", PO, settings["data"]["announcement"])
s, r, _ = call("PUT", "/platform/settings/branding", A, {"name": "Hacked"})
check("a school cannot change platform settings", s == 403, s)
s, r, _ = call("PUT", "/platform/settings/unknown_key", PO, {"a": 1})
check("unknown settings keys are refused", s == 400, (s, r))
s, ov, _ = call("GET", "/platform/overview", PO)
check("platform dashboard reports real server figures",
      s == 200 and ov["data"]["server"].get("heapMaxMb", 0) > 0 and ov["data"]["schools"]["total"] >= 2, ov)

# ---------------------------------------------------------------- suspension
print("\n9. Suspension and second-school isolation")
B_EMAIL, rb = register("b")
b = login(B_EMAIL, "Sunrise123")
B_TENANT = b[1]["data"]["context"]["tenantId"]
s, r, _ = call("POST", "/platform/schools/%s/review" % B_TENANT, PO, {"action": "REJECT", "note": "Document unreadable."})
check("platform owner rejects school B with a reason", s == 200 and r["data"]["verificationStatus"] == "REJECTED", r)
b = login(B_EMAIL, "Sunrise123")
B = token_of(b)
s, app, _ = call("GET", "/school/application", B)
check("school B's owner sees the rejection reason",
      s == 200 and app["data"]["application"]["rejectionReason"] == "Document unreadable.", app)
s, r, _ = call("GET", "/students", B)
check("a rejected school cannot use the ERP", s == 403, (s, r))
s, r, _ = call("POST", "/school/application/resubmit", B)
check("owner resubmits after rejection", s == 200 and r["data"]["verificationStatus"] == "PENDING", (s, r))

s, r, _ = call("POST", "/platform/schools/%s/review" % A_TENANT, PO, {"action": "SUSPEND", "note": "Payment dispute."})
check("platform owner suspends school A", s == 200 and r["data"]["verificationStatus"] == "SUSPENDED", r)
time.sleep(0.1)
s, r, _ = call("GET", "/students", A)
check("a suspended school is locked out of the ERP at once", s == 403, (s, r))
s, us2, _ = call("GET", "/platform/schools/%s" % A_TENANT, PO)
check("suspension keeps all of the school's data", us2["data"]["resources"]["usage"]["totalUsers"] >= 4, us2["data"]["resources"]["usage"])
a2 = login(A_EMAIL, "Sunrise123")
check("the owner can still sign in to see why", a2[0] == 200 and a2[1]["data"]["context"]["verificationStatus"] == "SUSPENDED", a2[1])
s, ov, _ = call("GET", "/school/overview", token_of(a2))
check("and sees the suspension reason", s == 200 and ov["data"]["school"]["suspensionReason"] == "Payment dispute.", (s, ov))
s, r, _ = call("POST", "/platform/schools/%s/review" % A_TENANT, PO, {"action": "REACTIVATE"})
check("platform owner reactivates school A", s == 200 and r["data"]["verificationStatus"] == "VERIFIED", r)
s, g, _ = call("GET", "/students?size=1", GVS)
check("the other school was never affected", s == 200, s)
if total_students is not None:
    check("and still has all its students (%s)" % total_students, g["data"]["totalElements"] == total_students)

# ---------------------------------------------------------------- brute force
print("\n10. Rate limiting")
ip = "10.99.%d.%d" % (random.randint(1, 250), random.randint(1, 250))
codes = [call("POST", "/auth/login", body={"username": "nobody-" + RUN, "password": "x"}, ip=ip)[0] for _ in range(26)]
check("repeated sign-in attempts from one address are throttled (429)", 429 in codes and codes[0] == 401, codes)
s, r, _ = call("POST", "/auth/login", body={"username": "nobody-" + RUN, "password": "x"})
check("other addresses are not affected", s == 401, s)
msg_unknown = r.get("message")
s, r, _ = call("POST", "/auth/login", body={"username": "GVS-ADM-0001", "password": "definitely-wrong"})
check("wrong password and unknown user give the same answer", s == 401 and r.get("message") == msg_unknown, (r.get("message"), msg_unknown))

print("\n%d passed, %d failed" % (passed, len(failed)))
for f in failed:
    print("  FAILED: " + f)
sys.exit(1 if failed else 0)
