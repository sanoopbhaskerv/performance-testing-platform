package com.perfplatform.domain.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CidrBlockTest {

    @Test
    @DisplayName("an IPv4 CIDR matches only addresses inside its prefix")
    void ipv4Contains() throws Exception {
        CidrBlock block = CidrBlock.parse("10.96.0.0/12");

        assertThat(block.contains(ip("10.96.4.10"))).isTrue();
        assertThat(block.contains(ip("10.111.255.254"))).isTrue();
        assertThat(block.contains(ip("10.112.0.1"))).isFalse();
    }

    @Test
    @DisplayName("an exact address without a slash becomes a host-sized block")
    void exactAddress() throws Exception {
        CidrBlock block = CidrBlock.parse("169.254.169.254");

        assertThat(block.contains(ip("169.254.169.254"))).isTrue();
        assertThat(block.contains(ip("169.254.169.253"))).isFalse();
    }

    @Test
    @DisplayName("IPv4 and IPv6 ranges do not match each other")
    void addressFamiliesDoNotMix() throws Exception {
        assertThat(CidrBlock.parse("10.0.0.0/8").contains(ip("fd00::1"))).isFalse();
    }

    @Test
    @DisplayName("invalid CIDR strings fail loudly")
    void invalid() {
        assertThatThrownBy(() -> CidrBlock.parse("10.0.0.0/99"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CidrBlock.parse("not a cidr"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static InetAddress ip(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
