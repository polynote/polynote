package polynote.kernel.interpreter.jav.javarepl;

import com.googlecode.totallylazy.Either;
import com.googlecode.totallylazy.Files;
import com.googlecode.totallylazy.Option;
import com.googlecode.totallylazy.Sequence;
import com.googlecode.totallylazy.annotations.multimethod;
import com.googlecode.totallylazy.functions.Reducer;
import com.googlecode.totallylazy.multi;
import polynote.kernel.interpreter.jav.javarepl.expressions.Assignment;
import polynote.kernel.interpreter.jav.javarepl.expressions.AssignmentWithType;
import polynote.kernel.interpreter.jav.javarepl.expressions.Expression;
import polynote.kernel.interpreter.jav.javarepl.expressions.Import;
import polynote.kernel.interpreter.jav.javarepl.expressions.Method;
import polynote.kernel.interpreter.jav.javarepl.expressions.Patterns;
import polynote.kernel.interpreter.jav.javarepl.expressions.Statement;
import polynote.kernel.interpreter.jav.javarepl.expressions.Type;
import polynote.kernel.interpreter.jav.javarepl.expressions.Value;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;

import static com.googlecode.totallylazy.Either.left;
import static com.googlecode.totallylazy.Either.right;
import static com.googlecode.totallylazy.Files.delete;
import static com.googlecode.totallylazy.Files.deleteFiles;
import static com.googlecode.totallylazy.Files.directory;
import static com.googlecode.totallylazy.Files.file;
import static com.googlecode.totallylazy.Option.none;
import static com.googlecode.totallylazy.Option.option;
import static com.googlecode.totallylazy.Option.some;
import static com.googlecode.totallylazy.Sequences.empty;
import static com.googlecode.totallylazy.Sequences.sequence;
import static com.googlecode.totallylazy.predicates.Not.not;
import static com.googlecode.totallylazy.predicates.Predicates.equalTo;
import static com.googlecode.totallylazy.predicates.Predicates.where;
import static java.io.File.pathSeparator;
import static polynote.kernel.interpreter.jav.javarepl.Evaluation.evaluation;
import static polynote.kernel.interpreter.jav.javarepl.EvaluationClassLoader.evaluationClassLoader;
import static polynote.kernel.interpreter.jav.javarepl.EvaluationContext.evaluationContext;
import static polynote.kernel.interpreter.jav.javarepl.Result.noResult;
import static polynote.kernel.interpreter.jav.javarepl.Utils.randomIdentifier;
import static polynote.kernel.interpreter.jav.javarepl.Utils.urlAsFilePath;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.importPattern;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.isValidAssignment;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.isValidAssignmentWithType;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.isValidImport;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.isValidMethod;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.isValidType;
import static polynote.kernel.interpreter.jav.javarepl.expressions.Patterns.typePattern;
import static polynote.kernel.interpreter.jav.javarepl.rendering.EvaluationClassRenderer.renderExpressionClass;
import static polynote.kernel.interpreter.jav.javarepl.rendering.EvaluationClassRenderer.renderMethodSignatureDetection;
import static polynote.kernel.interpreter.jav.javarepl.rendering.ExpressionSourceRenderer.renderExpressionSource;
import static polynote.kernel.interpreter.jav.javarepl.rendering.ExpressionTokenRenderer.EXPRESSION_TOKEN;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

public class Evaluator {
    private ClassLoader classLoader;
    private EvaluationContext context;

    public Evaluator(EvaluationContext context, ClassLoader classLoader) {
        initializeEvaluator(context, classLoader);
    }

    public Either<Throwable, Evaluation> evaluate(final String expr) {
        return parseExpression(expr).flatMap(
                expression -> {
                    Either<Throwable, Evaluation> resultForValue = evaluate(expression);
                    if (resultForValue.isLeft() && resultForValue.left() instanceof ExpressionCompilationException && expression instanceof Value) {
                        Either<Throwable, Evaluation> resultForStatement = evaluate(new Statement(expr));
                        return resultForStatement.isLeft() && resultForStatement.left() instanceof ExpressionCompilationException
                                ? left(new ExpressionCompilationException(sequence(resultForStatement.left().getMessage(), resultForValue.left().getMessage()).unique().toString("\n\n")))
                                : resultForStatement;
                    }

                    return resultForValue;
                });
    }

    public Either<Throwable, Expression> parseExpression(String expression) {
        if (isValidImport(expression))
            return createImport(expression);

        if (isValidType(expression))
            return createTypeExpression(expression);

        if (isValidMethod(expression))
            return createMethodExpression(expression);

        if (isValidAssignmentWithType(expression))
            return createAssignmentWithType(expression);

        if (isValidAssignment(expression))
            return createAssignmentExpression(expression);

        return createValueExpression(expression);
    }

    public void addResults(Sequence<Result> result) {
        context = context.addResults(result);
    }

    public void addResults(List<Result> result) {
        context = context.addResults(sequence(result));
    }

    public void setResults(List<Result> result) {
        context = context.setResults(sequence(result));
    }

    private Either<Throwable, Expression> createImport(String expression) {
        return right((Expression) new Import(expression, importPattern.match(expression).group(1)));
    }

    private Either<Throwable, Expression> createAssignmentWithType(String expression) {
        try {
            MatchResult match = Patterns.assignmentWithTypeNamePattern.match(expression);
            java.lang.reflect.Method declaredMethod = detectMethod(match.group(1) + " " + randomIdentifier("method") + "(){}");
            return right((Expression) new AssignmentWithType(expression, declaredMethod.getGenericReturnType(), match.group(2), match.group(3)));
        } catch (Exception e) {
            return left(Utils.unwrapException(e));
        }
    }

    private Either<Throwable, Expression> createAssignmentExpression(String expression) {
        MatchResult match = Patterns.assignmentPattern.match(expression);
        return right((Expression) new Assignment(expression, match.group(1), match.group(2)));
    }

    private Either<Throwable, Expression> createTypeExpression(String expression) {
        MatchResult match = typePattern.match(expression);
        return right((Expression) new Type(expression, option(match.group(1)), match.group(2)));
    }

    private Either<Throwable, Expression> createValueExpression(String expression) {
        return right((Expression) new Value(expression));
    }

    public Option<String> lastSource() {
        return context.lastSource();
    }

    public Sequence<Result> results() {
        return context.results();
    }

    public Option<Result> result(String name) {
        return context.result(name);
    }

    public <T extends Expression> Sequence<T> expressionsOfType(Class<T> type) {
        return context.expressionsOfType(type);
    }

    public Sequence<Expression> expressions() {
        return context.expressions();
    }

    public EvaluationContext context() {
        return context;
    }

    public void reset() {
        clearOutputDirectory();
        EvaluationContext evaluationContext = evaluationContext();
        initializeEvaluator(evaluationContext, evaluationClassLoader(evaluationContext, classLoader.getParent()));
    }

    private void initializeEvaluator(EvaluationContext evaluationContext, ClassLoader evaluationClassLoader) {
        context = evaluationContext;
        classLoader = evaluationClassLoader;
    }

    public final Either<Throwable, Evaluation> tryEvaluate(String expression) {
        Evaluator localEvaluator = new Evaluator(context, evaluationClassLoader(context, classLoader.getParent()));
        return localEvaluator.evaluate(expression);
    }

    public final Option<java.lang.reflect.Type> typeOfExpression(String expression) {
        Either<? extends Throwable, Evaluation> evaluation = tryEvaluate(expression);

        Option<java.lang.reflect.Type> expressionType = none();
        if (evaluation.isRight()) {
            Option<Result> result = evaluation.right().result();
            if (!result.isEmpty()) {
                expressionType = some(result.get().type());
            }
        }

        return expressionType;
    }

    public final Option<Class<?>> classFrom(String expression) {
        try {
            return some(detectClass(expression));
        } catch (Throwable e) {
            return none();
        }
    }

    public void clearOutputDirectory() {
        deleteFiles(context.outputDirectory());
        delete(context.outputDirectory());
    }

    public void addClasspathUrl(URL classpathUrl) {
        //classLoader.registerURL(classpathUrl);
    }

    public File outputDirectory() {
        return context.outputDirectory();
    }

    @multimethod
    private Either<Throwable, Evaluation> evaluate(Expression expression) {
        return new multi() {
        }.<Either<Throwable, Evaluation>>methodOption(expression).getOrElse(evaluateExpression(expression));
    }

    @multimethod
    private Either<Throwable, Evaluation> evaluate(Type expression) {
        if (getSystemJavaCompiler() == null) {
            return left((Throwable) new FileNotFoundException("Java compiler not found." +
                    "This can occur when JavaREPL was run with JRE instead of JDK or JDK is not configured correctly."));
        }

        try {
            File outputPath = directory(context.outputDirectory(), expression.typePackage().getOrElse("").replace('.', File.separatorChar));
            File outputJavaFile = file(outputPath, expression.type() + ".java");

            String sources = renderExpressionClass(context, expression.type(), expression)
                    .replace(EXPRESSION_TOKEN, renderExpressionSource(expression));

            Files.write(sources.getBytes(), outputJavaFile);
            compile(outputJavaFile);

            classLoader.loadClass(expression.canonicalName());

            context = context.addExpression(expression).lastSource(sources);

            return right(evaluation(expression, noResult()));
        } catch (Exception e) {
            return left(Utils.unwrapException(e));
        }
    }

    private Either<Throwable, Expression> createMethodExpression(String expression) {
        try {
            java.lang.reflect.Method declaredMethod = detectMethod(expression);
            return right((Expression) new Method(expression, declaredMethod.getGenericReturnType(), declaredMethod.getName(), sequence(declaredMethod.getParameterTypes())));
        } catch (Exception e) {
            return left(Utils.unwrapException(e));
        }
    }

    private java.lang.reflect.Method detectMethod(String expression) throws Exception {
        final String className = randomIdentifier("Method");
        final File outputJavaFile = file(context.outputDirectory(), className + ".java");

        final String sources = renderMethodSignatureDetection(context, className, expression);
        Files.write(sources.getBytes(), outputJavaFile);

        compile(outputJavaFile);

        Class<?> expressionClass = classLoader.loadClass(className);

        return expressionClass.getDeclaredMethods()[0];
    }

    private Class<?> detectClass(String expression) throws Exception {
        final String className = randomIdentifier("Class");
        final File outputJavaFile = file(context.outputDirectory(), className + ".java");

        final String sources = renderMethodSignatureDetection(context, className, "public " + expression + " detectClass(){return null;}");
        Files.write(sources.getBytes(), outputJavaFile);

        compile(outputJavaFile);

        Class<?> expressionClass = classLoader.loadClass(className);

        return expressionClass.getDeclaredMethods()[0].getReturnType();
    }

    private Either<Throwable, Evaluation> evaluateExpression(final Expression expression) {
        final String className = randomIdentifier("Evaluation");

        try {
            EvaluationContext newContext = context.removeExpressionWithKey(expression.key());

            File outputJavaFile = file(context.outputDirectory(), className + ".java");
            final String sources = renderExpressionClass(newContext, className, expression)
                    .replace(EXPRESSION_TOKEN, renderExpressionSource(expression));

            Files.write(sources.getBytes(), outputJavaFile);

            compile(outputJavaFile);

            Class<?> expressionClass = classLoader.loadClass(className);
            Constructor<?> constructor = expressionClass.getDeclaredConstructor(EvaluationContext.class);

            final Object expressionInstance = constructor.newInstance(newContext);

            java.lang.reflect.Method method = expressionClass.getMethod("evaluate");
            Object resultObject = method.invoke(expressionInstance);

            Sequence<Result> modifiedResults = modifiedResults(expressionInstance);

            if (resultObject != null || !method.getReturnType().equals(void.class)) {
                Result result = Result.result(nextResultKeyFor(expression), resultObject, typeFor(expression));
                context = newContext.addExpression(expression).addResults(modifiedResults.append(result)).lastSource(sources);
                return right(evaluation(expression, some(result)));
            } else {
                context = newContext.addExpression(expression).addResults(modifiedResults).lastSource(sources);
                return right(evaluation(expression, noResult()));
            }
        } catch (Throwable e) {
            return left(Utils.unwrapException(e));
        }
    }

    private Sequence<Result> modifiedResults(final Object expressionInstance) {
        return sequence(expressionInstance.getClass().getDeclaredFields())
                .reduceLeft(new Reducer<Field, Sequence<Result>>() {
                    public Sequence<Result> call(Sequence<Result> results, Field field) throws Exception {
                        Option<Result> result = result(field.getName()).filter(where(Result::value, not(equalTo(field.get(expressionInstance)))));

                        if (result.isEmpty())
                            return results;

                        return results.append(Result.result(field.getName(), field.get(expressionInstance)));

                    }

                    public Sequence<Result> identity() {
                        return empty(Result.class);
                    }
                });
    }

    private String nextResultKeyFor(Expression expression) {
        return (expression instanceof Value)
                ? context.nextResultKey()
                : expression.key();
    }

    private Option<java.lang.reflect.Type> typeFor(Expression expression) {
        return (expression instanceof AssignmentWithType)
                ? Option.some(((AssignmentWithType) expression).type())
                : Option.<java.lang.reflect.Type>none();
    }

    private static URL[] getClassLoaderUrls(final ClassLoader classLoader) {
        final List<URL> result = new ArrayList<>();
        getClassLoaderUrls(classLoader, result);
        return result.toArray(new URL[result.size()]);
    }

    private static void getClassLoaderUrls(final ClassLoader classLoader, final List<URL> result) {
        // recursively, get the URLs from this and all parent class loaders
        if(classLoader == null) {
            // noop
        } else if(classLoader instanceof URLClassLoader) {
            result.addAll(Arrays.asList(((URLClassLoader) classLoader).getURLs()));
            getClassLoaderUrls(classLoader.getParent(), result);
        } else {
            getClassLoaderUrls(classLoader.getParent(), result);
        }
    }

    private void compile(File file) throws Exception {
        JavaCompiler compiler = getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

        String classpath =
                sequence(/*System.getProperty("java.class.path"),*/ outputDirectory().getAbsolutePath())
                        .join(sequence(getClassLoaderUrls(this.classLoader)).map(urlAsFilePath()))
                        .toString(pathSeparator);

        Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromFiles(sequence(file));
        final StringWriter output = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(output, fileManager, diagnostics,
                sequence(//"-verbose",
                        "-cp", classpath,
                        // Have to set source output so that annotation processor generated classes are visible.
                        "-s", outputDirectory().getAbsolutePath()),
                null, compilationUnits);

        try {
            final boolean result = task.call();
            if (!result) {
                System.err.println("-----------------------------------");
                System.err.println("compile(\"" + file.getAbsolutePath() + "\")");
                System.err.println("OUTPUT:" + output.toString());
                System.err.println("-----------------------------------");
                throw new ExpressionCompilationException(file, diagnostics.getDiagnostics());
            }
        } finally {
            fileManager.close();
        }
    }
}
