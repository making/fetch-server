package am.ik.mcp.fetch;

import java.time.DateTimeException;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool service that returns the current date-time. The timezone can be supplied as an
 * IANA zone ID; when omitted, the server's default timezone is used. The rendered value is
 * ISO-8601 with an offset, so the zone can be inferred from the offset alone.
 */
@Service
public class CurrentTimeService {

	private final InstantSource instantSource;

	public CurrentTimeService(InstantSource instantSource) {
		this.instantSource = instantSource;
	}

	/**
	 * Current date-time formatted as ISO-8601 with an offset, e.g.
	 * {@code 2026-05-29T14:30:00+09:00}.
	 */
	public record CurrentTimeResponse(String dateTime) {
	}

	@McpTool(name = "current_datetime",
			description = "Return the current date-time, optionally in the given IANA timezone")
	public CurrentTimeResponse currentTime(
			@ToolParam(description = "IANA timezone ID (e.g. Asia/Tokyo). Defaults to the server's default timezone",
					required = false) @Nullable String timezone) {
		ZoneId zone = resolveZone(timezone);
		ZonedDateTime now = this.instantSource.instant().atZone(zone);
		return new CurrentTimeResponse(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
	}

	private static ZoneId resolveZone(@Nullable String timezone) {
		if (timezone == null) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(timezone);
		}
		catch (DateTimeException ex) {
			throw new IllegalArgumentException("Unknown timezone: " + timezone, ex);
		}
	}

}
