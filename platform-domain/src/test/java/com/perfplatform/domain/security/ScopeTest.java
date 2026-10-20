package com.perfplatform.domain.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

    @Test
    @DisplayName("recorder scopes are distinct from run execution scopes")
    void recorderScopesAreSeparate() {
        assertThat(Scope.RECORDINGS_CREATE).isEqualTo("recordings:create");
        assertThat(Scope.RECORDINGS_READ).isEqualTo("recordings:read");
        assertThat(Scope.RECORDINGS_CANCEL).isEqualTo("recordings:cancel");
        assertThat(Scope.RECORDINGS_CREATE).isNotEqualTo(Scope.RUNS_EXECUTE);
    }
}
