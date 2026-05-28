package am.ik.mcp.fetch;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jspecify.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * MCP tool service that fetches arbitrary URLs over HTTP and optionally converts HTML
 * responses into Markdown.
 */
@Service
public class WebFetchService {

	private final RestClient.Builder builder;

	private final WebFetchProperties properties;

	private final FlexmarkHtmlConverter htmlToMarkdown = FlexmarkHtmlConverter
		.builder(new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false))
		.build();

	public WebFetchService(RestClient.Builder builder, WebFetchProperties properties) {
		this.builder = builder;
		this.properties = properties;
	}

	public record FetchResponse(int status, String contentType, String body, boolean truncated) {
	}

	public record MarkdownResponse(int status, String title, String markdown, boolean truncated) {
	}

	@McpTool(name = "fetch", description = "Fetch a URL via HTTP GET and return the raw response body")
	public FetchResponse fetch(@ToolParam(description = "Target URL") String url,
			@ToolParam(description = "Optional HTTP request headers",
					required = false) @Nullable Map<String, String> headers,
			@ToolParam(description = "Optional request timeout in seconds (default 30)",
					required = false) @Nullable Integer timeoutSeconds,
			@ToolParam(description = "Optional maximum body size in bytes (default 1,000,000)",
					required = false) @Nullable Integer maxBytes) {
		return doFetch(new FetchOptions(url, headers, timeoutSeconds, maxBytes));
	}

	@McpTool(name = "fetch-as-markdown", description = "Fetch a URL and convert its HTML body into Markdown")
	public MarkdownResponse fetchAsMarkdown(@ToolParam(description = "Target URL") String url,
			@ToolParam(description = "Optional HTTP request headers",
					required = false) @Nullable Map<String, String> headers,
			@ToolParam(description = "Optional request timeout in seconds (default 30)",
					required = false) @Nullable Integer timeoutSeconds,
			@ToolParam(description = "Optional maximum body size in bytes (default 1,000,000)",
					required = false) @Nullable Integer maxBytes) {
		FetchResponse raw = doFetch(new FetchOptions(url, headers, timeoutSeconds, maxBytes));
		Document doc = Jsoup.parse(raw.body(), url);
		String markdown = htmlToMarkdown.convert(raw.body());
		return new MarkdownResponse(raw.status(), doc.title(), markdown, raw.truncated());
	}

	private FetchResponse doFetch(FetchOptions options) {
		Duration effectiveTimeout = (options.timeoutSeconds() != null) ? Duration.ofSeconds(options.timeoutSeconds())
				: this.properties.defaultTimeout();
		int effectiveMaxBytes = (options.maxBytes() != null) ? options.maxBytes()
				: Math.toIntExact(this.properties.defaultMaxSize().toBytes());
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(effectiveTimeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(effectiveTimeout);
		RestClient client = this.builder.clone().requestFactory(factory).build();
		return client.get()
			.uri(URI.create(options.url()))
			.headers(httpHeaders -> applyHeaders(httpHeaders, options.headers()))
			.exchange((request, response) -> {
				// Read one extra byte to detect truncation reliably.
				try (InputStream in = response.getBody()) {
					byte[] readBytes = in.readNBytes(effectiveMaxBytes + 1);
					boolean truncated = readBytes.length > effectiveMaxBytes;
					byte[] bodyBytes = truncated ? Arrays.copyOf(readBytes, effectiveMaxBytes) : readBytes;
					String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
					Charset charset = resolveCharset(contentType);
					String body = new String(bodyBytes, charset);
					return new FetchResponse(response.getStatusCode().value(), (contentType != null) ? contentType : "",
							body, truncated);
				}
			}, true);
	}

	private static void applyHeaders(HttpHeaders target, @Nullable Map<String, String> source) {
		if (source == null) {
			return;
		}
		source.forEach(target::set);
	}

	private static Charset resolveCharset(@Nullable String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			MediaType mediaType = MediaType.parseMediaType(contentType);
			Charset charset = mediaType.getCharset();
			return (charset != null) ? charset : StandardCharsets.UTF_8;
		}
		catch (RuntimeException ex) {
			return StandardCharsets.UTF_8;
		}
	}

	private record FetchOptions(String url, @Nullable Map<String, String> headers, @Nullable Integer timeoutSeconds,
			@Nullable Integer maxBytes) {
	}

}
