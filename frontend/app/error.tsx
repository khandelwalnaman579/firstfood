"use client";

import { useEffect } from "react";

/**
 * App-level error boundary. Deliberately does not render error.message
 * to the user (FirstFood_V2_Phase1_Review.md #13) - a backend or
 * framework error could contain internal details (e.g. a database
 * connection string fragment) that shouldn't reach the browser. Full
 * detail goes to console/error-tracking only.
 */
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // Replace with real error-tracking (Sentry, etc.) once one is wired up.
    console.error(error);
  }, [error]);

  return (
    <div>
      <h2>Something went wrong</h2>
      <p>Please try again. If the problem continues, contact support.</p>
      <button onClick={reset}>Try again</button>
    </div>
  );
}