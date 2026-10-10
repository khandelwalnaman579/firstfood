import { apiFetch } from "./api-client";

export type AuthTokens = {
  accessToken: string;
  refreshToken: string;
};

export type Profile = {
  accountId: string;
  phone: string;
  email: string | null;
  status: "ACTIVE" | "SUSPENDED";
  createdAt: string;
};

/**
 * One function per backend endpoint (com.firstfood.identity.web).
 * Deliberately thin - no token storage, no state, no retry logic. That
 * belongs in the component/hook that calls these, not here.
 */
export const authApi = {
  requestOtp(phone: string): Promise<void> {
    return apiFetch<void>("/auth/otp/request", { method: "POST", body: { phone } });
  },

  verifyOtp(phone: string, code: string): Promise<AuthTokens> {
    return apiFetch<AuthTokens>("/auth/otp/verify", { method: "POST", body: { phone, code } });
  },

  refresh(refreshToken: string): Promise<AuthTokens> {
    return apiFetch<AuthTokens>("/auth/refresh", { method: "POST", body: { refreshToken } });
  },

  logout(refreshToken: string): Promise<void> {
    return apiFetch<void>("/auth/logout", { method: "POST", body: { refreshToken } });
  },

  getProfile(accessToken: string): Promise<Profile> {
    return apiFetch<Profile>("/me", { accessToken });
  },

  updateEmail(accessToken: string, email: string): Promise<Profile> {
    return apiFetch<Profile>("/me/email", { method: "PATCH", accessToken, body: { email } });
  },

  requestPhoneChange(accessToken: string, newPhone: string): Promise<void> {
    return apiFetch<void>("/me/phone/otp/request", { method: "POST", accessToken, body: { newPhone } });
  },

  verifyPhoneChange(accessToken: string, newPhone: string, code: string): Promise<Profile> {
    return apiFetch<Profile>("/me/phone/otp/verify", {
      method: "POST",
      accessToken,
      body: { newPhone, code },
    });
  },
};
