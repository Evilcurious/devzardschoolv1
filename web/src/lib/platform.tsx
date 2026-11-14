import { useEffect, useState } from "react";
import { platformPublicApi, type PlatformSite } from "../api/saas";
import { applyBrand } from "./theme";

let cached: PlatformSite | null = null;
let inflight: Promise<PlatformSite> | null = null;

/** Drop the cached copy after the Platform Owner edits the website. */
export function resetPlatformSite() {
  cached = null;
  inflight = null;
}

/**
 * Public platform website content (branding, homepage, plans, contact).
 * Everything here is edited by the Platform Owner; nothing is hard-coded per school.
 */
export function usePlatformSite(brand = true) {
  const [site, setSite] = useState<PlatformSite | null>(cached);
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let live = true;
    if (!cached) {
      inflight = inflight || platformPublicApi.site();
      inflight
        .then((data) => {
          cached = data;
          if (live) setSite(data);
        })
        .catch((e) => {
          inflight = null;
          if (live) setError(e);
        });
    }
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    if (!site || !brand) return;
    applyBrand(site.branding.primaryColor, site.branding.accentColor);
    document.title = site.branding.name || "DEVZARD eSchool";
  }, [site, brand]);

  return { site, error };
}
