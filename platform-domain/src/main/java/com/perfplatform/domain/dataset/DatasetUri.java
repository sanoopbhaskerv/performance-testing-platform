package com.perfplatform.domain.dataset;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a specification names the dataset it wants: {@code dataset://{name}/{version}}.
 *
 * <p>A name and a version, and nothing else. Not a URL, not a bucket, not an object key — the
 * specification says <em>which data</em>, and the platform decides where that lives, exactly as it
 * decides where traffic goes rather than letting a document name its own host. A specification that
 * could name an object key could name another team's.
 *
 * <h2>The version is required, and that is the whole point</h2>
 * {@code dataset://orders} would mean "whatever orders.csv holds today". Two runs of one immutable
 * definition version would then read different data while carrying the same specification digest —
 * the same defect as a floating runner image tag, which this platform already refuses at startup
 * (Increment 1E). A run is reproducible from its stored inputs or it is not reproducible at all, so
 * an unversioned reference is refused at validation rather than resolved to the newest upload.
 *
 * <p>Uploading a dataset therefore <em>adds</em> a version rather than replacing one, and pointing a
 * test at new data is an edit to the specification: a visible, reviewable act with its own digest.
 */
public record DatasetUri(String name, int version) {

    /** The scheme, spelled out so a mistyped one is refused rather than half-parsed. */
    public static final String SCHEME = "dataset://";

    /**
     * {@code dataset://{name}/{version}}.
     *
     * <p>The name is the same restricted form every other name in a specification takes — lowercase
     * alphanumeric with hyphens — because a dataset name reaches an object key, and a key is what
     * makes authorization structural. The version is a plain positive integer with no leading zero,
     * so one textual form maps to one version and {@code /01} cannot address {@code /1}.
     */
    private static final Pattern FORM = Pattern.compile(
            "^dataset://([a-z0-9]([-a-z0-9]*[a-z0-9])?)/([1-9][0-9]{0,8})$");

    /** An unversioned reference, recognised only so it can be refused with the right explanation. */
    private static final Pattern UNVERSIONED = Pattern.compile(
            "^dataset://([a-z0-9]([-a-z0-9]*[a-z0-9])?)/?$");

    public DatasetUri {
        Objects.requireNonNull(name, "name");
        if (version < 1) {
            throw new IllegalArgumentException("A dataset version starts at 1, was " + version);
        }
    }

    /** The canonical text form, which is what a specification carries. */
    @Override
    public String toString() {
        return SCHEME + name + "/" + version;
    }

    /** The reference for a name and version. */
    public static DatasetUri of(String name, int version) {
        return new DatasetUri(name, version);
    }

    /**
     * Parses a reference, or explains nothing.
     *
     * <p>Empty rather than an exception: this is called on a value out of a submitted document, and
     * the caller turns an unparseable one into a validation finding with a path and a remedy. An
     * exception here would reach the consumer as an internal error instead.
     */
    public static Optional<DatasetUri> parse(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        Matcher matcher = FORM.matcher(uri.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new DatasetUri(matcher.group(1), Integer.parseInt(matcher.group(3))));
    }

    /**
     * Whether this is a well-formed reference that simply omits its version.
     *
     * <p>Distinguished from an unrecognisable string so the finding can say <em>pin a version</em>
     * rather than <em>this is not a dataset reference</em>. The two are different mistakes with
     * different fixes, and a single message covering both helps with neither.
     */
    public static boolean isUnversioned(String uri) {
        return uri != null && UNVERSIONED.matcher(uri.trim()).matches();
    }
}
