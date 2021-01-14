package polynote.kernel.interpreter.jav.javarepl.expressions;


import static java.util.UUID.randomUUID;

public final class Import extends Expression {
    private final String typePackage;

    public Import(String source, String typePackage) {
        super(source);
        this.typePackage = typePackage;
    }

    public String key() {
        return randomUUID().toString();
    }

    public String typePackage() {
        return typePackage;
    }

}
