/**
 * Centralized, validated access to environment configuration.
 * Never read process.env directly elsewhere - import from here so a
 * missing variable fails fast instead of surfacing as a confusing bug
 * deep in a component (phases.md Phase 1 "environment configuration").
 */
function required(name: string, value: string | undefined): string {
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

export const env = {
  apiBaseUrl: required("NEXT_PUBLIC_API_BASE_URL", process.env.NEXT_PUBLIC_API_BASE_URL),
};
