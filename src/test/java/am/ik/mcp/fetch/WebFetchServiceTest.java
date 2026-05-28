package am.ik.mcp.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

class WebFetchServiceTest {

	private HttpServer server;

	private String baseUrl;

	private WebFetchService service;

	@BeforeEach
	void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		this.baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
		executor.setVirtualThreads(true);
		this.service = new WebFetchService(RestClient.builder(),
				new WebFetchProperties(Duration.ofSeconds(30), DataSize.ofMegabytes(1)), executor);
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	@Test
	void shouldReturnRawBodyForOkResponse() {
		registerHandler("/hello", textHandler("Hello, world", StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResult result = fetchOne(this.baseUrl + "/hello", false);

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.contentType()).startsWith("text/plain");
		assertThat(result.truncated()).isFalse();
		assertThat(result.error()).isNull();
		assertThat(result.content()).isEqualToNormalizingWhitespace("""
				Hello, world
				""");
	}

	@Test
	void shouldPropagateNon200StatusCode() {
		registerHandler("/missing", textHandler("not found", StandardCharsets.UTF_8, 404));

		WebFetchService.FetchResult result = fetchOne(this.baseUrl + "/missing", false);

		assertThat(result.status()).isEqualTo(404);
		assertThat(result.content()).isEqualToNormalizingWhitespace("""
				not found
				""");
	}

	@Test
	void shouldTruncateBodyWhenExceedingMaxBytes() {
		String hundredBytes = "x".repeat(100);
		registerHandler("/big", textHandler(hundredBytes, StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResponse response = this.service.fetch(List.of(this.baseUrl + "/big"), false, null, null,
				20);
		WebFetchService.FetchResult result = response.results().get(0);

		assertThat(result.truncated()).isTrue();
		assertThat(result.content()).hasSize(20).isEqualTo("x".repeat(20));
	}

	@Test
	void shouldApplyCustomRequestHeaders() {
		registerHandler("/echo", exchange -> {
			String received = exchange.getRequestHeaders().getFirst("X-Test-Header");
			byte[] body = (received != null ? received : "").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});

		WebFetchService.FetchResponse response = this.service.fetch(List.of(this.baseUrl + "/echo"), false,
				Map.of("X-Test-Header", "abc123"), null, null);

		assertThat(response.results().get(0).content()).isEqualToNormalizingWhitespace("""
				abc123
				""");
	}

	@Test
	void shouldReturnErrorResultWhenTimeoutExceeded() {
		registerHandler("/slow", exchange -> {
			try {
				Thread.sleep(2000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] body = "late".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});

		WebFetchService.FetchResponse response = this.service.fetch(List.of(this.baseUrl + "/slow"), false, null, 1,
				null);
		WebFetchService.FetchResult result = response.results().get(0);

		assertThat(result.status()).isZero();
		assertThat(result.error()).isNotNull();
		assertThat(result.content()).isEmpty();
	}

	@Test
	void shouldDecodeBodyUsingContentTypeCharset() {
		Charset sjis = Charset.forName("Shift_JIS");
		registerHandler("/sjis", exchange -> {
			byte[] body = "こんにちは".getBytes(sjis);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=Shift_JIS");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});

		WebFetchService.FetchResult result = fetchOne(this.baseUrl + "/sjis", false);

		assertThat(result.content()).isEqualToNormalizingWhitespace("""
				こんにちは
				""");
	}

	@Test
	void shouldConvertHtmlToMarkdownByDefault() {
		registerHandler("/page", htmlHandler("""
				<html>
				  <head><title>Sample</title></head>
				  <body>
				    <h1>Heading</h1>
				    <p>Hello world</p>
				  </body>
				</html>
				"""));

		WebFetchService.FetchResponse response = this.service.fetch(List.of(this.baseUrl + "/page"), null, null, null,
				null);
		WebFetchService.FetchResult result = response.results().get(0);

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.title()).isEqualTo("Sample");
		assertThat(result.content()).isEqualToNormalizingWhitespace("""
				# Heading

				Hello world
				""");
	}

	@Test
	void shouldFetchMultipleUrlsPreservingOrder() {
		registerHandler("/one", textHandler("first", StandardCharsets.UTF_8, 200));
		registerHandler("/two", textHandler("second", StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResponse response = this.service
			.fetch(List.of(this.baseUrl + "/one", this.baseUrl + "/two"), false, null, null, null);

		assertThat(response.results()).hasSize(2);
		assertThat(response.results().get(0).url()).endsWith("/one");
		assertThat(response.results().get(0).content()).isEqualToNormalizingWhitespace("""
				first
				""");
		assertThat(response.results().get(1).url()).endsWith("/two");
		assertThat(response.results().get(1).content()).isEqualToNormalizingWhitespace("""
				second
				""");
	}

	@Test
	void shouldReturnPartialResultsWhenOneUrlFails() throws IOException {
		registerHandler("/ok", textHandler("ok", StandardCharsets.UTF_8, 200));
		String unreachableUrl = reserveClosedPortUrl() + "/down";

		WebFetchService.FetchResponse response = this.service.fetch(List.of(this.baseUrl + "/ok", unreachableUrl),
				false, null, null, null);

		assertThat(response.results()).hasSize(2);
		WebFetchService.FetchResult ok = response.results().get(0);
		assertThat(ok.status()).isEqualTo(200);
		assertThat(ok.error()).isNull();
		assertThat(ok.content()).isEqualToNormalizingWhitespace("""
				ok
				""");
		WebFetchService.FetchResult failed = response.results().get(1);
		assertThat(failed.url()).isEqualTo(unreachableUrl);
		assertThat(failed.status()).isZero();
		assertThat(failed.error()).isNotNull();
	}

	@Test
	void shouldUseDefaultsWhenOptionalParamsAreNull() {
		registerHandler("/default", textHandler("ok", StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResult result = fetchOne(this.baseUrl + "/default", false);

		assertThat(result.status()).isEqualTo(200);
		assertThat(result.truncated()).isFalse();
		assertThat(result.content()).isEqualToNormalizingWhitespace("""
				ok
				""");
	}

	private WebFetchService.FetchResult fetchOne(String url, boolean markdown) {
		return this.service.fetch(List.of(url), markdown, null, null, null).results().get(0);
	}

	private void registerHandler(String path, HttpHandler handler) {
		this.server.createContext(path, handler);
	}

	private static String reserveClosedPortUrl() throws IOException {
		// Bind then immediately release a loopback port so a later connection is refused
		// fast, rather than stalling until the read timeout.
		try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			return "http://127.0.0.1:" + socket.getLocalPort();
		}
	}

	private static HttpHandler textHandler(String body, Charset charset, int status) {
		return exchange -> {
			byte[] payload = body.getBytes(charset);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=" + charset.name());
			exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		};
	}

	private static HttpHandler htmlHandler(String html) {
		return exchange -> {
			byte[] payload = html.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		};
	}

}
