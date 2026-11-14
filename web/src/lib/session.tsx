import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { authApi, tenantApi } from "../api/services";
import { accessApi, type AccessContext, type LoginPayload, type SchoolOption, type VerificationStatus } from "../api/saas";
import { ApiError, AUTH_LOST_EVENT, LOCKED_EVENT, SCHOOL_GATED_EVENT, clearTokens, getAccessToken, getAuthEpoch, getRefreshToken, setTokens } from "../api/client";
import { clearFeatureCache, writeFeatureCache } from "./featureCache";
import { LOGIN_PORTALS } from "./routes";
import type { Role, Tenant, User } from "./types";
import { LOCKED_STATUSES } from "./types";

const USER_KEY = "atrium.user";
const CHILD_KEY = "atrium.child";
const CONTEXT_KEY = "atrium.context";

/** Sign-in needs one more step: the user belongs to several schools or branches. */
export interface PendingSelection {
  token: string;
  schools: SchoolOption[];
}

export type LoginResult = { user: User; selection?: undefined } | { user?: undefined; selection: PendingSelection };

interface Session {
  user: User | null;
  tenant: Tenant | null;
  loading: boolean;
  locked: boolean;
  childId: string | null;
  /** Where the server placed this session: school, branch (or all branches) and role. */
  context: AccessContext | null;
  /** Live verification state of the school; the ERP is usable only when VERIFIED. */
  verification: VerificationStatus | null;
  login: (username: string, password: string) => Promise<LoginResult>;
  select: (selectionToken: string, tenantId: string, branchId?: string | null, branchPassword?: string) => Promise<User>;
  switchTo: (tenantId: string, branchId?: string | null, branchPassword?: string) => Promise<User>;
  logout: (opts?: { remote?: boolean }) => Promise<void>;
  reload: () => Promise<void>;
  setChildId: (id: string | null) => void;
}

const Ctx = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const [user, setUser] = useState<User | null>(() => {
    try {
      const raw = localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as User) : null;
    } catch {
      return null;
    }
  });
  const [tenant, setTenant] = useState<Tenant | null>(null);
  const [context, setContextState] = useState<AccessContext | null>(() => {
    try {
      const raw = localStorage.getItem(CONTEXT_KEY);
      return raw ? (JSON.parse(raw) as AccessContext) : null;
    } catch {
      return null;
    }
  });
  const setContext = (next: AccessContext | null) => {
    setContextState(next);
    if (next) localStorage.setItem(CONTEXT_KEY, JSON.stringify(next));
    else localStorage.removeItem(CONTEXT_KEY);
  };
  const [loading, setLoading] = useState(() => Boolean(getAccessToken()) && !user);
  const [forcedLocked, setForcedLocked] = useState(false);
  const [childId, setChildIdState] = useState<string | null>(() => localStorage.getItem(CHILD_KEY));
  const leaving = useRef(false);
  const skipIdleMe = useRef(false);

  const persist = (next: User | null) => {
    setUser(next);
    if (next) localStorage.setItem(USER_KEY, JSON.stringify(next));
    else localStorage.removeItem(USER_KEY);
  };

  const loadTenant = async (u: User) => {
    if (u.role === "ERP_OWNER") {
      setTenant(null);
      setForcedLocked(false);
      return;
    }
    try {
      const t = await tenantApi.me();
      setTenant(t);
      setForcedLocked(LOCKED_STATUSES.includes(t.status));
    } catch (e) {
      if (e instanceof ApiError && e.errorCode === "SUBSCRIPTION_INACTIVE") {
        setForcedLocked(true);
      } else {
        setTenant(null);
      }
    }
  };

  const reload = useCallback(async () => {
    if (!getAccessToken()) {
      persist(null);
      setTenant(null);
      setLoading(false);
      return;
    }
    const epoch = getAuthEpoch();
    try {
      let cachedRole: Role | undefined;
      try {
        const raw = localStorage.getItem(USER_KEY);
        cachedRole = raw ? (JSON.parse(raw) as User).role : undefined;
      } catch {
        cachedRole = undefined;
      }
      if (cachedRole && cachedRole !== "ERP_OWNER") {
        const [meResult, tenantResult] = await Promise.allSettled([authApi.me(), tenantApi.me()]);
        if (meResult.status !== "fulfilled") throw meResult.reason;
        if (getAuthEpoch() !== epoch) return;
        persist(meResult.value);
        if (tenantResult.status === "fulfilled") {
          setTenant(tenantResult.value);
          setForcedLocked(LOCKED_STATUSES.includes(tenantResult.value.status));
        } else {
          const e = tenantResult.reason;
          if (e instanceof ApiError && e.errorCode === "SUBSCRIPTION_INACTIVE") setForcedLocked(true);
          else setTenant(null);
        }
      } else {
        const me = await authApi.me();
        if (getAuthEpoch() !== epoch) return;
        persist(me);
        await loadTenant(me);
      }
    } catch (e) {
      if (getAuthEpoch() !== epoch) return;
      if (e instanceof ApiError && e.errorCode === "SUBSCRIPTION_INACTIVE") {
        setForcedLocked(true);
      } else {
        clearTokens();
        persist(null);
        setTenant(null);
        setContext(null);
        setForcedLocked(false);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getAccessToken()) {
      setLoading(false);
      return;
    }
    if (user) {
      if (skipIdleMe.current) {
        skipIdleMe.current = false;
        setLoading(false);
        return;
      }
      const idle = (window as Window & { requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number }).requestIdleCallback;
      if (typeof idle === "function") {
        const id = idle(() => { void reload(); }, { timeout: 0 });
        const cancel = (window as Window & { cancelIdleCallback?: (id: number) => void }).cancelIdleCallback;
        return () => cancel?.(id);
      }
      const t = window.setTimeout(() => { void reload(); }, 0);
      return () => window.clearTimeout(t);
    }
    void reload();
  }, [reload]);

  useEffect(() => {
    const onLocked = () => setForcedLocked(true);
    window.addEventListener(LOCKED_EVENT, onLocked);
    return () => window.removeEventListener(LOCKED_EVENT, onLocked);
  }, []);

  useEffect(() => {
    // The server refused a request because the school is not verified or is suspended.
    // Re-read the school so the right status screen appears instead of broken pages.
    const onGated = () => {
      void tenantApi.me().then((t) => setTenant(t)).catch(() => undefined);
    };
    window.addEventListener(SCHOOL_GATED_EVENT, onGated);
    return () => window.removeEventListener(SCHOOL_GATED_EVENT, onGated);
  }, []);

  useEffect(() => {
    const onLost = () => {
      persist(null);
      setTenant(null);
      setContext(null);
      setForcedLocked(false);
      localStorage.removeItem(CHILD_KEY);
      setChildIdState(null);
      navigate(LOGIN_PORTALS, { replace: true });
    };
    window.addEventListener(AUTH_LOST_EVENT, onLost);
    return () => window.removeEventListener(AUTH_LOST_EVENT, onLost);
  }, [navigate]);

  const applyTenant = (u: User, tenant?: Tenant | null) => {
    if (u.role === "ERP_OWNER") {
      setTenant(null);
      setForcedLocked(false);
      return true;
    }
    if (tenant) {
      setTenant(tenant);
      setForcedLocked(LOCKED_STATUSES.includes(tenant.status));
      return true;
    }
    return false;
  };

  const adopt = async (payload: LoginPayload): Promise<User> => {
    if (!payload.accessToken || !payload.user) {
      throw new ApiError(401, "Unable to complete sign-in. Please try again.");
    }
    setTokens(payload.accessToken, payload.refreshToken);
    writeFeatureCache(payload.user.tenantId, payload.features);
    setContext(payload.context || null);
    persist(payload.user);
    skipIdleMe.current = true;
    if (!applyTenant(payload.user, payload.tenant)) {
      await loadTenant(payload.user);
    }
    setLoading(false);
    return payload.user;
  };

  const login = useCallback(async (username: string, password: string): Promise<LoginResult> => {
    const payload = await accessApi.login(username.trim(), password);
    if (payload.selectionRequired) {
      // No session yet: the server only told this user which of their own schools
      // and branches they may enter. Tokens are issued after they choose.
      return { selection: { token: payload.selectionToken || "", schools: payload.schools || [] } };
    }
    return { user: await adopt(payload) };
  }, []);

  const select = useCallback(async (selectionToken: string, tenantId: string, branchId?: string | null, branchPassword?: string) => {
    return adopt(await accessApi.select(selectionToken, tenantId, branchId, branchPassword));
  }, []);

  const switchTo = useCallback(async (tenantId: string, branchId?: string | null, branchPassword?: string) => {
    const payload = await accessApi.switchTo(tenantId, branchId, branchPassword);
    clearFeatureCache();
    localStorage.removeItem(CHILD_KEY);
    setChildIdState(null);
    return adopt(payload);
  }, []);

  const logout = useCallback(async (opts?: { remote?: boolean }) => {
    if (leaving.current) return;
    leaving.current = true;
    const rt = opts?.remote !== false ? getRefreshToken() : null;
    clearTokens();
    persist(null);
    setTenant(null);
    setContext(null);
    setForcedLocked(false);
    localStorage.removeItem(CHILD_KEY);
    setChildIdState(null);
    clearFeatureCache();
    leaving.current = false;
    if (rt) void authApi.logout(rt).catch(() => undefined);
    navigate(LOGIN_PORTALS, { replace: true });
  }, [navigate]);

  const setChildId = useCallback((id: string | null) => {
    setChildIdState(id);
    if (id) localStorage.setItem(CHILD_KEY, id);
    else localStorage.removeItem(CHILD_KEY);
  }, []);

  const locked = Boolean(
    user &&
      user.role !== "ERP_OWNER" &&
      (forcedLocked || (tenant && LOCKED_STATUSES.includes(tenant.status))),
  );

  const verification: VerificationStatus | null = !user || user.role === "ERP_OWNER"
    ? null
    : tenant?.verificationStatus || context?.verificationStatus || null;

  const value = useMemo<Session>(
    () => ({ user, tenant, loading, locked, childId, context, verification, login, select, switchTo, logout, reload, setChildId }),
    [user, tenant, loading, locked, childId, context, verification, login, select, switchTo, logout, reload, setChildId],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useSession outside provider");
  return ctx;
}
