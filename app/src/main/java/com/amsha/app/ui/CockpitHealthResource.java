package com.amsha.app.ui;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.net.URI;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.amsha.app.ui.dto.CockpitHealthResponse;
import com.amsha.app.ui.dto.DbConnectionPoolSummary;
import com.amsha.app.ui.dto.HealthCheckDetail;
import com.amsha.app.ui.dto.ProviderHealthSummary;
import com.amsha.app.ui.dto.SmallRyeHealthSummary;
import com.amsha.core.api.dto.AmshaErrorResponse;
import com.amsha.llm.api.AgentChatModelProvider;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;

import io.agroal.api.AgroalDataSource;
import io.smallrye.common.annotation.RunOnVirtualThread;

@ApplicationScoped
@Path("/admin/cockpit/health")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class CockpitHealthResource {

    @Inject
    @Any
    Instance<HealthCheck> healthChecks;

    @Inject
    Instance<DataSource> dataSourceInstance;

    @ConfigProperty(name = "amsha.persistence.backend", defaultValue = "sqlite")
    String runtimeBackend;

    @Inject
    @Any
    Instance<AgentChatModelProvider> providers;

    @ConfigProperty(name = "amsha.llm.provider", defaultValue = "groq")
    String defaultProviderName;

    @Context
    SecurityContext securityContext;

    record CachedProviders(long timestampMs, List<ProviderHealthSummary> summaries) {}

    record CachedDatabase(long timestampMs, DbConnectionPoolSummary summary) {}

    static final long PROVIDER_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(30);
    static final long DB_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(15);
    final AtomicReference<CachedProviders> providerCache = new AtomicReference<>();
    final AtomicReference<CachedDatabase> dbCache = new AtomicReference<>();

    @GET
    public Response getHealth() {
        if (!isInternal()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new AmshaErrorResponse("Internal operator role required for Cockpit Health"))
                    .build();
        }

        SmallRyeHealthSummary healthSummary = buildHealthSummary();
        DbConnectionPoolSummary dbSummary = buildDatabaseSummary();
        List<ProviderHealthSummary> providerSummary = buildProvidersSummary();

        String overallStatus = "UP";
        if ("DOWN".equalsIgnoreCase(healthSummary.status())
                || "DOWN".equalsIgnoreCase(dbSummary.status())
                || "NOT_CONFIGURED".equalsIgnoreCase(dbSummary.status())) {
            overallStatus = "DOWN";
        } else if ("DEGRADED".equalsIgnoreCase(healthSummary.status())
                || healthSummary.totalChecks() > healthSummary.passingChecks()
                || isDefaultProviderDegraded(providerSummary)) {
            overallStatus = "DEGRADED";
        }

        CockpitHealthResponse response = new CockpitHealthResponse(
                overallStatus, healthSummary, dbSummary, providerSummary, System.currentTimeMillis());
        return Response.ok(response).build();
    }

    private SmallRyeHealthSummary buildHealthSummary() {
        List<HealthCheckDetail> details = new ArrayList<>();
        int passing = 0;
        int total = 0;

        if (healthChecks != null) {
            for (HealthCheck hc : healthChecks) {
                total++;
                try {
                    HealthCheckResponse resp = hc.call();
                    boolean isUp = resp.getStatus() == HealthCheckResponse.Status.UP;
                    if (isUp) {
                        passing++;
                    }
                    Map<String, Object> data = sanitizeHealthData(resp.getData().orElse(Map.of()));
                    details.add(new HealthCheckDetail(resp.getName(), isUp ? "UP" : "DOWN", data));
                } catch (Exception e) {
                    String name = hc.getClass().getSimpleName();
                    if (name.isBlank()) {
                        name = "HealthCheck";
                    }
                    details.add(new HealthCheckDetail(name, "DOWN", Map.of("errorCategory", categorizeHealthError(e))));
                }
            }
        }

        String status = "UP";
        if (total > 0 && passing == 0) {
            status = "DOWN";
        } else if (passing < total) {
            status = "DEGRADED";
        }

        details.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return new SmallRyeHealthSummary(status, total, passing, details);
    }

    DbConnectionPoolSummary buildDatabaseSummary() {
        long now = System.currentTimeMillis();
        CachedDatabase cached = dbCache.get();
        if (cached != null && (now - cached.timestampMs()) < DB_CACHE_TTL_MS) {
            return cached.summary();
        }

        DbConnectionPoolSummary summary = probeDatabase();
        dbCache.set(new CachedDatabase(now, summary));
        return summary;
    }

    private DbConnectionPoolSummary probeDatabase() {
        String status = "UP";
        boolean poolEnabled = false;
        Integer active = null;
        Integer available = null;
        Integer maxPoolSize = null;
        Integer awaiting = null;

        if (dataSourceInstance != null && dataSourceInstance.isResolvable()) {
            DataSource ds = dataSourceInstance.get();
            try (Connection conn = ds.getConnection()) {
                boolean valid = conn.isValid(2);
                status = valid ? "UP" : "DOWN";
            } catch (Exception _) {
                status = "DOWN";
            }
            if (ds instanceof AgroalDataSource agroal) {
                poolEnabled = true;
                try {
                    var metrics = agroal.getMetrics();
                    active = (int) metrics.activeCount();
                    available = (int) metrics.availableCount();
                    maxPoolSize = agroal.getConfiguration()
                            .connectionPoolConfiguration()
                            .maxSize();
                    awaiting = (int) metrics.awaitingCount();
                } catch (Exception _) {
                    // Metrics might not be enabled or supported on this connection
                }
            }
        } else if ("filesystem".equalsIgnoreCase(runtimeBackend) || "fs".equalsIgnoreCase(runtimeBackend)) {
            status = "UP";
            poolEnabled = false;
        } else {
            status = "NOT_CONFIGURED";
        }

        return new DbConnectionPoolSummary(
                runtimeBackend, status, poolEnabled, active, available, maxPoolSize, awaiting);
    }

    List<ProviderHealthSummary> buildProvidersSummary() {
        long now = System.currentTimeMillis();
        CachedProviders cached = providerCache.get();
        if (cached != null && (now - cached.timestampMs()) < PROVIDER_CACHE_TTL_MS) {
            return cached.summaries();
        }

        List<ProviderHealthSummary> list = probeProviders();
        providerCache.set(new CachedProviders(now, list));
        return list;
    }

    private List<ProviderHealthSummary> probeProviders() {
        List<ProviderHealthSummary> list = new ArrayList<>();
        if (providers != null) {
            for (AgentChatModelProvider p : providers) {
                String name = p.providerName();
                boolean isDef = isDefaultProvider(name, defaultProviderName);
                String baseUrl = sanitizeBaseUrl(p.baseUrl());
                String status;
                String details;
                try {
                    var model = p.create();
                    if (model != null) {
                        status = "CONFIGURED";
                        details = "Ready (model instance created)";
                    } else {
                        status = "NOT_CONFIGURED";
                        details = "Model returned null";
                    }
                } catch (Exception e) {
                    status = "NOT_CONFIGURED";
                    details = categorizeProviderError(e);
                }
                list.add(new ProviderHealthSummary(name, isDef, status, baseUrl, details));
            }
        }

        list.sort((a, b) -> {
            if (a.isDefault() != b.isDefault()) {
                return a.isDefault() ? -1 : 1;
            }
            return a.providerName().compareToIgnoreCase(b.providerName());
        });
        return list;
    }

    static boolean isDefaultProvider(String providerName, String defaultName) {
        if (providerName == null || defaultName == null) {
            return false;
        }
        String p1 = providerName.trim().toLowerCase(Locale.ROOT);
        String p2 = defaultName.trim().toLowerCase(Locale.ROOT);
        if (p1.equals(p2)) {
            return true;
        }
        return isClaudeCodeAlias(p1) && isClaudeCodeAlias(p2);
    }

    private static boolean isClaudeCodeAlias(String name) {
        return "claude-code".equals(name)
                || "anthropic".equals(name)
                || "claudecode".equals(name)
                || "claude".equals(name);
    }

    private static boolean isDefaultProviderDegraded(List<ProviderHealthSummary> providers) {
        if (providers == null || providers.isEmpty()) {
            return false;
        }
        return providers.stream()
                .filter(ProviderHealthSummary::isDefault)
                .findFirst()
                .map(p -> !"CONFIGURED".equalsIgnoreCase(p.status()))
                .orElse(true);
    }

    private static final Set<String> ALLOWED_HEALTH_DATA_KEYS = Set.of(
            "status", "version", "rootless", "passing", "total", "compatible", "backend", "checkType", "errorCategory");

    static Map<String, Object> sanitizeHealthData(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (ALLOWED_HEALTH_DATA_KEYS.contains(entry.getKey())) {
                Object val = entry.getValue();
                if (val instanceof Number || val instanceof Boolean) {
                    safe.put(entry.getKey(), val);
                } else if (val != null) {
                    String s = String.valueOf(val);
                    if (s.length() > 64) {
                        s = s.substring(0, 64);
                    }
                    safe.put(entry.getKey(), s);
                }
            }
        }
        return Map.copyOf(safe);
    }

    static String categorizeHealthError(Throwable t) {
        if (t == null) {
            return "CHECK_FAILED";
        }
        if (t instanceof TimeoutException
                || (t.getMessage() != null
                        && t.getMessage().toLowerCase(Locale.ROOT).contains("timeout"))) {
            return "TIMEOUT";
        }
        if (t instanceof SecurityException
                || (t.getMessage() != null
                        && (t.getMessage().toLowerCase(Locale.ROOT).contains("security")
                                || t.getMessage().toLowerCase(Locale.ROOT).contains("unauthorized")
                                || t.getMessage().toLowerCase(Locale.ROOT).contains("forbidden")))) {
            return "SECURITY_ERROR";
        }
        return "CHECK_FAILED";
    }

    static String sanitizeBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String host = uri.getHost();
            if (host == null) {
                uri = URI.create("http://" + rawUrl.trim());
                host = uri.getHost();
            }
            if (host == null) {
                return "redacted";
            }
            int port = uri.getPort();
            if (port > 0 && port != 80 && port != 443) {
                return host + ":" + port;
            }
            return host;
        } catch (Exception _) {
            return "redacted";
        }
    }

    static String categorizeProviderError(Throwable t) {
        if (t == null) {
            return "INITIALIZATION_FAILED";
        }
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase(Locale.ROOT) : "";
        String type = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (msg.contains("credential")
                || msg.contains("key")
                || msg.contains("token")
                || msg.contains("auth")
                || msg.contains("unauthorized")
                || type.contains("auth")
                || type.contains("credential")) {
            return "MISSING_CREDENTIALS";
        }
        if (msg.contains("unsupported") || type.contains("unsupported")) {
            return "UNSUPPORTED_PROVIDER";
        }
        if (t instanceof IllegalArgumentException
                || t instanceof IllegalStateException
                || msg.contains("config")
                || type.contains("config")) {
            return "CONFIGURATION_ERROR";
        }
        return "INITIALIZATION_FAILED";
    }

    private boolean isInternal() {
        return securityContext != null && securityContext.isUserInRole("internal");
    }
}
