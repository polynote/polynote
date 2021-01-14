package polynote.kernel.interpreter.jav.javarepl.reflection;

public abstract class MemberReflection<T> implements AnnotatedReflection {

    private final T member;

    MemberReflection(T member) {
        this.member = member;
    }

    public final T member() {
        return member;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + member() + ")";
    }

    @Override
    public int hashCode() {
        return (member() != null ? member().hashCode() : 0);
    }

    @Override
    public boolean equals(Object other) {
        return other.getClass().equals(getClass()) &&
                member().getClass().isInstance(((MemberReflection) other).member()) &&
                (member() != null && member().equals(((MemberReflection) other).member()));
    }

    public abstract Integer modifiers();

    public abstract String name();
}
