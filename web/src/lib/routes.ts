import type { Role, User } from "./types";

/** Every account signs in on one central page. The server decides where it lands. */
export const LOGIN_PORTALS = "/login";

export function homeFor(user: User) {
  if (user.role === "ERP_OWNER") return "/admin/app";
  if (user.mustChangePassword) return "/app/password";
  return "/app";
}

export function isProtectedPath(pathname: string) {
  return pathname.startsWith("/app") || pathname.startsWith("/admin/app");
}

export function can(role: Role | undefined, allowed: Role[]) {
  return !!role && allowed.includes(role);
}

/**
 * A school's public website lives at /s/<CODE>. Nothing about a school is shown
 * unless the visitor arrived with that school's own address.
 */
export function schoolCodeFromPath(pathname: string): string | null {
  const m = /^\/s\/([A-Za-z0-9]{2,20})(?:\/|$)/.exec(pathname);
  return m ? m[1].toUpperCase() : null;
}

export function publicSchoolCode(): string | undefined {
  return schoolCodeFromPath(window.location.pathname) || undefined;
}

export function schoolPath(code: string | undefined | null, sub = "") {
  return code ? `/s/${code}${sub}` : "/";
}
