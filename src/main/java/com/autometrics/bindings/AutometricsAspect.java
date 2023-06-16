package com.autometrics.bindings;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

@Aspect
@Component
public class AutometricsAspect {

    private final MeterRegistry registry;
    private final Gauge artifactInfoGauge;

    public AutometricsAspect(MeterRegistry registry, Environment environment) throws IOException {
        Properties properties = new Properties();
        properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));

        Optional<String> gitCommitId = getCommit(properties);
        Optional<String> gitBranch = getBranch(properties);
        Optional<String> version = getVersion(environment);

        this.registry = registry;
        this.artifactInfoGauge = Gauge.builder("build_info", () -> 1.0)
                .tags("version", version.orElse("unknown"))
                .tags("commit", gitCommitId.orElse("unknown"))
                .tags("branch", gitBranch.orElse("unknown"))
                .register(registry);
    }

    private Optional<String> getCommit(Properties properties) {
        if (properties.getProperty("git.commit.id.full") != null) {
            return Optional.of(properties.getProperty("git.commit.id.full"));
        } else if (properties.getProperty("git.commit.id") != null) {
            return Optional.of(properties.getProperty("git.commit.id"));
        } else {
            return Optional.empty();
        }
    }
    private Optional<String> getBranch(Properties properties) {
        if (properties.getProperty("git.branch") != null) {
            return Optional.of(properties.getProperty("git.branch"));
        } else {
            return Optional.empty();
        }
    }

    private Optional<String> getVersion(Environment environment) {
        if (environment.getProperty("app.version") != null) {
            return Optional.of(environment.getProperty("app.version"));
        } else {
            return Optional.empty();
        }
    }


    @Around("@annotation(Autometrics)")
    public Object methodCallCount(ProceedingJoinPoint joinPoint) throws Throwable {
        String function = joinPoint.getSignature().getName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();
        String caller = "";
        try {
            Object proceed = joinPoint.proceed();
            registry.counter( "function.calls.count","function", function, "module", module, "result", "ok", "caller", caller).increment();
            return proceed;
        } catch (Throwable throwable) {
            registry.counter("function.calls.count", "function", function, "module", module, "result", "error", "caller", caller).increment();
            throw throwable;
        }
    }

    @Around("@annotation(Autometrics)")
    public Object methodCallDuration(ProceedingJoinPoint joinPoint) {
        String function = joinPoint.getSignature().getName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();
        Timer timer = registry.timer("function.calls.duration", "function", function, "module", module);
        return timer.record(() -> {
            try {
                return joinPoint.proceed();
            } catch (Throwable throwable) {
                throw new RuntimeException(throwable);
            }
        });
    }

    @Around("@annotation(Autometrics)")
    public Object methodConcurrentCalls(ProceedingJoinPoint joinPoint) throws Throwable {
        String function = joinPoint.getSignature().getName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();

        try {
            Object proceed = joinPoint.proceed();
            registry.counter( "function.calls.concurrent","function", function, "module", module).increment();
            return proceed;
        } catch (Throwable throwable) {
            registry.counter("function.calls.count", "function", function, "module", module).increment(-1.0);
            throw throwable;
        }
    }
}