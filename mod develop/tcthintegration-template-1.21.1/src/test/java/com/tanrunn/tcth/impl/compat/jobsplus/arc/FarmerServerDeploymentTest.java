package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 测试服（{@code ../../Server/}）部署态检查。与纯源码测试分开：这些断言依赖
 * 本机测试服目录，纯源码 CI 不读取 {@code ../../Server/}；目录不存在时用
 * {@code assumeTrue} 跳过，**跳过的运行环境检查不计作普适单元测试保证**。
 */
class FarmerServerDeploymentTest {

    private static final Path SERVER = Path.of("../../Server");

    @Test
    void serverConfigDisablesDefaultJobs() throws Exception {
        Path config = SERVER.resolve("config/jobsplus-common.yaml");
        assumeTrue(Files.exists(config), "test server config not present here; skipping");
        String yaml = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(yaml.contains("enable_default_jobs: false"),
                "Jobs+ default jobs must be disabled via enable_default_jobs: false");
        assertFalse(yaml.contains("enable_default_jobs: true"),
                "enable_default_jobs must not remain true");
    }

    @Test
    void chefDatapackStillDeployedAndEnabled() {
        Path chef = SERVER.resolve("world/datapacks/tcth-chef");
        assumeTrue(Files.isDirectory(chef), "test server chef datapack not deployed here; skipping");
        assertTrue(Files.exists(chef.resolve("pack.mcmeta")), "tcth-chef must remain a valid datapack");
        assertTrue(Files.exists(chef.resolve("data/tcth/jobsplus/jobs/chef.json")),
                "tcth-chef job definition must remain deployed");
    }

    @Test
    void farmerDatapackDeployedWithJobDefinition() {
        Path farmer = SERVER.resolve("world/datapacks/tcth-farmer");
        assumeTrue(Files.isDirectory(farmer), "test server farmer datapack not deployed here; skipping");
        assertTrue(Files.exists(farmer.resolve("pack.mcmeta")));
        assertTrue(Files.exists(farmer.resolve("data/tcth/jobsplus/jobs/farmer.json")));
        assertTrue(Files.exists(farmer.resolve("data/tcth/arc/farmer/crop_harvested.json")),
                "tcth-farmer must ship the unified crop_harvested action");
        assertFalse(Files.exists(farmer.resolve("data/tcth/arc/farmer/harvest_crop.json")),
                "tcth-farmer must no longer ship the arc:on_harvest_crop action");
    }
}
