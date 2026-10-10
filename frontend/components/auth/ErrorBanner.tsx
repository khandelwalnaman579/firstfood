export function ErrorBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return <div className="ff-error">{message}</div>;
}
