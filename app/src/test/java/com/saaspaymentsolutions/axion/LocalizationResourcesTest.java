package com.saaspaymentsolutions.axion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/** Regression tests for every user-facing localized string resource. */
public class LocalizationResourcesTest {
    private static final List<String> LOCALE_DIRS = Arrays.asList(
            "values-en", "values-ar", "values-pt-rMZ", "values-es", "values-fr", "values-pt-rBR"
    );
    private static final List<String> STRING_FILES = Arrays.asList("strings.xml", "strings_lottie.xml");
    private static final Pattern FORMAT_ARG = Pattern.compile(
            "%(?:\\d+\\$)?[-+# 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]"
    );
    private static final List<String> MOJIBAKE_MARKERS = Arrays.asList(
            "\uFFFD", "Ã©", "Ã£", "Ã§", "Ã¡", "Ã³", "Ãº", "Ãª", "Ã´", "Ã­",
            "Ã‰", "Ã‡", "Â ", "Â•", "â€", "├", "┬"
    );

    @Test
    public void everyLocaleHasEveryTranslatableString() throws Exception {
        ResourceSet base = readResourceSet("values");
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : base.values.entrySet()) {
            if (!"false".equals(base.translatable.get(entry.getKey()))) {
                expected.put(entry.getKey(), entry.getValue());
            }
        }

        for (String locale : LOCALE_DIRS) {
            ResourceSet translated = readResourceSet(locale);
            assertEquals("Key set differs for " + locale, expected.keySet(), translated.values.keySet());
        }
    }

    @Test
    public void formatArgumentsExactlyMatchBaseLanguage() throws Exception {
        ResourceSet base = readResourceSet("values");
        for (String locale : LOCALE_DIRS) {
            ResourceSet translated = readResourceSet(locale);
            for (Map.Entry<String, String> entry : translated.values.entrySet()) {
                String key = entry.getKey();
                assertEquals("Format arguments differ for " + locale + "/" + key,
                        formatArgs(base.values.get(key)), formatArgs(entry.getValue()));
            }
        }
    }

    @Test
    public void localizedXmlIsUtf8AndHasNoKnownMojibake() throws Exception {
        for (String locale : LOCALE_DIRS) {
            for (String fileName : STRING_FILES) {
                Path file = resDir().resolve(locale).resolve(fileName);
                byte[] bytes = Files.readAllBytes(file);
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertEquals("Invalid UTF-8 round-trip in " + file, Arrays.toString(bytes),
                        Arrays.toString(text.getBytes(StandardCharsets.UTF_8)));
                for (String marker : MOJIBAKE_MARKERS) {
                    assertFalse("Mojibake marker '" + marker + "' found in " + file, text.contains(marker));
                }
            }
        }
    }

    @Test
    public void axionBrandIsNeverTranslated() throws Exception {
        ResourceSet base = readResourceSet("values");
        assertEquals("Axion", base.values.get("app_name"));
        assertEquals("false", base.translatable.get("app_name"));
        assertEquals("Axion", base.values.get("main_drawer_app_name"));
        assertEquals("false", base.translatable.get("main_drawer_app_name"));

        for (String locale : LOCALE_DIRS) {
            ResourceSet translated = readResourceSet(locale);
            assertFalse(locale + " must inherit app_name", translated.values.containsKey("app_name"));
            assertFalse(locale + " must inherit main_drawer_app_name",
                    translated.values.containsKey("main_drawer_app_name"));
        }
    }

    @Test
    public void localeConfigAndRtlSupportAreEnabled() throws Exception {
        Path localeConfig = resDir().resolve("xml/locales_config.xml");
        Document doc = parse(localeConfig);
        NodeList nodes = doc.getElementsByTagName("locale");
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            actual.add(e.getAttributeNS("http://schemas.android.com/apk/res/android", "name"));
        }
        assertEquals(Arrays.asList("pt-BR", "en", "ar", "pt-MZ", "es", "fr"), actual);

        Document manifest = parse(appDir().resolve("src/main/AndroidManifest.xml"));
        Element application = (Element) manifest.getElementsByTagName("application").item(0);
        assertNotNull(application);
        assertEquals("true", application.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "supportsRtl"));
        assertEquals("@xml/locales_config", application.getAttributeNS(
                "http://schemas.android.com/apk/res/android", "localeConfig"));
    }

    @Test
    public void arabicCatalogActuallyContainsArabicText() throws Exception {
        ResourceSet arabic = readResourceSet("values-ar");
        long arabicStrings = arabic.values.values().stream()
                .filter(value -> value.codePoints().anyMatch(cp -> cp >= 0x0600 && cp <= 0x06FF))
                .count();
        assertTrue("Arabic catalog appears untranslated", arabicStrings > 500);
    }

    private static List<String> formatArgs(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        Matcher matcher = FORMAT_ARG.matcher(value);
        while (matcher.find()) out.add(matcher.group());
        return out;
    }

    private static ResourceSet readResourceSet(String valuesDir) throws Exception {
        ResourceSet result = new ResourceSet();
        for (String fileName : STRING_FILES) {
            Path file = resDir().resolve(valuesDir).resolve(fileName);
            assertTrue("Missing resource file " + file, Files.isRegularFile(file));
            Document doc = parse(file);
            NodeList strings = doc.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element e = (Element) strings.item(i);
                String name = e.getAttribute("name");
                assertFalse("Duplicate string " + valuesDir + "/" + name,
                        result.values.containsKey(name));
                result.values.put(name, e.getTextContent());
                result.translatable.put(name, e.getAttribute("translatable"));
            }
        }
        return result;
    }

    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (InputStream in = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(in);
        }
    }

    private static Path resDir() {
        return appDir().resolve("src/main/res");
    }

    private static Path appDir() {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("src/main/res"))) return cwd;
        if (Files.isDirectory(cwd.resolve("app/src/main/res"))) return cwd.resolve("app");
        throw new IllegalStateException("Could not locate Android app module from " + cwd);
    }

    private static final class ResourceSet {
        final Map<String, String> values = new LinkedHashMap<>();
        final Map<String, String> translatable = new LinkedHashMap<>();
    }
}
