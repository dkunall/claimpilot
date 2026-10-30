package com.claimpilot.fraud.domain;

/** A party identifier as stored: its type and a keyed hash of the normalised value, never the raw value. */
public record PartyRef(PartyType type, String hash) {

    public PartyRef {
        if (type == null || hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("party needs a type and a hash");
        }
    }

    String node() {
        return type.name() + ":" + hash;
    }
}
