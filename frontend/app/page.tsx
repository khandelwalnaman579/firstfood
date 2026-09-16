"use client";

import { useState } from "react";
import { authApi, type Profile } from "@/lib/auth-api";
import { PhoneStep } from "@/components/auth/PhoneStep";
import { OtpStep } from "@/components/auth/OtpStep";
import { AuthenticatedView } from "@/components/auth/AuthenticatedView";

type Step = "phone" | "otp" | "authenticated";

/**
 * Minimal Phase 2 auth flow UI (FirstFood_V2_Phase2_Final_Review.md
 * #21/#22): phone -> OTP -> authenticated screen with refresh/logout and
 * the email/phone-change forms. Deliberately plain - this proves the
 * backend flow end-to-end from a browser, it isn't the pilot's final
 * customer-facing design.
 *
 * Token storage: both tokens live only in this component's state, never
 * localStorage/sessionStorage (review #23) - lost on refresh by design
 * for Phase 2. See AuthenticatedView's on-screen note and README/
 * memory.md for the production-hardening plan (HttpOnly refresh cookie).
 */
export default function HomePage() {
  const [step, setStep] = useState<Step>("phone");
  const [phone, setPhone] = useState("");
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState<string | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);

  async function handleOtpRequested(enteredPhone: string) {
    await authApi.requestOtp(enteredPhone);
    setPhone(enteredPhone);
    setStep("otp");
  }

  async function handleVerify(code: string) {
    const tokens = await authApi.verifyOtp(phone, code);
    const fetchedProfile = await authApi.getProfile(tokens.accessToken);
    setAccessToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setProfile(fetchedProfile);
    setStep("authenticated");
  }

  function reset() {
    setAccessToken(null);
    setRefreshToken(null);
    setProfile(null);
    setStep("phone");
  }

  async function handleRefresh() {
    if (!refreshToken) return;
    const tokens = await authApi.refresh(refreshToken);
    const fetchedProfile = await authApi.getProfile(tokens.accessToken);
    setAccessToken(tokens.accessToken);
    setRefreshToken(tokens.refreshToken);
    setProfile(fetchedProfile);
  }

  async function handleLogout() {
    if (refreshToken) {
      await authApi.logout(refreshToken);
    }
    reset();
  }

  async function handleUpdateEmail(email: string) {
    if (!accessToken) return;
    const updated = await authApi.updateEmail(accessToken, email);
    setProfile(updated);
  }

  async function handleRequestPhoneChange(newPhone: string) {
    if (!accessToken) return;
    await authApi.requestPhoneChange(accessToken, newPhone);
  }

  async function handleVerifyPhoneChange(newPhone: string, code: string) {
    if (!accessToken) return;
    const updated = await authApi.verifyPhoneChange(accessToken, newPhone, code);
    setProfile(updated);
  }

  if (step === "authenticated" && profile) {
    return (
      <AuthenticatedView
        profile={profile}
        onRefresh={handleRefresh}
        onLogout={handleLogout}
        onUpdateEmail={handleUpdateEmail}
        onRequestPhoneChange={handleRequestPhoneChange}
        onVerifyPhoneChange={handleVerifyPhoneChange}
      />
    );
  }

  if (step === "otp") {
    return <OtpStep phone={phone} onVerify={handleVerify} onBack={() => setStep("phone")} />;
  }

  return <PhoneStep onOtpRequested={handleOtpRequested} />;
}
