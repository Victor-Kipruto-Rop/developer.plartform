import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, RotateCw } from "lucide-react";

type AppErrorBoundaryProps = { children: ReactNode };
type AppErrorBoundaryState = { hasError: boolean };

export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { hasError: false };

  static getDerivedStateFromError(): AppErrorBoundaryState {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("The developer platform encountered an unexpected rendering error.", error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="app-error-screen" role="alert">
          <div className="app-error-card">
            <span className="app-error-icon"><AlertTriangle size={22} aria-hidden="true" /></span>
            <h1>Something went wrong</h1>
            <p>The page could not be displayed. Reload the platform to try again.</p>
            <button className="button primary" type="button" onClick={() => window.location.reload()}>
              Reload platform <RotateCw size={15} aria-hidden="true" />
            </button>
          </div>
        </main>
      );
    }
    return this.props.children;
  }
}
