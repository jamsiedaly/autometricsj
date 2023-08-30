# autometricsj

Autometrics is an observability micro-framework built for developers. It makes it easy to instrument any function with the most useful metrics: request rate, error rate, and latency. Autometrics uses instrumented function names to generate Prometheus queries so you don’t need to hand-write complicated PromQL.

This is an implementation of Autometrics in Java. This implementation is built with AspectJ and is built to integrate with spring boot and micrometer.

## Usage
To add autometrics as a dependency
```xml
<dependency>
  <groupId>com.autometrics.bindings</groupId>
  <artifactId>autometricsj</artifactId>
  <version>1.3</version>
</dependency>
```

To enable autometrics in your project
```java
@SpringBootApplication
@EnableAutometrics
public class YourApplication {
```

To generate Autometrics metrics for a function method:
```java
@Autometrics
public String yourMethod() {
```

## Requirements
This plugin leverages Micrometer in order to expose your metrics to Autometrics. If your project does not already 
have Micrometer configured, then these metrics will not be scraped by Prometheus.

In order for this plugin to work with service name and version, you must expose them through your application.yaml file.
```yaml
app:
  version: 1.0.0

spring:
  application:
    name: application-name
```
