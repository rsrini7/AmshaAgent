package com.amsha.classifier.api;

import java.util.Objects;

/**
 * Identity-safe, compiler-checked handle to a question registered in a decision request or definition.
 * Parametric type {@code <A>} ensures that lookups produce the exact declared answer type without
 * unchecked casts or {@link ClassCastException}.
 *
 * @param <A> the expected answer type (Choice, Score, or Noul)
 */
public final class QuestionKey<A extends DecisionAnswer> {

    private final String name;
    private final Class<A> answerType;
    private final Object owner;

    private QuestionKey(String name, Class<A> answerType, Object owner) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Question handle name must not be blank");
        }
        this.name = name;
        this.answerType = Objects.requireNonNull(answerType, "answerType");
        if (!DecisionAnswer.class.isAssignableFrom(answerType)) {
            throw new IllegalArgumentException("answerType must implement DecisionAnswer: " + answerType.getName());
        }
        this.owner = owner;
    }

    public static QuestionKey<Choice> choice(String name) {
        return new QuestionKey<>(name, Choice.class, null);
    }

    public static QuestionKey<Score> score(String name) {
        return new QuestionKey<>(name, Score.class, null);
    }

    public static QuestionKey<Noul> noul(String name) {
        return new QuestionKey<>(name, Noul.class, null);
    }

    public static <A extends DecisionAnswer> QuestionKey<A> of(String name, Class<A> answerType) {
        return new QuestionKey<>(name, answerType, null);
    }

    public static <A extends DecisionAnswer> QuestionKey<A> of(String name, Class<A> answerType, Object owner) {
        return new QuestionKey<>(name, answerType, owner);
    }

    public String name() {
        return name;
    }

    public Class<A> answerType() {
        return answerType;
    }

    public Object owner() {
        return owner;
    }

    /**
     * Derives the decision primitive associated with this handle.
     *
     * @return the decision primitive
     */
    public DecisionPrimitive primitive() {
        if (Choice.class.isAssignableFrom(answerType)) {
            return DecisionPrimitive.CHOICE;
        }
        if (Score.class.isAssignableFrom(answerType)) {
            return DecisionPrimitive.SCORE;
        }
        if (Noul.class.isAssignableFrom(answerType)) {
            return DecisionPrimitive.NOUL;
        }
        throw new IllegalArgumentException("Unsupported answer type: " + answerType.getName());
    }

    /**
     * Binds this key to a specific owner object (such as a request or builder token).
     *
     * @param requestOwner owner handle token
     * @return bound question key
     */
    public QuestionKey<A> withOwner(Object requestOwner) {
        return new QuestionKey<>(name, answerType, requestOwner);
    }

    /**
     * Type-safely verifies and casts the given object to the key's target answer type.
     * Fails closed with {@link IllegalArgumentException} rather than allowing a raw {@link ClassCastException}.
     *
     * @param answer candidate answer object
     * @return typed answer
     * @throws IllegalArgumentException if the answer is null or of mismatched type
     */
    public A cast(Object answer) {
        if (answer == null) {
            throw new IllegalArgumentException("Answer for question handle '" + name + "' must not be null");
        }
        if (!answerType.isInstance(answer)) {
            throw new IllegalArgumentException("Answer for question handle '" + name + "' is of type "
                    + answer.getClass().getSimpleName() + ", expected " + answerType.getSimpleName());
        }
        return answerType.cast(answer);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof QuestionKey<?> other)) {
            return false;
        }
        return name.equals(other.name) && answerType.equals(other.answerType) && Objects.equals(owner, other.owner);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, answerType, owner);
    }

    @Override
    public String toString() {
        return "QuestionKey[" + name + ":" + answerType.getSimpleName()
                + (owner != null ? "@" + System.identityHashCode(owner) : "") + "]";
    }
}
