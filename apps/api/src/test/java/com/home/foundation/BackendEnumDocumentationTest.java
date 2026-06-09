package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendEnumDocumentationTest {

	private static final Map<String, Path> ENUM_ROOTS = Map.of(
		"application", Path.of("src/main/java/com/home/application"),
		"domain", Path.of("src/main/java/com/home/domain")
	);
	private static final Pattern ENUM_DECLARATION = Pattern.compile("(?s)/\\*\\*.*?\\*/\\s*public enum\\s+\\w+");

	@Test
	@DisplayName("application과 domain enum은 한글 JavaDoc과 title/description accessor를 가진다")
	void applicationAndDomainEnumsExposeKoreanTitleDescriptionAndJavadocs() {
		List<String> violations = ENUM_ROOTS.entrySet()
			.stream()
			.filter(entry -> Files.isDirectory(entry.getValue()))
			.flatMap(entry -> enumDocumentationViolations(entry.getKey(), entry.getValue()).stream())
			.toList();

		assertThat(violations).isEmpty();
	}

	private static boolean containsEnumDeclaration(Path path) {
		try {
			return Files.readString(path).contains("public enum ");
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static List<String> enumDocumentationViolations(String layerName, Path layerRoot) {
		try (var paths = Files.walk(layerRoot)) {
			return paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(BackendEnumDocumentationTest::containsEnumDeclaration)
				.flatMap(path -> enumDocumentationViolations(layerName, layerRoot, path))
				.toList();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect enum source root: " + layerRoot, ex);
		}
	}

	private static java.util.stream.Stream<String> enumDocumentationViolations(
		String layerName,
		Path layerRoot,
		Path path
	) {
		try {
			String source = Files.readString(path);
			String relativePath = layerName + "/" + layerRoot.relativize(path);
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
