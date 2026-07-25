package com.home.application.news.selection;

import java.time.LocalDate;
import java.util.List;

public interface MajorNewsComplexSelectionRepository {

    boolean hasPublishedSelection(LocalDate selectionWeek);

    List<MajorNewsComplexCandidate> findCandidates(LocalDate asOfDate);

    void publish(LocalDate selectionWeek, List<MajorNewsComplexCandidate> selected);
}
