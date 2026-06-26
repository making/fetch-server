package am.ik.mcp.fetch.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web configuration for the playground feature. Registers the Basic-auth interceptor for
 * the playground routes only ({@code /} and {@code /playground}); everything else,
 * including the MCP endpoint at {@code /mcp}, stays open.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaygroundAuthProperties.class)
public class PlaygroundWebConfig implements WebMvcConfigurer {

	private final PlaygroundAuthProperties properties;

	public PlaygroundWebConfig(PlaygroundAuthProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new PlaygroundBasicAuthInterceptor(this.properties))
			.addPathPatterns("/", "/playground");
	}

}
