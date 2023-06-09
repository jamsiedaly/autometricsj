package com.autometrics.bindings;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.commons.logging.Log;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

@Aspect
@Component
public class AutometricsAspect {

    private final MeterRegistry registry;

    private final Map<Gauge, AtomicInteger> concurrencyGauges = new HashMap<>();
    public AutometricsAspect(MeterRegistry registry, Environment environment) {
        Properties properties = new Properties();
        try {
            properties.load(getClass().getClassLoader().getResourceAsStream("git.properties"));
        } catch (IOException e) {
            System.out.println("Could not load git.properties");
        }

        Optional<String> gitCommitId = getCommit(properties);
        Optional<String> gitBranch = getBranch(properties);
        Optional<String> version = getVersion(environment);

        this.registry = registry;
        Gauge.builder("build_info", () -> 1.0)
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
    public Object methodCallDuration(ProceedingJoinPoint joinPoint) {
        String function = joinPoint.getSignature().getName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();
        var concurrencyGauge = findGaugeForFunction(function, module);
        Timer timer = registry.timer("function.calls.duration", "function", function, "module", module);
        concurrencyGauges.get(concurrencyGauge).incrementAndGet();
        return timer.record(() -> {
            try {
                Object proceed = joinPoint.proceed();
                registry.counter( "function.calls.count","function", function, "module", module, "result", "ok").increment();
                return proceed;
            } catch (Throwable throwable) {
                registry.counter("function.calls.count", "function", function, "module", module, "result", "error").increment();
                throw new RuntimeException(throwable);
            } finally {
                concurrencyGauges.get(concurrencyGauge).decrementAndGet();
            }
        });
    }

    public Gauge findGaugeForFunction(String function, String module) {
        var potentialGauge = concurrencyGauges.keySet().stream()
                .filter(gauge -> gauge.getId().getTag("function").equals(function) && gauge.getId().getTag("module").equals(module))
                .findFirst();
        if (potentialGauge.isPresent()) {
            return potentialGauge.get();
        } else {
            var concurrentCalls = new AtomicInteger(0);
            var newGauge =Gauge.builder("function.calls.concurrent", () -> concurrentCalls.get())
                    .tags("function", function)
                    .tags("module", module)
                    .register(registry);
            this.concurrencyGauges.put(newGauge, concurrentCalls);
            return newGauge;
        }
    }
}