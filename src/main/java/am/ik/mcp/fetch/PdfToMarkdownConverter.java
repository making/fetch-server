package am.ik.mcp.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jspecify.annotations.Nullable;

/**
 * Converts a PDF document into Markdown. Body text is extracted per page; the dominant
 * (most frequent by character count) font size is treated as body text, and lines
 * rendered notably larger are promoted to Markdown headings. Leading bullet glyphs are
 * normalized to Markdown list markers and pages are separated by horizontal rules.
 */
public final class PdfToMarkdownConverter {

	private PdfToMarkdownConverter() {
	}

	/**
	 * Conversion result. {@code title} is the PDF metadata title, or {@code null} when
	 * the document does not declare one.
	 */
	public record Result(@Nullable String title, String markdown) {

	}

	private static final float H1_RATIO = 1.5f;

	private static final float H2_RATIO = 1.3f;

	private static final float H3_RATIO = 1.15f;

	/**
	 * Extracted line: rendered text and the largest font size used within it.
	 */
	private record Line(String text, float maxSize) {

	}

	public static Result convert(InputStream in) throws IOException {
		try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(in))) {
			List<List<Line>> pages = extractPages(document);
			float bodySize = dominantSize(pages);
			String markdown = render(pages, bodySize);
			return new Result(metadataTitle(document), markdown);
		}
	}

	private static @Nullable String metadataTitle(PDDocument document) {
		PDDocumentInformation info = document.getDocumentInformation();
		if (info == null) {
			return null;
		}
		String title = info.getTitle();
		return (title != null && !title.isBlank()) ? title : null;
	}

	private static List<List<Line>> extractPages(PDDocument document) throws IOException {
		int pageCount = document.getNumberOfPages();
		List<List<Line>> pages = new ArrayList<>(pageCount);
		for (int page = 1; page <= pageCount; page++) {
			Collector stripper = new Collector();
			stripper.setStartPage(page);
			stripper.setEndPage(page);
			stripper.setSortByPosition(true);
			stripper.getText(document);
			pages.add(stripper.lines());
		}
		return pages;
	}

	private static float dominantSize(List<List<Line>> pages) {
		Map<Float, Integer> weights = new LinkedHashMap<>();
		for (List<Line> page : pages) {
			for (Line line : page) {
				weights.merge(line.maxSize(), Math.max(line.text().length(), 1), Integer::sum);
			}
		}
		float best = 12f;
		int bestWeight = -1;
		for (Map.Entry<Float, Integer> entry : weights.entrySet()) {
			if (entry.getValue() > bestWeight) {
				best = entry.getKey();
				bestWeight = entry.getValue();
			}
		}
		return best;
	}

	private static String render(List<List<Line>> pages, float bodySize) {
		StringBuilder markdown = new StringBuilder();
		boolean wroteContent = false;
		for (List<Line> page : pages) {
			if (wroteContent && !page.isEmpty()) {
				markdown.append("\n---\n\n");
			}
			for (Line line : page) {
				String text = normalizeBullets(line.text());
				if (text.isBlank()) {
					appendBlank(markdown);
					continue;
				}
				wroteContent = true;
				float ratio = line.maxSize() / bodySize;
				if (ratio >= H1_RATIO) {
					markdown.append("# ");
				}
				else if (ratio >= H2_RATIO) {
					markdown.append("## ");
				}
				else if (ratio >= H3_RATIO) {
					markdown.append("### ");
				}
				markdown.append(text).append('\n');
			}
		}
		return collapseBlankLines(markdown.toString()).strip();
	}

	private static String normalizeBullets(String text) {
		return text.replaceFirst("^[•●○◦▪♦·–―\\-\\u2212]+\\s*", "- ");
	}

	private static void appendBlank(StringBuilder markdown) {
		if (markdown.length() > 0 && markdown.charAt(markdown.length() - 1) != '\n') {
			markdown.append('\n');
		}
	}

	private static String collapseBlankLines(String markdown) {
		return markdown.replaceAll("\\n{3,}", "\n\n");
	}

	/**
	 * {@link PDFTextStripper} that records each rendered line together with the largest
	 * font size on the line.
	 */
	private static final class Collector extends PDFTextStripper {

		private final StringBuilder current = new StringBuilder();

		private float currentMaxSize;

		private final List<Line> lines = new ArrayList<>();

		private Collector() throws IOException {
		}

		List<Line> lines() {
			flush();
			return this.lines;
		}

		@Override
		protected void writeString(String text, List<TextPosition> positions) {
			if (text == null || text.isEmpty()) {
				return;
			}
			this.current.append(text);
			for (TextPosition position : positions) {
				float size = position.getFontSizeInPt();
				if (size > this.currentMaxSize) {
					this.currentMaxSize = size;
				}
			}
		}

		@Override
		protected void writeWordSeparator() {
			this.current.append(getWordSeparator());
		}

		@Override
		protected void writeLineSeparator() {
			flush();
		}

		private void flush() {
			String text = this.current.toString().strip();
			if (!text.isEmpty()) {
				this.lines.add(new Line(text, this.currentMaxSize));
			}
			this.current.setLength(0);
			this.currentMaxSize = 0f;
		}

	}

}
