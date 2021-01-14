package polynote.kernel.interpreter.jav.javarepl.rendering;

import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.multi;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

import static com.googlecode.totallylazy.Sequences.sequence;
import static java.lang.String.format;
import static polynote.kernel.interpreter.jav.javarepl.Utils.extractType;

public class TypeRenderer {

    @multimethod
    public static String renderType(Type type) {
        Type extractedType = extractType(type);
        return new multi() {
        }.<String>methodOption(extractedType)
                .getOrElse(extractedType.toString());
    }

    @multimethod
    private static String renderType(Class<?> type) {
        return type.getCanonicalName();
    }

    @multimethod
    private static String renderType(TypeVariable<?> typeVariable) {
        return "Object";
    }

    @multimethod
    private static String renderType(ParameterizedType type) {
        return format("%s<%s>",
                renderType(type.getRawType()),
                sequence(type.getActualTypeArguments()).map(TypeRenderer::renderType).toString(", "));
    }

}
