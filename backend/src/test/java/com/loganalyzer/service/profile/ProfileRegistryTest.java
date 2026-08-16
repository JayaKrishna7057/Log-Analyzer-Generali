package com.loganalyzer.service.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The /api/formats endpoint is only as honest as this accessor: every profile actually consulted
 * by {@link ProfileRegistry#select} and {@link ProfileRegistry#detect} must appear here too, or
 * the frontend could advertise support for a format it does not have, or vice versa.
 */
class ProfileRegistryTest {

    private final ProfileRegistry registry = new ProfileRegistry();

    @Test
    @DisplayName("every registered profile is exposed, each with a stable id and a display name")
    void exposesEveryRegisteredProfile() {
        assertThat(registry.profiles())
                .isNotEmpty()
                .allSatisfy(profile -> {
                    assertThat(profile.id()).isNotBlank();
                    assertThat(profile.displayName()).isNotBlank();
                });
    }

    @Test
    @DisplayName("ids are unique, since the frontend keys off them")
    void hasNoDuplicateIds() {
        var ids = registry.profiles().stream().map(LogProfile::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }
}
