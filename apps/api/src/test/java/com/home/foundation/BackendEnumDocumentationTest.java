package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendEnumDocumentationTest {

	private static final Path APPLICATION_ROOT = Path.of("src/main/java/com/home/application");
	private static final Pattern ENUM_DECLARATION = Pattern.compile("(?s)/\\*\\*.*?\\*/\\s*public enum\\s+\\w+");

	@Test
	@DisplayName("application enum은 한글 JavaDoc과 title/description accessor를 가진다")
	void applicationEnumsExposeKoreanTitleDescriptionAndJavadocs() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(APPLICATION_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(BackendEnumDocumentationTest::containsEnumDeclaration)
				.flatMap(BackendEnumDocumentationTest::enumDocumentationViolations)
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	private static boolean containsEnumDeclaration(Path path) {
		try {
			return Files.readString(path).contains("public enum ");
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static java.util.stream.Stream<String> enumDocumentationViolations(Path path) {
		try {
			String source = Files.readString(path);
			String relativePath = APPLICATION_ROOT.relativize(path).toString();
			var violations = new java.util.ArrayList<String>();
			if (!ENUM_DECLARATION.matcher(source).find()) {
				violations.add(relativePath + ": enum class-level JavaDoc is missing");
			}
			if (!source.contains("String titleKo()")) {
				violations.add(relativePath + ": titleKo() accessor is missing");
			}
			if (!source.contains("String descriptionKo()")) {
				violations.add(relativePath + ": descriptionKo() accessor is missing");
			}
			return violations.stream();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}
}
