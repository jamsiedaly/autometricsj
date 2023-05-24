# autometricsj

Autometrics is an observability micro-framework built for developers. It makes it easy to instrument any function with the most useful metrics: request rate, error rate, and latency. Autometrics uses instrumented function names to generate Prometheus queries so you don’t need to hand-write complicated PromQL.

This is an implementation of Autometrics in Java. This implementation is built with AspectJ and is built to integrate with spring boot and micrometer.

## Usage
To add autometrics as a dependency
```
<dependency>
  <groupId>com.autometrics.bindings</groupId>
  <artifactId>autometricsj</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

To enable autometrics in your project
```
@SpringBootApplication
@EnableAutometrics
public class YourApplication {
```

To generate Autometrics metrics for a function method:
```
@Autometrics
public String yourMethod() {
```
