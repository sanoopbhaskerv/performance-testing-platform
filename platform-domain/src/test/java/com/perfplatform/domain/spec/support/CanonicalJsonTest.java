package com.perfplatform.domain.spec.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer had no tests, and shipped documents that were not JSON.
 *
 * <p>Every field after the first was emitted without a separator — {@code {"a":"1""b":"2"}} — so
 * every execution plan and every rendered workload this platform produced was unparseable. It went
 * unnoticed because the only consumer was JMeter, which executes a JMX file and never reads the plan
 * JSON; the browser runner is the first thing that parses one.
 *
 * <p>These tests are therefore about the bytes, not the API. The class exists to make a digest an
 * identity, and a digest over a malformed document is an identity for something nothing can read.
 */
class CanonicalJsonTest {

    @Test
    void separatesEveryFieldOfAnObject() {
        String json = new CanonicalJson()
                .startObject()
                .field("first", "1")
                .field("second", "2")
                .field("third", 3L)
                .field("fourth", true)
                .endObject()
                .build();

        assertThat(json)
                .isEqualTo("{\"first\":\"1\",\"second\":\"2\",\"third\":3,\"fourth\":true}");
    }

    @Test
    void writesASingleFieldWithoutALeadingSeparator() {
        String json = new CanonicalJson().startObject().field("only", "1").endObject().build();

        assertThat(json).isEqualTo("{\"only\":\"1\"}");
    }

    @Test
    void separatesArrayElementsButNotTheFirst() {
        String json = new CanonicalJson()
                .startObject()
                .startArray("items")
                .startElement().field("name", "a").endObject()
                .startElement().field("name", "b").endObject()
                .endArray()
                .endObject()
                .build();

        assertThat(json).isEqualTo("{\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");
    }

    /** A field following a closed structure needs a separator as much as any other. */
    @Test
    void separatesAFieldThatFollowsAnArray()  {
        String json = new CanonicalJson()
                .startObject()
                .field("before", "x")
                .startArray("items").startElement().field("n", 1L).endObject().endArray()
                .field("after", "y")
                .endObject()
                .build();

        assertThat(json)
                .isEqualTo("{\"before\":\"x\",\"items\":[{\"n\":1}],\"after\":\"y\"}");
    }

    @Test
    void writesAnEmptyArrayWithoutASeparator() {
        String json = new CanonicalJson()
                .startObject()
                .startArray("items").endArray()
                .field("after", "y")
                .endObject()
                .build();

        assertThat(json).isEqualTo("{\"items\":[],\"after\":\"y\"}");
    }

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        String json = new CanonicalJson()
                .startObject()
                .field("quote", "say \"hi\"")
                .field("backslash", "a\\b")
                .field("newline", "a\nb")
                .field("control", "ab")
                .endObject()
                .build();

        assertThat(json).isEqualTo("{\"quote\":\"say \\\"hi\\\"\",\"backslash\":\"a\\\\b\","
                + "\"newline\":\"a\\nb\",\"control\":\"a\\u0001b\"}");
    }

    /**
     * A null string is written as empty rather than as JSON null.
     *
     * <p>Recorded because it is load-bearing for the digest: an absent optional coordinate and one
     * explicitly set to empty must produce identical bytes, or two equivalent specifications would
     * hash differently.
     */
    @Test
    void writesANullStringAsEmpty() {
        String json = new CanonicalJson()
                .startObject().field("absent", (String) null).endObject().build();

        assertThat(json).isEqualTo("{\"absent\":\"\"}");
    }

    @Test
    void refusesToBuildAnUnbalancedDocument() {
        assertThatThrownBy(() -> new CanonicalJson().startObject().field("a", "1").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void refusesANamedFieldOutsideAnObject() {
        assertThatThrownBy(() -> new CanonicalJson()
                .startObject().startArray("items").field("a", "1"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** The whole point of the class: the same calls always produce the same bytes. */
    @Test
    void producesIdenticalBytesForIdenticalInput() {
        assertThat(document()).isEqualTo(document());
    }

    private static String document() {
        return new CanonicalJson()
                .startObject()
                .field("engine", "playwright")
                .field("shardCount", 1L)
                .startArray("workloads")
                .startElement().field("name", "browser-experience").field("users", 5L).endObject()
                .endArray()
                .endObject()
                .build();
    }
}
