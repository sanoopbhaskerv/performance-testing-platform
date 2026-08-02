package com.perfplatform.importer.har;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recognises the requests a browser made on the page's behalf rather than the ones the user's
 * journey is made of.
 *
 * <p>A capture of a single page load is typically eighty to three hundred entries, of which a
 * handful are the application calls anyone wants to load-test. The rest are scripts, stylesheets,
 * fonts, images and analytics beacons — served from a CDN, cached in front of the origin, and
 * unrelated to the behaviour under test. Importing them would put most of a test's load on assets
 * that never touch the application, and the resulting latency figure would be an average across two
 * populations that have nothing to do with each other.
 *
 * <p>They are dropped and counted, never dropped silently. Someone testing a page's asset delivery
 * specifically needs to know the importer removed exactly the thing they meant to measure.
 *
 * <p>Both tests are deliberately conservative in the same direction: an extension or a content type
 * that is unambiguously an asset. An API returning {@code application/json} from a path ending
 * {@code /data.js} stays, because a false drop removes something a person wanted and a false keep
 * only leaves something for them to delete.
 */
final class StaticAssets {

    /** Extensions that are an asset whatever the server says they are. */
    private static final Set<String> EXTENSIONS = Set.of(
            "js", "mjs", "cjs", "css", "map",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "bmp", "webp", "avif",
            "woff", "woff2", "ttf", "otf", "eot",
            "mp4", "webm", "ogg", "mp3", "wav",
            "pdf", "zip");

    /** Content-type prefixes that are an asset whatever the path looks like. */
    private static final List<String> CONTENT_TYPES = List.of(
            "image/", "font/", "audio/", "video/",
            "text/css", "text/javascript",
            "application/javascript", "application/x-javascript",
            "application/font", "application/x-font");

    private StaticAssets() {
    }

    /**
     * Whether this request fetched an asset rather than exercising the application.
     *
     * @param path        the request path
     * @param contentType the response content type, or null when the capture did not record one
     */
    static boolean isAsset(String path, String contentType) {
        return hasAssetExtension(path) || hasAssetContentType(contentType);
    }

    private static boolean hasAssetExtension(String path) {
        if (path == null) {
            return false;
        }
        int lastSlash = path.lastIndexOf('/');
        String lastSegment = lastSlash < 0 ? path : path.substring(lastSlash + 1);
        int dot = lastSegment.lastIndexOf('.');
        if (dot < 0 || dot == lastSegment.length() - 1) {
            return false;
        }
        return EXTENSIONS.contains(lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean hasAssetContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.stream().anyMatch(normalized::startsWith);
    }
}
