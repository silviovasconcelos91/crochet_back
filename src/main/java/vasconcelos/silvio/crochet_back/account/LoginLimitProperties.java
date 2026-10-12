package vasconcelos.silvio.crochet_back.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxFailuresPerEmailAndClient failed password checks after which one client address is
 *                                     refused for an email until the window ends
 * @param maxFailuresPerEmail          failed password checks after which every client is refused for
 *                                     an email; higher, so one attacker cannot lock the owner out
 * @param emailWindow                  window counted from the first failure
 * @param maxRequestsPerIp             login and register requests one address may send per window
 * @param ipWindow                     window counted from an address's first request
 */
@ConfigurationProperties("crochet.login-limit")
public record LoginLimitProperties(
        int maxFailuresPerEmailAndClient,
        int maxFailuresPerEmail,
        Duration emailWindow,
        int maxRequestsPerIp,
        Duration ipWindow) {
}
