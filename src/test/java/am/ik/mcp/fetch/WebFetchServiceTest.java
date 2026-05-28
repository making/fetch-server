package am.ik.mcp.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.ResourceAccessException;
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
		this.service = new WebFetchService(RestClient.builder());
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	@Test
	void shouldReturnRawBodyForOkResponse() {
		registerHandler("/hello", textHandler("Hello, world", StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/hello", null, null, null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.contentType()).startsWith("text/plain");
		assertThat(response.truncated()).isFalse();
		assertThat(response.body()).isEqualToNormalizingWhitespace("""
				Hello, world
				""");
	}

	@Test
	void shouldPropagateNon200StatusCode() {
		registerHandler("/missing", textHandler("not found", StandardCharsets.UTF_8, 404));

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/missing", null, null, null);

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.body()).isEqualToNormalizingWhitespace("""
				not found
				""");
	}

	@Test
	void shouldTruncateBodyWhenExceedingMaxBytes() {
		String hundredBytes = "x".repeat(100);
		registerHandler("/big", textHandler(hundredBytes, StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/big", null, null, 20);

		assertThat(response.truncated()).isTrue();
		assertThat(response.body()).hasSize(20).isEqualTo("x".repeat(20));
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

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/echo",
				Map.of("X-Test-Header", "abc123"), null, null);

		assertThat(response.body()).isEqualToNormalizingWhitespace("""
				abc123
				""");
	}

	@Test
	void shouldRespectTimeout() {
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

		assertThatThrownBy(() -> this.service.fetch(this.baseUrl + "/slow", null, 1, null))
			.isInstanceOf(ResourceAccessException.class);
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

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/sjis", null, null, null);

		assertThat(response.body()).isEqualToNormalizingWhitespace("""
				こんにちは
				""");
	}

	@Test
	void shouldConvertHtmlToMarkdown() {
		String html = """
				<html>
				  <head><title>Sample</title></head>
				  <body>
				    <h1>Heading</h1>
				    <p>Hello world</p>
				  </body>
				</html>
				""";
		registerHandler("/page", htmlHandler(html));

		WebFetchService.MarkdownResponse response = this.service.fetchAsMarkdown(this.baseUrl + "/page", null, null,
				null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.title()).isEqualTo("Sample");
		assertThat(response.markdown()).isEqualToNormalizingWhitespace("""
				# Heading

				Hello world
				""");
	}

	@Test
	void shouldUseDefaultsWhenOptionalParamsAreNull() {
		registerHandler("/default", textHandler("ok", StandardCharsets.UTF_8, 200));

		WebFetchService.FetchResponse response = this.service.fetch(this.baseUrl + "/default", null, null, null);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.truncated()).isFalse();
		assertThat(response.body()).isEqualToNormalizingWhitespace("""
				ok
				""");
	}

	private void registerHandler(String path, HttpHandler handler) {
		this.server.createContext(path, handler);
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
