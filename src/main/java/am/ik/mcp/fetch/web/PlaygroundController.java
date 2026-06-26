package am.ik.mcp.fetch.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.mcp.fetch.WebFetchService;
import am.ik.mcp.fetch.WebFetchService.FetchResult;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered playground that lets a human exercise the {@code fetch} MCP tool from a
 * browser and inspect the exact {@link FetchResult} the tool would return to an MCP
 * client. The page is rendered with Mustache; the result panel is refreshed in place via
 * htmx, so the whole interaction stays on the server side.
 */
@Controller
public class PlaygroundController {

	private final WebFetchService webFetchService;

	private final ObjectMapper objectMapper;

	public PlaygroundController(WebFetchService webFetchService, ObjectMapper objectMapper) {
		this.webFetchService = webFetchService;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/")
	public String index(Model model) {
		model.addAttribute("sampleUrls", "https://example.com");
		return "index";
	}

	@PostMapping("/playground")
	public String run(@RequestParam(defaultValue = "") String urls,
			@RequestParam(defaultValue = "false") boolean markdown, @RequestParam(defaultValue = "") String headers,
			@RequestParam(required = false) @Nullable Integer timeoutSeconds,
			@RequestParam(required = false) @Nullable Integer maxBytes, Model model) {
		List<String> urlList = parseLines(urls);
		if (urlList.isEmpty()) {
			model.addAttribute("error", "Enter at least one URL.");
			return "fragments/results";
		}

		Map<String, String> headerMap = parseHeaders(headers);
		WebFetchService.FetchResponse response = this.webFetchService.fetch(urlList, markdown,
				headerMap.isEmpty() ? null : headerMap, timeoutSeconds, maxBytes);

		List<ResultView> views = response.results().stream().map(PlaygroundController::toView).toList();
		model.addAttribute("results", views);
		model.addAttribute("count", views.size());
		model.addAttribute("plural", views.size() != 1);
		model.addAttribute("markdown", markdown);
		model.addAttribute("rawJson", toPrettyJson(response));
		return "fragments/results";
	}

	private static ResultView toView(FetchResult result) {
		boolean failed = result.error() != null;
		String statusClass = failed ? "err" : (result.status() >= 200 && result.status() < 300) ? "ok" : "warn";
		String statusLabel = failed ? "ERR" : String.valueOf(result.status());
		int bytes = result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		return new ResultView(result.url(), statusLabel, statusClass, result.contentType(), result.title(),
				result.content(), result.content().isEmpty(), result.truncated(), result.error(), formatBytes(bytes));
	}

	private String toPrettyJson(WebFetchService.FetchResponse response) {
		try {
			return this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
		}
		catch (RuntimeException ex) {
			return "// failed to render JSON: " + ex.getMessage();
		}
	}

	private static List<String> parseLines(String raw) {
		List<String> lines = new ArrayList<>();
		for (String line : raw.split("\\R")) {
			String trimmed = line.strip();
			if (!trimmed.isEmpty()) {
				lines.add(trimmed);
			}
		}
		return lines;
	}

	private static Map<String, String> parseHeaders(String raw) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (String line : parseLines(raw)) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				String name = line.substring(0, colon).strip();
				String value = line.substring(colon + 1).strip();
				if (!name.isEmpty()) {
					headers.put(name, value);
				}
			}
		}
		return headers;
	}

	private static String formatBytes(int bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		double kb = bytes / 1024.0;
		if (kb < 1024) {
			return "%.1f KB".formatted(kb);
		}
		return "%.1f MB".formatted(kb / 1024);
	}

	/**
	 * View model for a single fetched URL, holding presentation-ready values (status
	 * label, CSS status class, formatted size) so the logic-less Mustache template stays
	 * simple.
	 */
	public record ResultView(String url, String statusLabel, String statusClass, String contentType,
			@Nullable String title, String content, boolean empty, boolean truncated, @Nullable String error,
			String size) {
	}

}
