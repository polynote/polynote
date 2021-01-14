package polynote.kernel.interpreter.jav.javarepl.completion;


import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.functions.Callables;
import com.googlecode.totallylazy.functions.Function0;
import com.googlecode.totallylazy.functions.Function1;
import polynote.kernel.interpreter.jav.javarepl.EvaluationClassLoader;

import java.io.File;
import java.net.URL;

import static com.googlecode.totallylazy.Files.asFile;
import static com.googlecode.totallylazy.Sequences.memorise;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.Strings.*;
import static com.googlecode.totallylazy.functions.Callables.last;
import static com.googlecode.totallylazy.functions.Functions.function;
import static com.googlecode.totallylazy.predicates.Predicates.not;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static java.lang.ClassLoader.getSystemClassLoader;
import static polynote.kernel.interpreter.jav.javarepl.Utils.entries;
import static polynote.kernel.interpreter.jav.javarepl.completion.TypeResolver.methods.*;

public final class TypeResolver {
    private final Function0<Sequence<ResolvedPackage>> packageResolver;

    public TypeResolver(Function0<Sequence<ResolvedPackage>> packageResolver) {
        this.packageResolver = packageResolver;
    }

    public Sequence<ResolvedPackage> packages() {
        return packageResolver.apply();
    }


    public static class functions {
        public static Function0<Sequence<ResolvedPackage>> defaultPackageResolver() {
            return packagesResolver(classpathJars().join(runtimeJars()));
        }

        public static Function0<Sequence<ResolvedPackage>> evaluationClassLoaderResolver(final EvaluationClassLoader classLoader) {
            return () -> packagesFromFiles(classLoader.registeredUrls().map(URL::getFile).map(asFile()));
        }

        public static Function0<Sequence<ResolvedPackage>> packagesResolver(final Sequence<File> files) {
            return ((Function0<Sequence<ResolvedPackage>>) () -> packagesFromFiles(files)).lazy();
        }

        public static Function0<Sequence<ResolvedClass>> classResolver(final ResolvedPackage resolvedPackage) {
            return () -> classes(resolvedPackage);
        }


        public static Function1<String, String> simpleClassName() {
            return className -> className.substring(className.lastIndexOf('.') + 1);
        }
    }


    public static class methods {
        public static Sequence<ResolvedPackage> packagesFromFiles(Sequence<File> files) {
            return files
                    .flatMap(packagesFromFile())
                    .unique()
                    .sortBy(ResolvedPackage::packageName);
        }

        public static Sequence<ResolvedClass> classes(final ResolvedPackage resolvedPackage) {
            return entries(resolvedPackage.file())
                    .map(replace("/", "."))
                    .filter(startsWith(resolvedPackage.packageName())
                            .and(endsWith(".class"))
                            .and(not(contains("$"))))
                    .map(replaceFirst("\\.class$", ""))
                    .filter(where(replace(resolvedPackage.packageName() + ".", ""), not(contains("."))))
                    .unique()
                    .map(s -> new ResolvedClass(resolvedPackage, s.substring(s.lastIndexOf('.') + 1)));
        }


        private static Function1<File, Sequence<ResolvedPackage>> packagesFromFile() {
            return file -> entries(file).filter(endsWith(".class").and(not(contains("$"))))
                    .map(replaceFirst("[a-zA-Z0-9_]*.class$", "").then(replaceFirst("/$", "")).then(replace("/", ".")))
                    .unique()
                    .map(function(ResolvedPackage::new).flip().applySecond(file));
        }

        public static Sequence<File> classpathJars() {
            return sequence(System.getProperty("java.class.path").split(File.pathSeparator))
                    .map(asFile());
        }

        public static Sequence<File> runtimeJars() {
            try {
                return memorise(getSystemClassLoader().getResources("java/lang/Object.class"))
                        .map(Callables.asString().then(split("!/")).then(Callables.<String>first()).then(split(":")).then(last(String.class)))
                        .unique()
                        .map(asFile());
            } catch (Exception e) {
                throw new RuntimeException("Couldn't load runtime jars", e);
            }
        }
    }
}
