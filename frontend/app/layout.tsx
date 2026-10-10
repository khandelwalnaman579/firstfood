import type { Metadata } from "next";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "FirstFood",
  description: "Food whenever you need it.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="ff-header">
          <span className="ff-wordmark">FirstFood</span>
        </header>
        <main className="ff-main">{children}</main>
      </body>
    </html>
  );
}
