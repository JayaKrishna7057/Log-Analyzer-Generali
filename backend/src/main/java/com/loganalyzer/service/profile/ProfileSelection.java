package com.loganalyzer.service.profile;

import java.util.List;

/**
 * The dialect chosen for a log and the units it found, resolved together.
 *
 * <p>They are returned as a pair because the choice depends on the parse: the registry prefers a
 * profile that can break the log into units over one that merely recognises the text, and it
 * cannot know which that is without parsing.
 */
public record ProfileSelection(ProfileMatch match, List<ProcessingUnit> units) {

    public ProfileSelection {
        units = List.copyOf(units);
    }
}
