import { useState } from "react";
import { accessApi, SAAS_ROLE_LABEL, type SchoolOption } from "../api/saas";
import { useSession } from "../lib/session";
import { useToast } from "../lib/toast";
import { SchoolPicker } from "../pages/LoginPage";
import { friendlyMessage, Modal, Spinner } from "./kit";

/**
 * Shows the school and branch this session is working in. Users who are allowed
 * more than one school or branch can switch here. The list comes from the server
 * and only ever contains what this account is authorised for.
 */
export default function ContextSwitcher() {
  const { context, switchTo } = useSession();
  const toast = useToast();
  const [options, setOptions] = useState<SchoolOption[] | null>(null);
  const [busy, setBusy] = useState(false);
  if (!context || context.role === "PLATFORM_OWNER") return null;

  const where = context.allBranches ? "All branches" : context.branchName || "Branch";
  const label = (
    <>
      <span className="ctx-where">{where}</span>
      <small>{SAAS_ROLE_LABEL[context.role] || context.role}</small>
    </>
  );
  if (!context.canSwitch) return <div className="ctx" title={context.tenantName || ""}>{label}</div>;

  return (
    <>
      <button
        type="button"
        className="ctx switchable"
        title="Switch school or branch"
        disabled={busy}
        onClick={() => {
          setBusy(true);
          accessApi.contexts()
            .then(setOptions)
            .catch((e) => toast("err", friendlyMessage(e)))
            .finally(() => setBusy(false));
        }}
      >
        {label}
        {busy ? <Spinner small /> : <span aria-hidden>⇄</span>}
      </button>
      <Modal title="Switch school or branch" open={Boolean(options)} onClose={() => setOptions(null)}>
        {options ? (
          <div className="ctx-picker">
            <SchoolPicker
              pending={{ schools: options }}
              title="Choose where to work"
              cancelLabel="Cancel"
              onCancel={() => setOptions(null)}
              onChoose={async (tenantId, branchId, branchPassword) => {
                await switchTo(tenantId, branchId, branchPassword);
                // Start clean so no data from the previous school or branch stays on screen.
                window.location.assign("/app");
              }}
            />
          </div>
        ) : null}
      </Modal>
    </>
  );
}
