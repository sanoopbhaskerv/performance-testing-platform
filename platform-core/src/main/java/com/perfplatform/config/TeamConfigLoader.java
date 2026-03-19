package com.perfplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Loads and hot-reloads team configuration YAML files from the configured
 * directory.
 * All files matching *.yml in the config dir are treated as team configs.
 */
@Component
public class TeamConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(TeamConfigLoader.class);

    @Value("${platform.config-dir:config/teams}")
    private String configDir;

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final Map<String, TeamConfig> teamConfigs = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * Hot-reload all team configs without restarting the platform.
     * Called on startup and via POST /api/v1/config/reload.
     */
    public int reload() {
        teamConfigs.clear();
        Path dir = Paths.get(configDir);

        if (!Files.exists(dir)) {
            log.warn("Team config directory does not exist: {}. Creating it.", dir.toAbsolutePath());
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                log.error("Failed to create config directory", e);
            }
            return 0;
        }

        int loaded = 0;
        try (Stream<Path> paths = Files.walk(dir, 1)) {
            List<Path> yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                    .toList();

            for (Path yamlFile : yamlFiles) {
                try {
                    TeamConfig config = yamlMapper.readValue(yamlFile.toFile(), TeamConfig.class);
                    if (config.getTeam() == null || config.getTeam().isBlank()) {
                        log.warn("Skipping config file {} — missing 'team' field", yamlFile.getFileName());
                        continue;
                    }
                    validate(config, yamlFile.getFileName().toString());
                    teamConfigs.put(config.getTeam(), config);
                    log.info("Loaded team config: {} from {}", config.getTeam(), yamlFile.getFileName());
                    loaded++;
                } catch (IOException e) {
                    log.error("Failed to parse team config file: {}", yamlFile.getFileName(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to scan config directory: {}", configDir, e);
        }

        log.info("Team configs loaded: {}", loaded);
        return loaded;
    }

    public Optional<TeamConfig> getConfig(String team) {
        return Optional.ofNullable(teamConfigs.get(team));
    }

    public List<TeamConfig> getAllConfigs() {
        return new ArrayList<>(teamConfigs.values());
    }

    public List<String> getTeamNames() {
        return new ArrayList<>(teamConfigs.keySet());
    }

    /**
     * Validate a team config and warn about common misconfigurations.
     */
    private void validate(TeamConfig config, String filename) {
        if (config.getTargets() == null || config.getTargets().isEmpty()) {
            log.warn("[{}] No targets defined — tests will have nothing to hit", filename);
        } else {
            for (TeamConfig.TargetConfig target : config.getTargets()) {
                if (target.getBaseUrl() == null || target.getBaseUrl().isBlank()) {
                    log.warn("[{}] Target '{}' has no baseUrl", filename, target.getName());
                }
                if (target.isJvmEnabled()) {
                    if (target.getJvmHost() == null || target.getJvmHost().isBlank()) {
                        log.warn("[{}] Target '{}' has jvmEnabled=true but no jvmHost set", filename, target.getName());
                    }
                }
            }
        }

        // Warn if heapDump enabled but no JVM target configured
        if (config.getAnalysis().getHeapDump().isEnabled()) {
            boolean hasJvmTarget = config.getTargets().stream().anyMatch(TeamConfig.TargetConfig::isJvmEnabled);
            if (!hasJvmTarget) {
                log.warn(
                        "[{}] heapDump.enabled=true but no target has jvmEnabled=true — dump collection will be skipped",
                        filename);
            }
        }
    }
}
