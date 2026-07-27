package com.thaap.payments.workspace;

public class SavedSearchUpgradeRequiredException extends RuntimeException {

    public SavedSearchUpgradeRequiredException() {
        super("Saved search requires an explicit schema upgrade");
    }
}
