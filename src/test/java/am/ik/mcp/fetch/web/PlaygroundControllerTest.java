package am.ik.mcp.fetch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Integration test for the server-rendered fetch playground. Boots the application on a
 * random port, points the form at a JDK-based mock HTTP server, and asserts on the HTML
 * that Mustache renders for both the page shell and the htmx result fragment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class PlaygroundControllerTest {

	@Autowired
	private RestTestClient client;

	private HttpServer mockServer;

	private String mockBaseUrl;

	@BeforeEach
	void setUp() throws IOException {
		this.mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.mockServer.start();
		this.mockBaseUrl = "http://127.0.0.1:" + this.mockServer.getAddress().getPort();
	}

	@AfterEach
	void tearDown() {
		this.mockServer.stop(0);
	}

	@Test
	void shouldRenderIndexPageWithForm() {
		String html = getBody("/");

		assertThat(html).contains("fetch").contains("hx-post=\"/playground\"").contains("name=\"urls\"");
	}

	@Test
	void shouldRenderMarkdownResultFragment() {
		this.mockServer.createContext("/page", htmlHandler("""
				<html><head><title>Sample Page</title></head>
				<body><h1>Heading</h1><p>Hello world</p></body></html>
				"""));

		String html = postForm("urls=" + encode(this.mockBaseUrl + "/page") + "&markdown=true");

		assertThat(html).contains("status-ok")
			.contains(">200<")
			.contains("Sample Page")
			.contains("# Heading")
			.contains("raw tool result");
	}

	@Test
	void shouldRenderErrorCardWhenFetchFails() {
		// Nothing registered for this path on a server that returns 404 for unknown
		// contexts;
		// use an unreachable port instead to force a transport error with status 0.
		String html = postForm("urls=" + encode("http://127.0.0.1:1/down") + "&markdown=false");

		assertThat(html).contains("status-err").contains(">ERR<").contains("card-error");
	}

	@Test
	void shouldRenderValidationAlertWhenUrlsBlank() {
		String html = postForm("urls=%20%20&markdown=true");

		assertThat(html).contains("alert").contains("at least one URL");
	}

	private String getBody(String uri) {
		String body = this.client.get()
			.uri(uri)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private String postForm(String formBody) {
		String body = this.client.post()
			.uri("/playground")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(formBody)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		return body;
	}

	private static String encode(String url) {
		return java.net.URLEncoder.encode(url, StandardCharsets.UTF_8);
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
