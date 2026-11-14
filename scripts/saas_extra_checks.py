#!/usr/bin/env python3
"""Second end-to-end check for the SaaS layer (standard library only).

Covers what scripts/saas_smoke_test.py does not: website images, platform
settings, editing and resubmitting a school application, document review,
branch staff access, branch passwords and lockout, per-branch modules, upload
size limit and sign-out.

    python3 scripts/saas_extra_checks.py http://127.0.0.1:8080

Needs the demo school (SEED_DEMO_DATA=true) and curl. It registers one
throw-away school, so run it against a test database only.
"""
import json, sys, urllib.request, urllib.error, uuid, base64
B = sys.argv[1] + "/api/v1"
ok = bad = 0
def check(name, cond, extra=""):
    global ok, bad
    if cond: ok += 1; print("  ok  ", name)
    else: bad += 1; print("  FAIL", name, extra)
def call(method, path, token=None, body=None, raw=None, ctype=None, headers=None):
    h = dict(headers or {})
    data = None
    if body is not None: data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    if raw is not None: data = raw; h["Content-Type"] = ctype
    if token: h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(B + path, data=data, method=method, headers=h)
    try:
        r = urllib.request.urlopen(req, timeout=60); code, hd, txt = r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        code, hd, txt = e.code, e.headers, e.read()
    try: js = json.loads(txt)
    except Exception: js = None
    return code, js, hd, txt
def multipart(fields, files):
    bd = "----x" + uuid.uuid4().hex; out = b""
    for k, v in fields: out += f'--{bd}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    for k, fn, ct, content in files: out += f'--{bd}\r\nContent-Disposition: form-data; name="{k}"; filename="{fn}"\r\nContent-Type: {ct}\r\n\r\n'.encode() + content + b"\r\n"
    return out + f"--{bd}--\r\n".encode(), "multipart/form-data; boundary=" + bd
def login(u, p):
    c, j, h, _ = call("POST", "/auth/login", body={"username": u, "password": p})
    return (j or {}).get("data") or {}, c, h
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
PDF = b"%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
po = login("erp.owner", "Owner@12345")[0]["accessToken"]
ow = login("GVS-ADM-0001", "ChangeMe@123")[0]["accessToken"]
tch_d = login("GVS-TCH-0001", "ChangeMe@123")[0]; tch = tch_d["accessToken"]

print("Platform assets")
raw, ct = multipart([], [("file", "logo.png", "image/png", PNG)])
c, j, _, _ = call("POST", "/platform/assets", po, raw=raw, ctype=ct); url = ((j or {}).get("data") or {}).get("url", "")
check("platform owner uploads a website image", c in (200, 201) and url, f"{c} {j}")
c2, _, h2, body = call("GET", url.replace("/api/v1", "", 1)) if url else (0, None, {}, b"")
check("the image is public and served as an image", c2 == 200 and body == PNG and "image/png" in (h2.get("Content-Type") or ""), f"{c2} {url}")
c, j, _, _ = call("POST", "/platform/assets", ow, raw=raw, ctype=ct); check("a school owner cannot upload platform images", c == 403, c)
raw2, ct2 = multipart([], [("file", "x.html", "text/html", b"<script>alert(1)</script>")])
c, j, _, _ = call("POST", "/platform/assets", po, raw=raw2, ctype=ct2); check("a non-image upload is refused", c == 400, f"{c} {j}")
c, _, _, _ = call("GET", "/public/assets/..%2F..%2Fetc%2Fpasswd"); check("asset path traversal is refused", c in (400, 404), c)

print("Platform settings")
c, j, _, _ = call("GET", "/platform/settings", po); ann = j["data"]["announcement"]
c, j, _, _ = call("PUT", "/platform/settings/announcement", po, body={**ann, "enabled": True, "text": "Admissions season is open"})
check("platform owner saves the announcement", c == 200, f"{c} {j}")
c, j, _, _ = call("GET", "/public/site"); check("the public site shows it at once", j["data"]["announcement"]["text"] == "Admissions season is open")
check("monitoring thresholds are not public", "monitoring" not in j["data"])
call("PUT", "/platform/settings/announcement", po, body=ann)
c, j, _, _ = call("PUT", "/platform/settings/announcement", ow, body=ann); check("a school owner cannot change platform settings", c == 403, c)
c, j, _, _ = call("PUT", "/platform/settings/nonsense", po, body={"a": 1}); check("unknown setting keys are refused", c in (400, 404), c)

print("School application (pending school)")
tag = uuid.uuid4().hex[:6]; email = f"own.{tag}@example.test"
raw, ct = multipart([("schoolName", f"Extra {tag} Academy"), ("address", "1 Road"), ("city", "Lahore"), ("country", "Pakistan"), ("contactNumber", "+92 300 1112223"), ("officialEmail", f"info.{tag}@example.test"), ("principalName", "Zara Noor"), ("schoolType", "Primary"), ("ownerFirstName", "Zara"), ("ownerLastName", "Noor"), ("ownerEmail", email), ("password", "Welcome123"), ("documentTypes", "Registration certificate")], [("documents", "cert.pdf", "application/pdf", PDF)])
c, j, _, _ = call("POST", "/public/register", raw=raw, ctype=ct); check("school registers", c == 201, f"{c} {j}")
so = login(email, "Welcome123")[0]["accessToken"]
c, j, _, _ = call("GET", "/school/application", so); app = j["data"]; doc_id = app["documents"][0]["id"]
a = dict(app["application"]); a["city"] = "Karachi"
c, j, _, _ = call("PUT", "/school/application", so, body=a); check("owner edits details while pending", c == 200 and j["data"]["application"]["city"] == "Karachi", f"{c} {j}")
raw, ct = multipart([("documentType", "Tax certificate")], [("file", "tax.png", "image/png", PNG)])
c, j, _, _ = call("POST", "/school/application/documents", so, raw=raw, ctype=ct); check("owner adds a second document", c in (200, 201) and len(j["data"]["documents"]) == 2, f"{c} {j}")
doc2 = [d for d in j["data"]["documents"] if d["id"] != doc_id][0]["id"]
c, _, h, body = call("GET", f"/school/application/documents/{doc_id}", so); check("owner downloads their own document", c == 200 and body == PDF, c)
c, _, _, _ = call("GET", f"/school/application/documents/{doc_id}", ow); check("another school's owner cannot (404)", c == 404, c)
c, _, _, _ = call("GET", f"/school/application/documents/{doc_id}"); check("nobody can without signing in", c in (401, 403, 404), c)
c, j, _, _ = call("DELETE", f"/school/application/documents/{doc2}", so); check("owner removes a document", c == 200 and len(j["data"]["documents"]) == 1, f"{c} {j}")
c, j, _, _ = call("GET", "/platform/schools?status=PENDING&q=" + tag, po); sid = j["data"][0]["id"]
c, _, _, body = call("GET", f"/platform/schools/{sid}/documents/{doc_id}", po); check("platform owner opens the document", c == 200 and body == PDF, c)
c, _, _, _ = call("GET", f"/platform/schools/{sid}/documents/{doc_id}", ow); check("a school owner cannot use the platform document route", c == 403, c)
c, j, _, _ = call("POST", f"/platform/schools/{sid}/documents/{doc_id}/review", po, body={"status": "REJECTED", "note": "Scan is unreadable"}); check("platform owner rejects one document with a note", c == 200, f"{c} {j}")
c, j, _, _ = call("GET", "/school/application", so); d0 = j["data"]["documents"][0]; check("the school sees the rejected document and the reason", d0["status"] == "REJECTED" and "unreadable" in json.dumps(d0), d0)
c, j, _, _ = call("POST", f"/platform/schools/{sid}/review", po, body={"action": "REJECT", "note": "Documents not acceptable"}); check("school rejected", c == 200 and j["data"]["verificationStatus"] == "REJECTED", f"{c}")
c, j, _, _ = call("GET", "/students", so); check("a rejected school cannot use the ERP", c == 403 and j.get("errorCode") == "SCHOOL_NOT_VERIFIED", f"{c} {j}")
c, j, _, _ = call("POST", "/school/application/resubmit", so); check("resubmitting with only a rejected document is refused", c == 400 and j.get("errorCode") == "DOCUMENTS_REQUIRED", f"{c} {j}")
raw, ct = multipart([("documentType", "Registration certificate")], [("file", "clear.pdf", "application/pdf", PDF)])
call("POST", "/school/application/documents", so, raw=raw, ctype=ct)
c, j, _, _ = call("POST", "/school/application/resubmit", so); check("owner resubmits after uploading a new document", c == 200, f"{c} {j}")
c, j, _, _ = call("GET", "/tenants/me", so); check("status is back to pending and visible to the web app", c == 200 and j["data"].get("verificationStatus") == "PENDING", f"{c} {j and j.get('data', {}).get('verificationStatus')}")

print("Branch access")
gvs = login("GVS-ADM-0001", "ChangeMe@123")[0]["context"]["tenantId"]
c, j, _, _ = call("POST", "/auth/switch", ow, body={"tenantId": gvs}); ow = j["data"]["accessToken"]
check("owner returns to the whole school", j["data"]["context"]["allBranches"] is True, j["data"]["context"])
c, j, _, _ = call("GET", "/branches", ow); br = {b["code"]: b for b in j["data"]}; cantt = br["CANTT"]["id"]; canal = br["CANAL"]["id"]
check("legacy branch admins are shown on their branch", br["CANAL"].get("adminUsername") == "GVS-ADM-0002", br["CANAL"].get("adminUsername"))
c, j, _, _ = call("GET", "/branches/staff", ow); staff = j["data"]; t = [s for s in staff if s.get("username") == "GVS-TCH-0001"][0]
home = tch_d["context"]["branchId"]; other = cantt if home != cantt else canal
c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other}); check("teacher cannot open a branch they do not belong to", c == 403, f"{c} {j}")
c, j, _, _ = call("POST", f"/branches/{other}/members", ow, body={"userId": t["id"]}); check("owner gives the teacher access to a second branch", c in (200, 201), f"{c} {j}")
c, j, _, _ = call("PUT", f"/branches/{other}/password", ow, body={"password": "Branch#77"}); check("owner sets a branch password", c == 200, f"{c} {j}")
c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other}); check("the branch now asks for its password", c == 403 and j.get("errorCode") == "BRANCH_PASSWORD_REQUIRED", f"{c} {j}")
codes = []
for i in range(5):
    c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other, "branchPassword": "wrong" + str(i)}); codes.append(j.get("errorCode"))
check("wrong branch passwords are refused", codes[0] == "BRANCH_PASSWORD_INVALID", codes)
c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other, "branchPassword": "Branch#77"}); check("after 5 wrong tries the branch is locked even with the right password", c == 403 and j.get("errorCode") == "BRANCH_LOCKED", f"{c} {j} {codes}")
c, j, _, _ = call("POST", "/auth/switch", ow, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other}); check("the owner still enters without the branch password", c == 200, f"{c} {j}")
call("POST", "/auth/switch", ow, body={"tenantId": gvs})
call("PUT", f"/branches/{other}/password", ow, body={"password": ""})
c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other}); check("with the password removed the member enters", c == 200, f"{c} {j}")
c, j, _, _ = call("DELETE", f"/branches/{other}/members/{t['id']}", ow); check("owner removes that access", c == 200, f"{c} {j}")
c, j, _, _ = call("POST", "/auth/switch", tch, body={"tenantId": tch_d["context"]["tenantId"], "branchId": other}); check("and the teacher is refused again", c == 403, f"{c} {j}")
acc_d = login("GVS-ACC-0001", "ChangeMe@123")[0]; acc = acc_d["accessToken"]; ab = acc_d["context"]["branchId"]
abr = [b for b in br.values() if b["id"] == ab][0]; perms = abr.get("permissions") or {}
c, j, _, _ = call("GET", "/fees/structures", acc); check("account officer reads fee structures", c == 200, f"{c} {j}")
c, j, _, _ = call("PUT", f"/branches/{ab}/permissions", ow, body={**perms, "FEES": False}); check("owner switches a module off for one branch", c == 200, f"{c} {j}")
c, j, _, _ = call("GET", "/fees/structures", acc); check("staff of that branch are refused the module", c == 403 and j.get("errorCode") == "BRANCH_MODULE_DISABLED", f"{c} {j}")
c, j, _, _ = call("GET", "/notifications/unread-count", acc); check("other parts still work there", c == 200, c)
c, j, _, _ = call("PUT", f"/branches/{ab}/permissions", ow, body={**perms, "FEES": True})
c, j, _, _ = call("GET", "/fees/structures", acc); check("switching it back on restores access", c == 200, f"{c} {j}")
ba = login("GVS-ADM-0002", "ChangeMe@123")[0]["accessToken"]
c, j, _, _ = call("PUT", f"/branches/{canal}/permissions", ba, body={"FEES": True}); check("a branch admin cannot change branch modules", c == 403, c)

print("Uploads and sign-out")
import subprocess, tempfile, os
tf = tempfile.NamedTemporaryFile(suffix=".png", delete=False); tf.write(b"\0" * (61 * 1024 * 1024)); tf.close()
out = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST", B + "/platform/assets", "-H", "Authorization: Bearer " + po, "-F", f"file=@{tf.name};type=image/png"], capture_output=True, text=True).stdout
os.unlink(tf.name)
check("a 61 MB upload is refused with 413", out == "413", out)
d, c, h = login("GVS-ADM-0001", "ChangeMe@123"); cookie = h.get("Set-Cookie") or ""
check("sign-in sets the file cookie HttpOnly", "dz_files=" in cookie and "HttpOnly" in cookie, cookie[:80])
c, j, h, _ = call("POST", "/auth/logout", d["accessToken"], body={"refreshToken": d["refreshToken"]}); cookie = h.get("Set-Cookie") or ""
check("sign-out clears it", "dz_files=" in cookie and "Max-Age=0" in cookie, f"{c} {cookie[:90]}")
c, j, _, _ = call("POST", "/auth/refresh", body={"refreshToken": d["refreshToken"]}); check("the refresh token no longer works", c == 401, c)
print(f"\n{ok} passed, {bad} failed")
