package am.ik.mcp.fetch;

import java.time.InstantSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebFetchProperties.class)
public class AppConfig {

	@Bean
	public RestClientCustomizer restClientCustomizer(Logbook logbook) {
		return builder -> builder.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook));
	}

	@Bean
	public InstantSource instantSource() {
		return InstantSource.system();
	}

}
