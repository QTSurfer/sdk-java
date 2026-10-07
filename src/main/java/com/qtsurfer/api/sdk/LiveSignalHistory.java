package com.qtsurfer.api.sdk;

import com.qtsurfer.api.client.model.LiveSignal;

import java.util.List;
import java.util.Objects;

/** Sandbox signals still held by a subscribed live WebSocket channel, oldest first. */
public record LiveSignalHistory(List<Publication> publications, String epoch, long offset) {
    public LiveSignalHistory {
        publications = List.copyOf(Objects.requireNonNull(publications, "publications"));
        Objects.requireNonNull(epoch, "epoch");
    }

    /**
     * Position after the last returned signal, or the channel's current position when empty.
     * This avoids skipping publications when a limit smaller than the held history is used.
     */
    public Position position() {
        long nextOffset = publications.isEmpty() ? offset : publications.get(publications.size() - 1).offset();
        return new Position(nextOffset, epoch);
    }

    /** A decoded signal and the offset it carried when published. */
    public record Publication(LiveSignal signal, long offset) {
        public Publication {
            Objects.requireNonNull(signal, "signal");
        }
    }

    /** A channel stream position, valid only while its epoch remains available. */
    public record Position(long offset, String epoch) {
        public Position {
            Objects.requireNonNull(epoch, "epoch");
            if (epoch.isBlank()) throw new IllegalArgumentException("epoch must not be blank");
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        }
    }
}
