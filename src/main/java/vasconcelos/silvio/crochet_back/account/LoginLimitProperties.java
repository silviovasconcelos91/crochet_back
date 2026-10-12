package vasconcelos.silvio.crochet_back.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxFailuresPerEmail failed logins after which an email is blocked until its window ends
 * @param emailWindow         window counted from an email's first failure
 * @param maxRequestsPerIp    login and register requests one address may send per window
 * @param ipWindow            window counted from an address's first request
 */
@ConfigurationProperties("crochet.login-limit")
public record LoginLimitProperties(
        int maxFailuresPerEmail, Duration emailWindow, int maxRequestsPerIp, Duration ipWindow) {
}
