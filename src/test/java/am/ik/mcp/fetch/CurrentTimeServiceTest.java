package am.ik.mcp.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import am.ik.mcp.fetch.CurrentTimeService.CurrentTimeResponse;

class CurrentTimeServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-05-29T05:30:00Z");

	private final CurrentTimeService service = new CurrentTimeService(InstantSource.fixed(FIXED_INSTANT));

	@Test
	void shouldReturnTimeInRequestedTimezone() {
		CurrentTimeResponse response = this.service.currentTime("Asia/Tokyo");

		assertThat(response.dateTime()).isEqualTo("2026-05-29T14:30:00+09:00");
	}

	@Test
	void shouldReturnTimeInAnotherRequestedTimezone() {
		CurrentTimeResponse response = this.service.currentTime("America/New_York");

		assertThat(response.dateTime()).isEqualTo("2026-05-29T01:30:00-04:00");
	}

	@Test
	void shouldFallBackToSystemDefaultTimezoneWhenOmitted() {
		CurrentTimeResponse response = this.service.currentTime(null);

		// The default zone is environment-dependent, so compute the expected value the
		// same way for the fixed instant rather than hard-coding it.
		String expected = FIXED_INSTANT.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		assertThat(response.dateTime()).isEqualTo(expected);
	}

	@Test
	void shouldRejectUnknownTimezone() {
		assertThatThrownBy(() -> this.service.currentTime("Not/AZone")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown timezone: Not/AZone");
	}

}
