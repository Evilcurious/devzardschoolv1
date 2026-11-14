/**
 * DEVZARD eSchool platform API: central login context, school registration and
 * verification, branches, plans and limits, platform settings.
 */
import { api, getAccessToken } from "./client";
import type { AuthPayload } from "../lib/types";

const v1 = "/api/v1";

export type SaasRole =
  | "PLATFORM_OWNER"
  | "SCHOOL_OWNER"
  | "SCHOOL_ADMIN"
  | "BRANCH_ADMIN"
  | "TEACHER"
  | "STAFF"
  | "STUDENT"
  | "PARENT";

export type VerificationStatus = "PENDING" | "UNDER_REVIEW" | "VERIFIED" | "REJECTED" | "SUSPENDED";

export const SAAS_ROLE_LABEL: Record<SaasRole, string> = {
  PLATFORM_OWNER: "Platform Owner",
  SCHOOL_OWNER: "School Owner",
  SCHOOL_ADMIN: "School Admin",
  BRANCH_ADMIN: "Branch Admin",
  TEACHER: "Teacher",
  STAFF: "Staff",
  STUDENT: "Student",
  PARENT: "Parent",
};

export interface AccessContext {
  role: SaasRole;
  tenantId?: string;
  tenantName?: string;
  branchId?: string | null;
  branchName?: string | null;
  allBranches?: boolean;
  verificationStatus?: VerificationStatus;
  canSwitch?: boolean;
}

export interface BranchOption {
  id: string;
  name: string;
  main?: boolean;
  requiresPassword?: boolean;
}

export interface SchoolOption {
  tenantId: string;
  name: string;
  logoUrl?: string;
  role: SaasRole;
  status?: string;
  verificationStatus?: VerificationStatus;
  allBranches?: boolean;
  blockedReason?: string;
  branches: BranchOption[];
}

export interface LoginPayload extends Partial<AuthPayload> {
  context?: AccessContext | null;
  selectionRequired?: boolean;
  selectionToken?: string;
  schools?: SchoolOption[];
}

export interface Plan {
  id: string;
  code: string;
  name: string;
  description?: string;
  priceMonthly?: number;
  priceYearly?: number;
  currency?: string;
  maxActiveUsers: number;
  storageLimitMb: number;
  ramBudgetMb: number;
  maxBranches: number;
  features?: string[];
  resourcePolicy?: string;
  isPublic?: boolean;
  isActive?: boolean;
  isDefault?: boolean;
  sortOrder?: number;
  schools?: number;
}

export interface Meter {
  used: number;
  limit: number;
  remaining: number;
  percent: number;
  over: boolean;
  full: boolean;
}

export interface Resources {
  limits: {
    planCode: string;
    planName: string;
    maxActiveUsers: number;
    storageLimitMb: number;
    ramBudgetMb: number;
    maxBranches: number;
    resourcePolicy?: string;
    planExpired?: boolean;
    hasOverride?: boolean;
    subscribedPlanCode?: string;
    subscribedPlanName?: string;
    expiresAt?: string;
  };
  usage: {
    activeUsers: number;
    totalUsers: number;
    branches: number;
    students: number;
    storageBytes: number;
    measuredAt?: string;
  };
  users: Meter;
  branches: Meter;
  storage: Meter;
}

export interface PlatformContact {
  email?: string;
  phone?: string;
  whatsapp?: string;
  address?: string;
  hours?: string;
}

export interface NavLinkItem {
  to: string;
  label: string;
}

export interface PlatformSite {
  branding: {
    name: string;
    shortName?: string;
    tagline?: string;
    logoUrl?: string;
    primaryColor?: string;
    accentColor?: string;
    theme?: string;
    layout?: string;
  };
  homepage: {
    hero: { eyebrow?: string; title?: string; subtitle?: string; primaryCta?: string; secondaryCta?: string; imageUrl?: string };
    features?: { title: string; body?: string; icon?: string }[];
    steps?: { title: string; body?: string }[];
    about?: { title?: string; body?: string };
  };
  navigation: NavLinkItem[];
  footer: { text?: string; links?: NavLinkItem[] };
  contact: PlatformContact;
  announcement: { enabled?: boolean; text?: string; tone?: string; linkTo?: string; linkLabel?: string };
  registration: { open?: boolean; schoolTypes?: string[]; documentTypes?: string[]; minimumDocuments?: number; closedMessage?: string };
  plans: Plan[];
}

export interface VerificationDocument {
  id: string;
  documentType: string;
  fileName: string;
  contentType?: string;
  sizeBytes?: number;
  status: string;
  reviewNote?: string;
  uploadedAt?: string;
}

export interface SchoolApplication {
  id: string;
  schoolName: string;
  hasLogo?: boolean;
  address?: string;
  city?: string;
  country?: string;
  contactNumber?: string;
  officialEmail?: string;
  principalName?: string;
  schoolType?: string;
  registrationDetails?: string;
  registrationNumber?: string;
  status: VerificationStatus;
  documentsRequested?: boolean;
  reviewNote?: string;
  rejectionReason?: string;
  suspensionReason?: string;
  submittedAt?: string;
  reviewedAt?: string;
}

export interface ApplicationView {
  tenantId: string;
  schoolName: string;
  code: string;
  verificationStatus: VerificationStatus;
  status: string;
  registeredAt?: string;
  application?: SchoolApplication | null;
  documents: VerificationDocument[];
  canEdit?: boolean;
}

export interface SchoolOverview {
  school: {
    id: string;
    name: string;
    code: string;
    status: string;
    verificationStatus: VerificationStatus;
    verifiedAt?: string;
    rejectionReason?: string;
    suspensionReason?: string;
    reviewNote?: string;
    documentsRequested?: boolean;
  };
  role: SaasRole;
  resources: Resources;
  plans: Plan[];
  contact: PlatformContact;
}

export interface Branch {
  id: string;
  name: string;
  code: string;
  address?: string;
  city?: string;
  phone?: string;
  email?: string;
  status: "ACTIVE" | "SUSPENDED" | "ARCHIVED";
  main: boolean;
  permissions?: Record<string, boolean>;
  createdAt?: string;
  hasPassword?: boolean;
  users?: number;
  students?: number;
  adminId?: string;
  adminName?: string;
  adminUsername?: string;
}

export interface StaffOption {
  id: string;
  username: string;
  name: string;
  role: SaasRole;
  erpRole: string;
  branchName?: string;
}

export interface PlatformSchoolRow {
  id: string;
  code: string;
  name: string;
  city?: string;
  email?: string;
  phone?: string;
  schoolType?: string;
  status: string;
  verificationStatus: VerificationStatus;
  registeredAt?: string;
  submittedAt?: string;
  documentsRequested?: boolean;
  documents?: number;
  ownerName?: string;
  ownerEmail?: string;
  planCode?: string;
  planName?: string;
  hasOverride?: boolean;
  planExpired?: boolean;
  maxActiveUsers?: number;
  storageLimitMb?: number;
  ramBudgetMb?: number;
  maxBranches?: number;
  activeUsers?: number;
  totalUsers?: number;
  branches?: number;
  students?: number;
  storageBytes?: number;
  recentRequests?: number;
  estimatedRamMb?: number;
}

export interface PlatformSchoolDetail extends ApplicationView {
  profile: {
    email?: string;
    phone?: string;
    address?: string;
    city?: string;
    country?: string;
    schoolType?: string;
    principalName?: string;
    slug?: string;
    ownerId?: string;
    ownerName?: string;
    ownerEmail?: string;
    ownerUsername?: string;
    ownerLastLoginAt?: string;
  };
  resources: Resources;
  subscriptions: { id: string; planCode: string; planName: string; status: string; billingCycle?: string; startedAt?: string; endedAt?: string; expiresAt?: string; notes?: string }[];
  branches: Branch[];
  recentRequests?: number;
}

export interface PlatformOverview {
  schools: { total: number; pending: number; underReview: number; verified: number; rejected: number; suspended: number };
  totals: {
    activeUsers: number;
    branches: number;
    students: number;
    storageBytes: number;
    ramBudgetAllocatedMb: number;
    storageAllocatedMb: number;
    databaseBytes?: number;
  };
  plans: { code: string; name: string; schools: number }[];
  server: {
    heapUsedMb: number;
    heapMaxMb: number;
    processors: number;
    uptimeSeconds: number;
    systemRamTotalMb?: number;
    systemRamUsedMb?: number;
    cpuPercent?: number;
    diskTotalMb?: number;
    diskUsedMb?: number;
  };
  thresholds: { cpuWarnPercent: number; ramWarnPercent: number; diskWarnPercent: number; nearLimitPercent: number };
  recentRequests: number;
  nearLimit: {
    id: string;
    name: string;
    activeUsers: number;
    maxActiveUsers: number;
    branches: number;
    maxBranches: number;
    storageBytes: number;
    storageLimitMb: number;
  }[];
}

export type ReviewAction = "UNDER_REVIEW" | "REQUEST_DOCUMENTS" | "APPROVE" | "REJECT" | "SUSPEND" | "REACTIVATE";

export const accessApi = {
  login: (username: string, password: string) => api.post<LoginPayload>(`${v1}/auth/login`, { username, password }),
  select: (selectionToken: string, tenantId: string, branchId?: string | null, branchPassword?: string) =>
    api.post<LoginPayload>(`${v1}/auth/select`, { selectionToken, tenantId, branchId: branchId || undefined, branchPassword: branchPassword || undefined }),
  switchTo: (tenantId: string, branchId?: string | null, branchPassword?: string) =>
    api.post<LoginPayload>(`${v1}/auth/switch`, { tenantId, branchId: branchId || undefined, branchPassword: branchPassword || undefined }),
  contexts: () => api.get<SchoolOption[]>(`${v1}/auth/contexts`),
};

export const platformPublicApi = {
  site: () => api.get<PlatformSite>(`${v1}/public/site`),
  register: (body: FormData) =>
    api.form<{ schoolName: string; loginEmail: string; username: string; verificationStatus: VerificationStatus }>(`${v1}/public/register`, body),
};

export const schoolAccountApi = {
  overview: () => api.get<SchoolOverview>(`${v1}/school/overview`),
  application: () => api.get<ApplicationView>(`${v1}/school/application`),
  saveApplication: (body: Record<string, unknown>) => api.put<ApplicationView>(`${v1}/school/application`, body),
  resubmit: () => api.post<ApplicationView>(`${v1}/school/application/resubmit`),
  addDocument: (file: File, documentType: string) => {
    const fd = new FormData();
    fd.append("file", file);
    fd.append("documentType", documentType);
    return api.form<ApplicationView>(`${v1}/school/application/documents`, fd);
  },
  removeDocument: (id: string) => api.del<ApplicationView>(`${v1}/school/application/documents/${id}`),
  documentUrl: (id: string) => `${v1}/school/application/documents/${id}`,
};

export const branchApi = {
  list: () => api.get<Branch[]>(`${v1}/branches`),
  create: (body: Record<string, unknown>) => api.post<Branch>(`${v1}/branches`, body),
  update: (id: string, body: Record<string, unknown>) => api.put<Branch>(`${v1}/branches/${id}`, body),
  setStatus: (id: string, status: Branch["status"]) => api.post<Branch>(`${v1}/branches/${id}/status`, { status }),
  setAdmin: (id: string, body: Record<string, unknown>) =>
    api.put<{ branch?: Branch; username?: string; temporaryPassword?: string; name?: string }>(`${v1}/branches/${id}/admin`, body),
  setPassword: (id: string, password: string) => api.put<Branch>(`${v1}/branches/${id}/password`, { password }),
  setPermissions: (id: string, permissions: Record<string, boolean>) => api.put<Branch>(`${v1}/branches/${id}/permissions`, permissions),
  members: (id: string) => api.get<StaffOption[]>(`${v1}/branches/${id}/members`),
  addMember: (id: string, userId: string) => api.post<StaffOption[]>(`${v1}/branches/${id}/members`, { userId }),
  removeMember: (id: string, userId: string) => api.del<StaffOption[]>(`${v1}/branches/${id}/members/${userId}`),
  staff: () => api.get<StaffOption[]>(`${v1}/branches/staff`),
};

export const platformApi = {
  overview: () => api.get<PlatformOverview>(`${v1}/platform/overview`),
  schools: (status?: string, q?: string) => {
    const p = new URLSearchParams();
    if (status) p.set("status", status);
    if (q) p.set("q", q);
    const qs = p.toString();
    return api.get<PlatformSchoolRow[]>(`${v1}/platform/schools${qs ? `?${qs}` : ""}`);
  },
  school: (id: string) => api.get<PlatformSchoolDetail>(`${v1}/platform/schools/${id}`),
  review: (id: string, action: ReviewAction, note?: string) =>
    api.post<PlatformSchoolDetail>(`${v1}/platform/schools/${id}/review`, { action, note: note || undefined }),
  setLimits: (id: string, body: Record<string, unknown>) => api.put<Resources>(`${v1}/platform/schools/${id}/limits`, body),
  setPlan: (id: string, body: Record<string, unknown>) => api.put<Resources>(`${v1}/platform/schools/${id}/plan`, body),
  reviewDocument: (id: string, documentId: string, status: string, note?: string) =>
    api.post<PlatformSchoolDetail>(`${v1}/platform/schools/${id}/documents/${documentId}/review`, { status, note: note || undefined }),
  documentUrl: (id: string, documentId: string) => `${v1}/platform/schools/${id}/documents/${documentId}`,
  plans: () => api.get<Plan[]>(`${v1}/platform/plans`),
  createPlan: (body: Record<string, unknown>) => api.post<Plan>(`${v1}/platform/plans`, body),
  updatePlan: (id: string, body: Record<string, unknown>) => api.put<Plan>(`${v1}/platform/plans/${id}`, body),
  settings: () => api.get<Record<string, unknown>>(`${v1}/platform/settings`),
  saveSetting: (key: string, value: unknown) => api.put<unknown>(`${v1}/platform/settings/${key}`, value),
  uploadAsset: (file: File) => {
    const fd = new FormData();
    fd.append("file", file);
    return api.form<{ url: string }>(`${v1}/platform/assets`, fd);
  },
};

export function hasSession() {
  return Boolean(getAccessToken());
}

export function fmtBytes(bytes?: number | null) {
  const n = Number(bytes || 0);
  if (n >= 1024 ** 3) return `${(n / 1024 ** 3).toFixed(n >= 10 * 1024 ** 3 ? 0 : 1)} GB`;
  if (n >= 1024 ** 2) return `${(n / 1024 ** 2).toFixed(n >= 10 * 1024 ** 2 ? 0 : 1)} MB`;
  if (n >= 1024) return `${Math.round(n / 1024)} KB`;
  return `${n} B`;
}

export function fmtMb(mb?: number | null) {
  const n = Number(mb || 0);
  return n >= 1024 ? `${(n / 1024).toFixed(n % 1024 === 0 ? 0 : 1)} GB` : `${n} MB`;
}
