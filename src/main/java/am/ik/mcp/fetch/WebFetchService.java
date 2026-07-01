package am.ik.mcp.fetch;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jspecify.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * MCP tool service that fetches one or more URLs over HTTP, optionally converting HTML
 * responses into Markdown. URLs in a single call are fetched concurrently on the
 * Spring-managed task executor (virtual threads when enabled), and a failure of one URL
 * does not abort the others.
 */
@Service
public class WebFetchService {

	private static final Logger logger = LoggerFactory.getLogger(WebFetchService.class);

	private final RestClient.Builder builder;

	private final WebFetchProperties properties;

	private final AsyncTaskExecutor taskExecutor;

	private final FlexmarkHtmlConverter htmlToMarkdown = FlexmarkHtmlConverter
		.builder(new MutableDataSet().set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false))
		.build();

	private InetAddressFilter addressFilter = InetAddressFilter.externalAddresses().or("127.0.0.1");

	public WebFetchService(RestClient.Builder builder, WebFetchProperties properties,
			@Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.builder = builder;
		this.properties = properties;
		this.taskExecutor = taskExecutor;
	}

	/**
	 * Result of fetching a single URL. When {@code markdown} is requested, {@code title}
	 * holds the HTML document title and {@code content} holds the Markdown rendering;
	 * otherwise {@code title} is {@code null} and {@code content} holds the raw body.
	 * When the fetch fails, {@code status} is {@code 0}, {@code content} is empty, and
	 * {@code error} carries the failure message.
	 */
	public record FetchResult(String url, int status, String contentType, @Nullable String title, String content,
			boolean truncated, @Nullable String error) {
	}

	/**
	 * Aggregated response holding one {@link FetchResult} per requested URL, in the same
	 * order as the input.
	 */
	public record FetchResponse(List<FetchResult> results) {
	}

	@McpTool(name = "fetch",
			description = "Fetch one or more URLs via HTTP GET, optionally converting HTML bodies to Markdown")
	public FetchResponse fetch(@ToolParam(description = "Target URLs to fetch") List<String> urls,
			@ToolParam(description = "Convert HTML body to Markdown (default true)",
					required = false) @Nullable Boolean markdown,
			@ToolParam(description = "Optional HTTP request headers",
					required = false) @Nullable Map<String, String> headers,
			@ToolParam(description = "Optional request timeout in seconds (default 30)",
					required = false) @Nullable Integer timeoutSeconds,
			@ToolParam(description = "Optional maximum body size in bytes per URL (default 1,000,000)",
					required = false) @Nullable Integer maxBytes) {
		Duration effectiveTimeout = (timeoutSeconds != null) ? Duration.ofSeconds(timeoutSeconds)
				: this.properties.defaultTimeout();
		int effectiveMaxBytes = (maxBytes != null) ? maxBytes
				: Math.toIntExact(this.properties.defaultMaxSize().toBytes());
		boolean asMarkdown = (markdown == null) || markdown;
		FetchContext context = new FetchContext(buildClient(effectiveTimeout), asMarkdown, headers, effectiveMaxBytes);

		// Submit every URL first so they run concurrently, then join in input order.
		List<CompletableFuture<FetchResult>> futures = urls.stream()
			.map(url -> this.taskExecutor.submitCompletable(() -> fetchOne(context, url)))
			.toList();
		return new FetchResponse(futures.stream().map(CompletableFuture::join).toList());
	}

	private FetchResult fetchOne(FetchContext context, String url) {
		// Write the body itself in logfmt so it is visible with the default console
		// pattern,
		// while the same fields are also exposed as structured pairs via the KeyValue
		// API.
		logger.atInfo()
			.addKeyValue("url", url)
			.addKeyValue("markdown", context.markdown())
			.log("msg=\"fetching url\" url=\"{}\" markdown={}", url, context.markdown());
		try {
			return context.client()
				.get()
				.uri(URI.create(url))
				.headers(httpHeaders -> applyHeaders(httpHeaders, context.headers()))
				.exchange((request, response) -> {
					// Read one extra byte to detect truncation reliably.
					try (InputStream in = response.getBody()) {
						byte[] readBytes = in.readNBytes(context.maxBytes() + 1);
						boolean truncated = readBytes.length > context.maxBytes();
						byte[] bodyBytes = truncated ? Arrays.copyOf(readBytes, context.maxBytes()) : readBytes;
						String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
						String body = new String(bodyBytes, resolveCharset(contentType));
						int status = response.getStatusCode().value();
						String resolvedContentType = (contentType != null) ? contentType : "";
						if (context.markdown()) {
							Document doc = Jsoup.parse(body, url);
							String rendered = this.htmlToMarkdown.convert(body);
							return new FetchResult(url, status, resolvedContentType, doc.title(), rendered, truncated,
									null);
						}
						return new FetchResult(url, status, resolvedContentType, null, body, truncated, null);
					}
				}, true);
		}
		catch (Exception ex) {
			return errorResult(url, ex);
		}
	}

	private RestClient buildClient(Duration timeout) {
		HttpClientSettings settings = HttpClientSettings.defaults()
			.withReadTimeout(timeout)
			.withConnectTimeout(timeout)
			.withInetAddressFilter(this.addressFilter);
		return this.builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
	}

	private static FetchResult errorResult(String url, Throwable ex) {
		String message = ex.getMessage();
		String error = (message != null && !message.isBlank()) ? message : ex.getClass().getSimpleName();
		return new FetchResult(url, 0, "", null, "", false, error);
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

	private record FetchContext(RestClient client, boolean markdown, @Nullable Map<String, String> headers,
			int maxBytes) {
	}

}
