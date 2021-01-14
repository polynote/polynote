package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.Sequence;

import java.net.URL;
import java.net.URLClassLoader;

import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.io.URLs.toURL;

public class EvaluationClassLoader extends URLClassLoader {
    private Sequence<URL> registeredUrls = sequence();

    private EvaluationClassLoader(EvaluationContext context, final ClassLoader parent) {
        super(new URL[]{toURL().apply(context.outputDirectory())}, parent);
    }

    public static EvaluationClassLoader evaluationClassLoader(EvaluationContext context, final ClassLoader parent) {
        return new EvaluationClassLoader(context, parent);
    }

    public void registerURL(URL url) {
        if (!sequence(getURLs()).contains(url)) {
            addURL(url);
            registeredUrls = registeredUrls.append(url);
        }
    }

    public Sequence<URL> registeredUrls() {
        return registeredUrls;
    }



    public boolean isClassLoaded(String name) {
        return findLoadedClass(name) != null;
    }
}
