package polynote.kernel.interpreter.jav.javarepl.reflection;


public final class ClassReflections {

    public static ClassReflection reflectionOf(Class<?> aClass) {
        return new ClassReflection(aClass);
    }

    public static ClassReflection reflectionOf(Object object) {
        return new ClassReflection(object.getClass());
    }
}
