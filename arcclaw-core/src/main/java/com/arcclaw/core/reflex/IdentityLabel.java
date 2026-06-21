package com.arcclaw.core.reflex;

public enum IdentityLabel {
    USER_NAME, // canonical name
    USER_ALIAS, // "call me X"
    USER_HOBBY, // "my hobby is X" / "i enjoy doing X"
    USER_PREF, // "I prefer X"
    USER_LIKE, // "I like X" / "I love X" / "I enjoy X"
    USER_EMPLOYER, // "I work at X"
    USER_LOCATION, // "I'm based in X"
    USER_TIMEZONE, // infer from location or explicit
    USER_ROLE // "i teach/study/tutor/..."
}
