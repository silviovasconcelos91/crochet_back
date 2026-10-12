package vasconcelos.silvio.crochet_back.config;

import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Applies {@code server.servlet.session.cookie.*} to the Spring Session cookie. Spring Boot only does
 * this when running in an embedded server, so without this bean the attributes would silently differ
 * under MockMvc and the cookie could not be verified by tests. Unset properties keep Spring Session's
 * defaults.
 */
@Configuration
public class SessionCookieConfig {

    @Bean
    DefaultCookieSerializer cookieSerializer(ServerProperties serverProperties) {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        PropertyMapper map = PropertyMapper.get();
        map.from(cookie::getName).to(serializer::setCookieName);
        map.from(cookie::getDomain).to(serializer::setDomainName);
        map.from(cookie::getPath).to(serializer::setCookiePath);
        map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
        map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
        map.from(cookie::getMaxAge).as(maxAge -> (int) maxAge.toSeconds()).to(serializer::setCookieMaxAge);
        map.from(cookie::getSameSite).as(Cookie.SameSite::attributeValue).to(serializer::setSameSite);
        map.from(cookie::getPartitioned).to(serializer::setPartitioned);
        return serializer;
    }
}
