package am.ik.mcp.fetch.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties for the simple HTTP Basic authentication that guards the
 * playground UI ({@code /} and {@code /playground}). Authentication can be turned off
 * with {@code playground.auth.enabled=false}, which is convenient for tests and local
 * use.
 */
@ConfigurationProperties(prefix = "playground.auth")
public record PlaygroundAuthProperties(@DefaultValue("true") boolean enabled, @DefaultValue("admin") String username,
		@DefaultValue("fetch-server") String password, @DefaultValue("fetch-inspector") String realm) {
}
