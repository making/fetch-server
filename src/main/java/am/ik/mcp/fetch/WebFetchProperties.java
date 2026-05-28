package am.ik.mcp.fetch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the fetch tools, controlling the default request timeout
 * and the maximum size read from a response body.
 */
@ConfigurationProperties(prefix = "fetch")
public record WebFetchProperties(@DefaultValue("30s") Duration defaultTimeout,
		@DefaultValue("1MB") DataSize defaultMaxSize) {
}
