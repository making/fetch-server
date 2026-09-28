package am.ik.mcp.fetch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfToMarkdownConverterTest {

	@Test
	void shouldPromoteLargerTextToHeadings() throws IOException {
		byte[] pdf;
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			document.getDocumentInformation().setTitle("Sample PDF");
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
				content.newLineAtOffset(50, 700);
				content.showText("Big Title");
				content.endText();
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 650);
				content.showText("Body text line");
				content.endText();
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 16);
				content.newLineAtOffset(50, 620);
				content.showText("Section Head");
				content.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			pdf = out.toByteArray();
		}

		PdfToMarkdownConverter.Result result = PdfToMarkdownConverter.convert(new ByteArrayInputStream(pdf));

		assertThat(result.title()).isEqualTo("Sample PDF");
		assertThat(result.markdown()).contains("# Big Title").contains("## Section Head").contains("Body text line");
	}

	@Test
	void shouldInsertSpaceBetweenTextRunsOnSameLine() throws IOException {
		byte[] pdf;
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("Hello");
				content.endText();
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(120, 700);
				content.showText("World");
				content.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			pdf = out.toByteArray();
		}

		PdfToMarkdownConverter.Result result = PdfToMarkdownConverter.convert(new ByteArrayInputStream(pdf));

		assertThat(result.markdown()).contains("Hello World");
	}

	@Test
	void shouldNormalizeBulletGlyphs() throws IOException {
		byte[] pdf;
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("• first item");
				content.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			pdf = out.toByteArray();
		}

		PdfToMarkdownConverter.Result result = PdfToMarkdownConverter.convert(new ByteArrayInputStream(pdf));

		assertThat(result.markdown()).contains("- first item");
	}

	@Test
	void shouldReturnNullTitleWhenMetadataAbsent() throws IOException {
		byte[] pdf;
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(50, 700);
				content.showText("plain");
				content.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			pdf = out.toByteArray();
		}

		PdfToMarkdownConverter.Result result = PdfToMarkdownConverter.convert(new ByteArrayInputStream(pdf));

		assertThat(result.title()).isNull();
		assertThat(result.markdown()).contains("plain");
	}

}
