import { Sun } from "lucide-react";
import { PageHeader } from "../../components/ui/PageHeader";

export function AppearanceSettingsPage() {
  return (
    <>
      <PageHeader
        eyebrow="PERSONAL PREFERENCES"
        title="Appearance"
        description="The developer platform uses a consistent light appearance across devices and sessions."
      />
      <section className="panel appearance-settings-panel" aria-labelledby="appearance-choice-title">
        <div className="panel-heading">
          <div>
            <h2 id="appearance-choice-title">Light theme</h2>
            <p>Always enabled. Device and browser appearance preferences do not change the platform theme.</p>
          </div>
        </div>
        <div className="appearance-settings-footer">
          <p role="status"><Sun size={16} aria-hidden="true" /> Light theme is enforced across the developer platform.</p>
        </div>
      </section>
    </>
  );
}
