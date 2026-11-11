# Java Spring Boot — Strict Code Style & Formatting Rules (R01–R19)

## Purpose and scope

This Java 21 Spring Boot service requires all rules below for every Java file
created or modified, including production code, tests, configuration, security,
controllers, services, repositories, filters, and utilities. These rules are
mandatory, not optional suggestions.

Produce clean, consistent, professional, highly readable Java optimized for
IntelliJ IDEA on an approximately 15-inch laptop.

- Follow the rules during implementation, not only during final cleanup.
- Prioritize readability over minimizing the number of lines.
- Avoid horizontally dense expressions, excessive nesting, and unnecessary
  formatting complexity.
- Preserve business logic, security behavior, API contracts, test expectations,
  and existing architecture.
- Do not introduce unrelated refactoring solely for formatting preferences.
- Apply formatting consistently to Java code you touch. Avoid unrelated
  repository-wide reformatting unless explicitly requested.
- If a rule conflicts with Java syntax, compilation, or required behavior,
  preserve correctness and use the closest readable formatting.

These principles apply to Spring Security and `SecurityFilterChain`, `@Bean`
methods, Spring MVC controllers, Spring Data repositories, Spring Boot
configuration, HTTP clients, Micrometer and observability configuration, JUnit,
Mockito, and integration tests. They do not require changing project architecture
or adopting a different framework.

## R01 — One Statement Per Line

Never place multiple Java statements on the same line.

Incorrect:

```java
config.setPort(8080); config.setEnabled(true);
```

Correct:

```java
config.setPort(8080);
config.setEnabled(true);
```

## R02 — Separate Annotations and Declarations

Place annotations on separate lines before the declaration they annotate.
For multiple annotations, use one annotation per line.

Incorrect:

```java
@Test void shouldReturnSuccess() {
}
```

Correct:

```java
@Test
void shouldReturnSuccess() {
}
```

## R03 — Mandatory Braces

Always use braces for control-flow statements, including single-statement
bodies. Applies to `if`, `else`, `for`, `while`, `do-while`, and similar
constructs.

Incorrect:

```java
if (enabled) return;
```

Correct:

```java
if (enabled) {
    return;
}
```

## R04 — Strict Line Length Policy

Optimize code for an approximately 15-inch laptop screen.

- **80 characters:** Preferred target for ordinary Java lines.
- **100 characters:** Normal hard maximum.
- **120 characters:** Exceptional maximum, only when unavoidable.

Exceptions may include:

- Package declarations.
- Import declarations.
- Unsplittable identifiers.
- String literals that cannot safely be split.

Additional requirements:

- Prefer breaking long expressions at logical boundaries.
- Do not shorten meaningful names merely to satisfy line length.
- Do not compress complex expressions just because they fit within 100 characters.
- Avoid horizontal scrolling whenever reasonably possible.
- Prefer readable line breaks over horizontal compression.
- Do not introduce excessive line breaks for simple, readable expressions.

## R05 — No Wildcard Imports

Never use wildcard imports, including static imports. Import only the classes
and static methods actually used.

Incorrect:

```java
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
```

Correct:

```java
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
```

## R06 — Logical Code Grouping

Organize statements into logical sections. Typical sections include:

- Variable and dependency initialization.
- Mock or configuration setup.
- Business operations.
- Validation and assertions.
- Resource cleanup.
- Return statements.

Separate distinct logical groups with blank lines. Keep statements performing
the same operation together. Avoid unnecessary comments when the structure is
self-explanatory, including redundant `// Arrange`, `// Act`, and `// Assert`.

## R07 — Descriptive Naming

Use meaningful, readable names for classes, methods, fields, parameters,
variables, and constants. Test method names should clearly describe the behavior
being verified.

Avoid:

- Unnecessary abbreviations.
- Ambiguous names.
- Redundant words.
- Extremely long identifiers when a shorter, equally clear name is available.
- Names describing implementation details instead of the behavior under test.

Do not rename existing public APIs or externally referenced methods solely for
formatting.

## R08 — Named Constants

Use named constants for meaningful configuration values, limits, timeouts,
intervals, and repeated values.

```java
private static final int MAX_POOL_SIZE = 2;
private static final int MAX_REFRESH_ATTEMPTS = 150;
private static final int REFRESH_INTERVAL_MS = 10;
```

Do not automatically extract every numeric literal. Use constants when they
improve clarity, reuse, or maintainability.

## R09 — Readable Method Chaining

Keep simple, short method chains compact. Break long or complex chains into
readable lines. Prefer significant chained operations on separate lines, with
the dot at the beginning of continuation lines.

```java
double total = registry.find("app.security.events")
        .counters()
        .stream()
        .mapToDouble(counter -> counter.count())
        .sum();
```

Avoid unnecessary vertical expansion of trivial method chains. R19 takes
precedence when an expression is horizontally dense despite remaining within
the line-length limit.

## R10 — Never Compress Code

Never compress multiple operations onto one line to reduce line count. Apply
this rule to control-flow blocks, resource management, configuration, and tests.

Incorrect:

```java
try { process(); } finally { cleanup(); }
```

Correct:

```java
try {
    process();
} finally {
    cleanup();
}
```

## R11 — Import Organization

Organize imports into consistent, clearly separated groups. Preferred grouping:

1. Project-specific imports.
2. Framework and third-party imports.
3. Java and Jakarta imports.
4. Static imports.

Requirements:

- Never use wildcard imports.
- Keep imports from the same package or library together.
- Sort imports alphabetically within groups.
- Separate major groups with one blank line.
- Remove unused imports.
- Keep static imports in their own group.
- Follow an existing project-wide import order if already established, while
  preserving explicit imports.

## R12 — No Fully Qualified Class Names Inside Java Code

Do not write fully qualified class names directly inside fields, parameters,
return types, constructors, annotations, local variables, or method bodies.

Incorrect:

```java
private com.japaneselearning.learning.metrics.SecurityMetrics securityMetrics;

@org.springframework.beans.factory.annotation.Autowired(required = false)
public void setSecurityMetrics(
        com.japaneselearning.learning.metrics.SecurityMetrics metrics
) {
    this.securityMetrics = metrics;
}
```

Correct imports:

```java
import com.japaneselearning.learning.metrics.SecurityMetrics;
import org.springframework.beans.factory.annotation.Autowired;
```

Correct implementation:

```java
private SecurityMetrics securityMetrics;

@Autowired(required = false)
public void setSecurityMetrics(SecurityMetrics metrics) {
    this.securityMetrics = metrics;
}
```

Exceptions:

- Types from `java.lang` do not require imports.
- Types in the same package do not require imports.
- Fully qualified names are permitted only when necessary to resolve unavoidable
  naming conflicts.

## R13 — Vertical Spacing and Indentation

Use consistent indentation and spacing.

- Use 4 spaces per indentation level.
- Never use tabs.
- Insert one space after commas.
- Insert spaces around binary operators.
- Insert one blank line between imports and the class declaration.
- Separate distinct logical sections with blank lines.
- Avoid unnecessary consecutive blank lines.
- Do not separate closely related statements without reason.
- Remove trailing whitespace.
- End Java source files with a newline.

Incorrect:

```java
metrics.completed("POST","/auth/login",false,401);
metrics.completed("GET","/admin/users",false,403);
assertThat(registry.getMeters()).hasSize(7);
```

Correct:

```java
metrics.completed("POST", "/auth/login", false, 401);
metrics.completed("GET", "/admin/users", false, 403);

assertThat(registry.getMeters()).hasSize(7);
```

## R14 — Method Arguments, Parameters, and Annotations

### A. Method invocations

When method calls contain many arguments, are horizontally dense, or exceed the
preferred line length, put each argument on a separate line. Place the closing
parenthesis on its own line for multiline argument lists. Keep short, simple
calls compact.

Incorrect:

```java
return MeterFilter.maximumAllowableTags("http.server.requests", "uri", 100, MeterFilter.deny());
```

Correct:

```java
return MeterFilter.maximumAllowableTags(
        "http.server.requests",
        "uri",
        100,
        MeterFilter.deny()
);
```

### B. Method declarations

When declarations require multiline parameters:

- Place each parameter on its own line.
- Use consistent indentation.
- Place the closing `)` on a separate line.
- Keep short declarations on one line when readable.

```java
SecurityFilterChain metricsSecurity(
        HttpSecurity http,
        ObjectProvider<Connector> connectors
) throws Exception {
    // Implementation
}
```

### C. Annotated parameters

For multiline method or constructor parameters:

- Place each annotation on its own line.
- Place the parameter type and name on a separate line.
- Insert one blank line between parameter groups.
- Never insert a blank line between an annotation and its associated parameter.
- Keep multiple annotations for the same parameter together.

```java
@Bean
MeterRegistryCustomizer<MeterRegistry> metricsIdentity(
        @Value("${spring.application.name}")
        String service,

        @Value("${app.deployment-environment:development}")
        String environment,

        @Value("${app.version:unknown}")
        String version
) {
    // Implementation
}
```

Apply similar formatting to long constructor invocations and annotation argument
lists.

## R15 — Control Flow Block Formatting

Always put control-flow bodies on subsequent lines.

Incorrect:

```java
if (metrics != null) metrics.completed(method(), route, internal, status);
```

Correct:

```java
if (metrics != null) {
    metrics.completed(
            method(),
            route,
            internal,
            status
    );
}
```

Requirements:

- Always use braces.
- Never place the body on the same line as the condition.
- Place closing braces on separate lines.
- Use consistent indentation.
- Apply R14 when statements inside the block have long argument lists.

## R16 — Boolean Expressions and Condition Formatting

For multiline boolean expressions:

- Put each logical condition on a separate line.
- Place `&&` and `||` at the beginning of continuation lines.
- Align conditions consistently.
- For multiline `if`, `else if`, and `while` conditions, place the closing `)` on
  its own line.
- Keep short, simple conditions on one line.
- Use parentheses when needed to clarify logical grouping.
- Preserve operator precedence and short-circuit behavior.

Boolean assignment:

```java
boolean health = path.equals("/actuator/health")
        || path.startsWith("/actuator/health/")
        || path.equals("/actuator/prometheus");
```

Multiline `if`:

```java
if (request.isSecure()
        && user.isActive()
        && user.hasPermission("ADMIN")
) {
    processRequest();
}
```

Do not write the closing `)` on the same line as the final condition when the
condition spans multiple lines.

## R17 — Logical Block Separation

Use blank lines to visually distinguish different operations inside a method.

```java
Connector connector = new Connector(
        TomcatServletWebServerFactory.DEFAULT_PROTOCOL
);

connector.setPort(port);
connector.setProperty("address", "0.0.0.0");
connector.setProperty("maxThreads", "8");
connector.setProperty("maxConnections", "32");
connector.setProperty("acceptCount", "16");
connector.setProperty("connectionTimeout", "5000");

return connector;
```

Requirements:

- Separate initialization from configuration when they represent distinct steps.
- Separate completed operations from final return statements.
- Keep related configuration calls together.
- Use one blank line between logical groups by default.
- Avoid arbitrary or excessive blank lines.

## R18 — Long Method and Class Declarations

### A. Long method declarations

When a method declaration is too long:

- Prefer keeping the return type and method name together.
- Break the parameter list into multiple lines.
- Place each parameter on its own line.
- Put the closing `)` on a separate line.
- Keep `throws` on the same line as the closing `)` whenever possible.
- Do not separate return type and method name unless unavoidable.

```java
@Bean
@ConditionalOnProperty(
        name = "app.metrics.listener.enabled",
        havingValue = "true"
)
WebServerFactoryCustomizer<TomcatServletWebServerFactory> metricsListener(
        Connector metricsConnector
) {
    return factory -> factory.addAdditionalConnectors(metricsConnector);
}
```

With `throws`:

```java
SecurityFilterChain metricsSecurity(
        HttpSecurity http,
        ObjectProvider<Connector> connectors
) throws Exception {
    // Implementation
}
```

### B. Long class declarations

Break long `extends` and `implements` declarations into readable lines.

```java
public class SecurityMetricsConfiguration
        extends AbstractMetricsConfiguration
        implements WebMvcConfigurer, ApplicationContextAware {

    // Implementation
}
```

Use additional continuation lines when needed to satisfy R04. Avoid excessively
long class names and unnecessary inheritance or interfaces.

## R19 — Strict Horizontal Readability and Expression Wrapping

This rule is especially important and takes precedence over compact formatting
preferences. Do not treat the 100-character limit as permission to write dense,
difficult-to-read expressions.

### A. Strict horizontal limits

- Target 80 characters per line.
- Treat 100 characters as the normal hard maximum.
- Allow up to 120 characters only when genuinely unavoidable.
- Prefer readable multiline formatting over horizontal compression.
- Do not use excessively short names merely to fit code onto one line.
- Avoid horizontally dense Java expressions even below 100 characters.

### B. Fluent method chains

When a chain contains multiple significant operations:

- Prefer one significant operation per line.
- Place dots at the beginning of continuation lines.
- Keep nested callbacks visually separated.
- Avoid compressing several configuration steps into one expression.
- Keep simple, short chains compact.

Incorrect:

```java
@Bean
SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll()).build();
}
```

Correct:

```java
@Bean
SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http
            .authorizeHttpRequests(requests ->
                    requests.anyRequest().permitAll()
            )
            .build();
}
```

This is a formatting example, not a requirement to change authorization policy.

### C. Lambda expressions

- Break long or nested lambda expressions into readable lines.
- Prefer expression lambdas for simple single-expression behavior.
- Use block lambdas when multiple statements are required.
- Do not introduce unnecessary braces.
- Preserve return behavior and functional interface compatibility.
- Avoid deeply nested lambda chains on a single line.

### D. Spring Boot configuration

Pay special attention to:

- Spring Security configuration.
- Fluent builder APIs.
- Stream pipelines.
- HTTP client configuration.
- Micrometer metrics configuration.
- Spring Boot dependency configuration.
- Nested callbacks and lambda expressions.
- Long validation and assertion chains.

Format these constructs so that each significant operation is easy to identify.
Formatting examples do not authorize dependency or runtime behavior changes.

### E. Readability over line count

Prefer readable vertical structure over minimizing the number of lines. However:

- Do not split every trivial method call.
- Do not create excessive vertical whitespace.
- Do not make simple expressions unnecessarily verbose.
- Do not change program behavior solely to improve formatting.
- Preserve Java semantics.

## Mandatory Final Self-Review

Before completing any coding task, inspect every Java file created or modified.
Verify all of the following:

1. No wildcard imports.
2. No unnecessary fully qualified class names inside Java code.
3. No multiple statements compressed onto one line.
4. All control-flow bodies use braces and proper line breaks.
5. Long method invocations use readable argument formatting.
6. Long method declarations have properly formatted parameters.
7. Annotated parameters have separate annotation and declaration lines.
8. Multiline boolean conditions have leading `&&`/`||` operators and properly
   placed closing parentheses.
9. Long method declarations and `throws` clauses follow R18.
10. Long class declarations are formatted consistently.
11. Logical code groups are separated by appropriate blank lines.
12. Indentation uses 4 spaces, with no tabs or trailing whitespace.
13. Ordinary Java code stays within 100 characters per line.
14. Lines exceeding 100 characters are justified by unavoidable constraints.
15. Lines exceeding 120 characters are avoided unless genuinely unsplittable.
16. Fluent APIs and nested lambdas are not horizontally compressed.
17. Imports are explicit, grouped, and consistently ordered.
18. Formatting is readable without unnecessary vertical expansion.
19. Business logic, security behavior, public API contracts, and test expectations
    remain unchanged by formatting.

If formatting checks, compilation, or tests are available and appropriate,
execute them. Do not claim any verification passed unless actually executed.
Avoid unrelated repository-wide reformatting unless explicitly requested.

## Final Instruction

Apply R01–R19 strictly throughout implementation. Write clean, readable,
consistently formatted code optimized for reviewing and debugging on a 15-inch
laptop in IntelliJ IDEA. Prioritize:

1. Correctness.
2. Readability.
3. Consistency.
4. Maintainability.
5. Minimal horizontal scrolling.

Do not sacrifice readability to reduce the number of lines.
