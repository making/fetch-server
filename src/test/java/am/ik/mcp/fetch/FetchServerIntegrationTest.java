package am.ik.mcp.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * End-to-end integration test that boots the application on a random port, points the
 * fetch tools at a JDK-based mock HTTP server, and walks through the MCP Streamable HTTP
 * handshake documented in the README (initialize, notifications/initialized, tools/list,
 * tools/call).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class FetchServerIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

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
	void shouldListAllTools() throws Exception {
		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode result = callRpc(sessionId, "tools/list", null);

		List<String> toolNames = result.path("tools").findValues("name").stream().map(JsonNode::asText).sorted().toList();
		assertThat(toolNames).containsExactly("fetch", "fetch-as-markdown");
	}

	@Test
	void shouldReturnRawBodyForFetch() throws Exception {
		registerHandler("/hello", textHandler("Hello from mock", 200));

		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "fetch", Map.of("url", this.mockBaseUrl + "/hello"));

		assertThat(payload.path("status").asInt()).isEqualTo(200);
		assertThat(payload.path("contentType").asText()).startsWith("text/plain");
		assertThat(payload.path("truncated").asBoolean()).isFalse();
		assertThat(payload.path("body").asText()).isEqualToNormalizingWhitespace("""
				Hello from mock
				""");
	}

	@Test
	void shouldConvertHtmlToMarkdown() throws Exception {
		registerHandler("/page", htmlHandler("""
				<html>
				  <head><title>Sample</title></head>
				  <body>
				    <h1>Heading</h1>
				    <p>Hello world</p>
				  </body>
				</html>
				"""));

		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "fetch-as-markdown", Map.of("url", this.mockBaseUrl + "/page"));

		assertThat(payload.path("status").asInt()).isEqualTo(200);
		assertThat(payload.path("title").asText()).isEqualTo("Sample");
		assertThat(payload.path("markdown").asText()).isEqualToNormalizingWhitespace("""
				# Heading

				Hello world
				""");
	}

	@Test
	void shouldForwardCustomHeaders() throws Exception {
		registerHandler("/echo", exchange -> {
			String received = exchange.getRequestHeaders().getFirst("X-Trace-Id");
			byte[] body = (received != null ? received : "").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});

		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "fetch", Map.of("url", this.mockBaseUrl + "/echo", "headers",
				Map.of("X-Trace-Id", "demo-trace")));

		assertThat(payload.path("body").asText()).isEqualToNormalizingWhitespace("""
				demo-trace
				""");
	}

	private String initializeSession() {
		HttpHeaders headers = this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
					Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo",
							Map.of("name", "junit", "version", "1"))))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseHeaders();
		String sessionId = headers.getFirst("Mcp-Session-Id");
		assertThat(sessionId).as("Mcp-Session-Id header from initialize").isNotBlank();
		return sessionId;
	}

	private void confirmInitialized(String sessionId) {
		this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("Mcp-Session-Id", sessionId)
			.body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
			.exchange()
			.expectStatus()
			.isAccepted();
	}

	private JsonNode callTool(String sessionId, String toolName, Map<String, Object> arguments) throws Exception {
		JsonNode rpcResult = callRpc(sessionId, "tools/call", Map.of("name", toolName, "arguments", arguments));
		assertThat(rpcResult.path("isError").asBoolean()).as("tool error flag").isFalse();
		String inner = rpcResult.path("content").get(0).path("text").asText();
		return MAPPER.readTree(inner);
	}

	private JsonNode callRpc(String sessionId, String method, Map<String, Object> params) throws Exception {
		Map<String, Object> request = (params != null)
				? Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params)
				: Map.of("jsonrpc", "2.0", "id", 2, "method", method);
		String body = this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("Mcp-Session-Id", sessionId)
			.body(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		String json = extractSseData(body);
		return MAPPER.readTree(json).path("result");
	}

	private static String extractSseData(String sseBody) {
		return sseBody.lines()
			.filter(line -> line.startsWith("data:"))
			.findFirst()
			.map(line -> line.substring("data:".length()))
			.orElse(sseBody);
	}

	private void registerHandler(String path, HttpHandler handler) {
		this.mockServer.createContext(path, handler);
	}

	private static HttpHandler textHandler(String body, int status) {
		return exchange -> {
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
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
