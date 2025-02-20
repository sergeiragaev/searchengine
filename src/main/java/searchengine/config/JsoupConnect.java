package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "jsoup-setting")
public class JsoupConnect {
    String  userAgent;
    String referrer;
    int timeout;
    boolean ignoreHttpErrors;
    boolean followRedirects;
}
