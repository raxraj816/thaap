package com.thaap.payments.workspace;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewNoteRetentionScheduler {

    private final ReviewNoteService noteService;

    public ReviewNoteRetentionScheduler(ReviewNoteService noteService) {
        this.noteService = noteService;
    }

    @Scheduled(fixedDelayString = "${thaap.workspace.notes.retention-poll-delay:1h}")
    public void expireDueNotes() {
        noteService.expireDueNotes();
    }
}
