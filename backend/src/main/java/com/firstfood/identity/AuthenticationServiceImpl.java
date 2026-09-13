package com.firstfood.identity;

import com.firstfood.identity.error.AccountSuspendedException;
import com.firstfood.identity.error.PhoneAlreadyInUseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationServiceImpl implements AuthenticationService {

    private final UserAccountRepository userAccountRepository;
    private final OtpService otpService;
    private final TokenService tokenService;
    private final RefreshSessionService refreshSessionService;

    public AuthenticationServiceImpl(
            UserAccountRepository userAccountRepository,
            OtpService otpService,
            TokenService tokenService,
            RefreshSessionService refreshSessionService) {
        this.userAccountRepository = userAccountRepository;
        this.otpService = otpService;
        this.tokenService = tokenService;
        this.refreshSessionService = refreshSessionService;
    }

    @Override
    public void requestLoginOtp(String phone) {
        otpService.requestOtp(phone, OtpPurpose.LOGIN);
    }

    @Override
    @Transactional
    public AuthTokens verifyLoginOtp(String phone, String code) {
        otpService.verifyOtp(phone, OtpPurpose.LOGIN, code);

        UserAccount account = userAccountRepository.findByPhone(phone)
                .orElseGet(() -> userAccountRepository.save(new UserAccount(phone)));

        if (account.getStatus() == AccountStatus.SUSPENDED) {
            throw new AccountSuspendedException();
        }

        return issueTokenPair(account.getId());
    }

    @Override
    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshSessionService.RotationResult rotation = refreshSessionService.rotate(rawRefreshToken);
        String accessToken = tokenService.issueAccessToken(rotation.accountId());
        return new AuthTokens(accessToken, rotation.rawRefreshToken());
    }

    @Override
    public void logout(String rawRefreshToken) {
        refreshSessionService.revoke(rawRefreshToken);
    }

    @Override
    public void requestPhoneChangeOtp(java.util.UUID accountId, String newPhone) {
        if (userAccountRepository.existsByPhone(newPhone)) {
            throw new PhoneAlreadyInUseException();
        }
        // Sent to the NEW number - proves the caller controls it before
        // the change is applied (rules.md Rule 3.3).
        otpService.requestOtp(newPhone, OtpPurpose.PHONE_CHANGE);
    }

    @Override
    @Transactional
    public void verifyPhoneChangeOtp(java.util.UUID accountId, String newPhone, String code) {
        otpService.verifyOtp(newPhone, OtpPurpose.PHONE_CHANGE, code);

        if (userAccountRepository.existsByPhone(newPhone)) {
            // Re-check post-verification: another account could have
            // claimed this number in the window between request and verify.
            throw new PhoneAlreadyInUseException();
        }

        UserAccount account = userAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Authenticated account not found: " + accountId));
        account.setPhone(newPhone);
        userAccountRepository.save(account);
    }

    private AuthTokens issueTokenPair(java.util.UUID accountId) {
        String accessToken = tokenService.issueAccessToken(accountId);
        String refreshToken = refreshSessionService.issue(accountId);
        return new AuthTokens(accessToken, refreshToken);
    }
}
