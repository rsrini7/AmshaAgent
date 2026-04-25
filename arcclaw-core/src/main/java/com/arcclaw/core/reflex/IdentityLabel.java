package com.arcclaw.core.reflex;

public enum IdentityLabel {
    USER_NAME,       // canonical name
    USER_ALIAS,      // "call me X"
    USER_HOBBY,      // "I love X"
    USER_PREF,       // "I prefer X"
    USER_EMPLOYER,   // "I work at X"
    USER_LOCATION,   // "I'm based in X"
    USER_TIMEZONE    // infer from location or explicit
}