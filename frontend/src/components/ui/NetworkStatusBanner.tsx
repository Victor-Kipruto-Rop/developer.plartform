import { WifiOff } from "lucide-react";
import { useNetworkStatus } from "../../context/NetworkStatusContext";

/** A persistent reminder while browser connectivity is absent. */
export function NetworkStatusBanner() {
  const { online } = useNetworkStatus();
  if (online) return null;

  return (
    <div className="network-status-banner" role="status" aria-live="polite">
      <WifiOff size={16} aria-hidden="true" />
      <span>You’re offline. Changes will not be saved until your connection is restored.</span>
    </div>
  );
}
