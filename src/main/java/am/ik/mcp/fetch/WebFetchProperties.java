package am.ik.mcp.fetch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the fetch tools, controlling the default User-Agent, the
 * default request timeout and the maximum size read from a response body.
 */
@ConfigurationProperties(prefix = "fetch")
public record WebFetchProperties(
		@DefaultValue("fetch-server (+https://github.com/making/fetch-server)") String userAgent,
		@DefaultValue("30s") Duration defaultTimeout,
		@DefaultValue("10MB") DataSize defaultMaxSize) {
}
