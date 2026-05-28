package am.ik.mcp.fetch;

import org.jspecify.annotations.Nullable;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Configuration that wires native image hints for libraries and types whose reflection
 * metadata is not auto-discovered by Spring Boot's AOT processor.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeConfig.NativeRuntimeHints.class)
@RegisterReflectionForBinding({ WebFetchService.FetchResponse.class, WebFetchService.TextResponse.class,
		WebFetchService.MarkdownResponse.class })
public class NativeConfig {

	/**
	 * Native image hints needed by libraries used by the fetch server. flexmark's
	 * {@code BitFieldSet.UniverseLoader#getUniverseSlow} reflects on enum classes via
	 * {@code Class#getFields()} during static initialization, so every enum it consumes
	 * must be reflection-registered. Spring AI's MCP annotation runtime additionally
	 * instantiates meta providers via no-arg constructors discovered through reflection.
	 */
	static class NativeRuntimeHints implements RuntimeHintsRegistrar {

		private static final String[] FLEXMARK_ENUMS = { "com.vladsch.flexmark.ext.emoji.EmojiImageType",
				"com.vladsch.flexmark.ext.emoji.EmojiShortcutType",
				"com.vladsch.flexmark.ext.emoji.internal.EmojiReference$EmojiBrowserType",
				"com.vladsch.flexmark.ext.tables.TableCell$Alignment", "com.vladsch.flexmark.formatter.FormattingPhase",
				"com.vladsch.flexmark.formatter.RenderPurpose", "com.vladsch.flexmark.html.renderer.RenderingPhase",
				"com.vladsch.flexmark.html2md.converter.ExtensionConversion",
				"com.vladsch.flexmark.html2md.converter.HtmlConverterPhase",
				"com.vladsch.flexmark.html2md.converter.LinkConversion",
				"com.vladsch.flexmark.html2md.converter.TableConversion",
				"com.vladsch.flexmark.html2md.converter.TagType", "com.vladsch.flexmark.parser.block.ParserPhase",
				"com.vladsch.flexmark.parser.internal.HtmlDeepParser$HtmlMatch",
				"com.vladsch.flexmark.parser.ParserEmulationProfile", "com.vladsch.flexmark.util.ast.KeepType",
				"com.vladsch.flexmark.util.ast.TextContainer$Flags",
				"com.vladsch.flexmark.util.format.MarkdownParagraph$TextType",
				"com.vladsch.flexmark.util.format.NumberFormat",
				"com.vladsch.flexmark.util.format.options.BlockQuoteMarker",
				"com.vladsch.flexmark.util.format.options.CodeFenceMarker",
				"com.vladsch.flexmark.util.format.options.DefinitionMarker",
				"com.vladsch.flexmark.util.format.options.DiscretionaryText",
				"com.vladsch.flexmark.util.format.options.ElementAlignment",
				"com.vladsch.flexmark.util.format.options.ElementPlacement",
				"com.vladsch.flexmark.util.format.options.ElementPlacementSort",
				"com.vladsch.flexmark.util.format.options.EqualizeTrailingMarker",
				"com.vladsch.flexmark.util.format.options.HeadingStyle",
				"com.vladsch.flexmark.util.format.options.KeepAtStartOfLine",
				"com.vladsch.flexmark.util.format.options.ListBulletMarker",
				"com.vladsch.flexmark.util.format.options.ListNumberedMarker",
				"com.vladsch.flexmark.util.format.options.ListSpacing",
				"com.vladsch.flexmark.util.format.options.TableCaptionHandling",
				"com.vladsch.flexmark.util.format.options.TrailingSpaces", "com.vladsch.flexmark.util.format.Sort",
				"com.vladsch.flexmark.util.format.TableSectionType", "com.vladsch.flexmark.util.format.TextAlignment",
				"com.vladsch.flexmark.util.format.TrackedOffset$Flags", "com.vladsch.flexmark.util.html.CellAlignment",
				"com.vladsch.flexmark.util.options.ParsedOptionStatus",
				"com.vladsch.flexmark.util.sequence.BasedOptionsHolder$Options",
				"com.vladsch.flexmark.util.sequence.builder.ISegmentBuilder$Options",
				"com.vladsch.flexmark.util.sequence.builder.tree.Segment$SegType",
				"com.vladsch.flexmark.util.sequence.LineAppendable$Options",
				"com.vladsch.flexmark.util.sequence.LineInfo$Flags",
				"com.vladsch.flexmark.util.sequence.LineInfo$Preformatted",
				"com.vladsch.flexmark.util.sequence.PositionAnchor" };

		private static final String[] SPRING_AI_REFLECTIVE_CLASSES = {
				"org.springframework.ai.mcp.annotation.context.DefaultMetaProvider" };

		@Override
		public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
			for (String type : FLEXMARK_ENUMS) {
				hints.reflection()
					.registerType(TypeReference.of(type), MemberCategory.ACCESS_PUBLIC_FIELDS,
							MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_PUBLIC_METHODS,
							MemberCategory.INVOKE_DECLARED_METHODS);
			}
			for (String type : SPRING_AI_REFLECTIVE_CLASSES) {
				hints.reflection()
					.registerType(TypeReference.of(type), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
							MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
			}
		}

	}

}
