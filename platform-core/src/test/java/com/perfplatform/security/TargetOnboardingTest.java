package com.perfplatform.security;
import com.perfplatform.domain.identity.*;
import com.perfplatform.domain.port.*;
import com.perfplatform.domain.security.*;
import com.perfplatform.domain.spec.TargetClass;
import com.perfplatform.orchestration.target.ManageTargetsUseCase;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TargetOnboardingTest {
    final TeamId team = TeamId.generate();
    final TeamDirectory directory = mock(TeamDirectory.class);
    final TeamProvisioning provisioning = mock(TeamProvisioning.class);
    final TargetRegistry targets = mock(TargetRegistry.class);
    final ManageTargetsUseCase useCase = new ManageTargetsUseCase(targets, directory, provisioning);
    final ManageTargetsUseCase.RegisterCommand command = new ManageTargetsUseCase.RegisterCommand(
            "alpha", "new-application", "performance", "https://staging.example.test", TargetClass.NON_PRODUCTION);
    @Test void authorizedTargetRegistrationOnboardsTheApplication() {
        when(directory.findByName("alpha")).thenReturn(Optional.of(team));
        when(directory.findApplication(team, "new-application")).thenReturn(Optional.empty());
        when(provisioning.ensureApplication(team, "new-application", TargetClass.NON_PRODUCTION)).thenReturn(ApplicationId.generate());
        useCase.register(command, new CallerContext("author", Set.of(team), Set.of(Scope.DEFINITIONS_WRITE), true));
        verify(provisioning).ensureApplication(team, "new-application", TargetClass.NON_PRODUCTION);
        verify(targets).register(eq(team), any(), eq("new-application"), eq("performance"), eq(TargetClass.NON_PRODUCTION), any(), eq("author"));
    }
    @Test void anotherTeamsTokenCannotOnboardOrRegister() {
        when(directory.findByName("alpha")).thenReturn(Optional.of(team));
        assertThatThrownBy(() -> useCase.register(command, new CallerContext("other", Set.of(TeamId.generate()), Set.of(Scope.DEFINITIONS_WRITE), true)))
                .isInstanceOf(CallerContext.AuthorizationException.class);
        verifyNoInteractions(provisioning, targets);
    }
}
