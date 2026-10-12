package vasconcelos.silvio.crochet_back.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;
import vasconcelos.silvio.crochet_back.account.LoginLimitProperties;

@Configuration
@EnableConfigurationProperties(LoginLimitProperties.class)
public class SecurityConfig {

    /**
     * 401 and 403 are delegated to the MVC exception resolver so that ApiExceptionHandler renders
     * them as ProblemDetail like every other error.
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository,
            LogoutSuccessHandler logoutSuccessHandler,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // an API has no login page to return to: without this, every anonymous 401 would
                // create a session just to remember the request
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(logoutSuccessHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex))
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)));
        return http.build();
    }

    /**
     * The XSRF-TOKEN cookie follows the session cookie's Secure flag; by default it would follow
     * request.isSecure(), which is false behind a TLS-terminating proxy that sends no X-Forwarded-Proto.
     */
    @Bean
    CsrfTokenRepository csrfTokenRepository(ServerProperties serverProperties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        Boolean secure = serverProperties.getServlet().getSession().getCookie().getSecure();
        if (secure != null) {
            repository.setCookieCustomizer(cookie -> cookie.secure(secure));
        }
        return repository;
    }

    /**
     * Logout clears the XSRF-TOKEN cookie (CsrfLogoutHandler); issue a fresh one in the same
     * response so the SPA can log in again without calling GET /api/auth/csrf.
     */
    @Bean
    LogoutSuccessHandler logoutSuccessHandler(CsrfTokenRepository csrfTokenRepository) {
        LogoutSuccessHandler noContent = new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT);
        return (request, response, authentication) -> {
            CsrfToken token = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(token, request, response);
            noContent.onLogoutSuccess(request, response, authentication);
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
