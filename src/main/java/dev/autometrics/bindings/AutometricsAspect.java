package dev.autometrics.bindings;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

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
        } catch (Exception e) {
            System.out.println("Could not load git.properties");
        }

        Optional<String> gitCommitId = getCommit(properties);
        Optional<String> gitBranch = getBranch(properties);
        Optional<String> version = getVersion(environment);
        Optional<String> serviceName = getServiceName(environment);

        this.registry = registry;
        Gauge.builder("build_info", () -> 1.0)
                .tags("version", version.orElse("unknown"))
                .tags("commit", gitCommitId.orElse("unknown"))
                .tags("branch", gitBranch.orElse("unknown"))
                .tags("service.name", serviceName.orElse("unknown"))
                .register(registry);
    }

    private Optional<String> getServiceName(Environment environment) {
        return environment.getProperty("spring.application.name") != null ?
                Optional.ofNullable(environment.getProperty("spring.application.name")) :
                Optional.empty();
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
            return Optional.ofNullable(environment.getProperty("app.version"));
        } else {
            return Optional.empty();
        }
    }

    @Around("@annotation(dev.autometrics.bindings.Autometrics)")
    public Object methodCallDuration(ProceedingJoinPoint joinPoint) {
        String function = joinPoint.getSignature().getName();
        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String module = joinPoint.getSignature().getDeclaringType().getPackageName();
        String fullFunctionName = module + "." + className + "." + function;

        var concurrencyGauge = findGaugeForFunction(fullFunctionName, module);
        var timer = Timer.builder("function.calls.duration")
                .sla()
                .tag("function", fullFunctionName)
                .tag("module", module)
                .publishPercentileHistogram()
                .publishPercentiles()
                .register(registry);
        concurrencyGauges.get(concurrencyGauge).incrementAndGet();
        return timer.record(() -> {
            try {
                Object proceed = joinPoint.proceed();
                registry.counter( "function.calls","function", fullFunctionName, "module", module, "result", "ok").increment();
                return proceed;
            } catch (Throwable throwable) {
                registry.counter("function.calls", "function", fullFunctionName, "module", module, "result", "error").increment();
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