package vasconcelos.silvio.crochet_back.config;

import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Applies {@code server.servlet.session.cookie.*} to the Spring Session cookie. Spring Boot only does
 * this when running in an embedded server, so without this bean the attributes would silently differ
 * under MockMvc and the cookie could not be verified by tests.
 */
@Configuration
public class SessionCookieConfig {

    @Bean
    DefaultCookieSerializer cookieSerializer(ServerProperties serverProperties) {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        if (cookie.getHttpOnly() != null) {
            serializer.setUseHttpOnlyCookie(cookie.getHttpOnly());
        }
        if (cookie.getSecure() != null) {
            serializer.setUseSecureCookie(cookie.getSecure());
        }
        if (cookie.getSameSite() != null) {
            serializer.setSameSite(cookie.getSameSite().attributeValue());
        }
        return serializer;
    }
}
