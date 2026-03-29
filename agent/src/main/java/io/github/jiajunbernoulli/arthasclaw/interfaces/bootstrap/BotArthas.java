/*
 * Copyright © 2026 Jiajun Bernoulli
 * (jiajunbernoulli@users.noreply.github.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.jiajunbernoulli.arthasclaw.interfaces.bootstrap;

import io.github.jiajunbernoulli.arthasclaw.infrastructure.config.Config;
import io.github.jiajunbernoulli.arthasclaw.infrastructure.mcp.McpClient;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Bootstrap class for attaching Arthas to a target JVM process.
 * Handles Arthas installation, configuration, and MCP connection.
 */
public class BotArthas {

    /** Arthas boot jar download URL. */
    private static final String ARTHAS_BOOT_URL =
            "https://arthas.aliyun.com/arthas-boot.jar";

    /** Arthas config directory name. */
    private static final String ARTHAS_CONF_DIR = ".arthas/conf";

    /** Arthas lib directory pattern. */
    private static final String ARTHAS_LIB_PATTERN = ".arthas/lib/%s/arthas";

    /** Target JVM process ID. */
    private final String pid;

    /** MCP configuration. */
    private final Config.McpConfig mcpConfig;

    /** MCP password for authentication. */
    private String mcpPassword;

    /** MCP client instance. */
    private McpClient mcpClient;

    /**
     * Create BotArthas with configuration.
     *
     * @param targetPid target JVM process ID
     * @param config    ArthasClaw configuration
     */
    public BotArthas(final String targetPid, final Config config) {
        this.pid = targetPid;
        this.mcpConfig = config.getMcp();
    }

    /**
     * Legacy constructor for backward compatibility.
     *
     * @param targetPid target JVM process ID
     */
    public BotArthas(final String targetPid) {
        this(targetPid, new Config());
    }

    /**
     * Attach Arthas to the target process and establish MCP connection.
     *
     * @return McpClient connected to Arthas
     * @throws Exception if attachment or connection fails
     */
    public McpClient attach() throws Exception {
        System.out.println("[*] Attaching Arthas to PID: " + pid);

        mcpPassword = java.util.UUID.randomUUID()
                .toString()
                .replace("-", "");

        writeArthasConfig();

        attachArthas();

        connectMcp();

        return mcpClient;
    }

    /**
     * Write Arthas properties file to enable MCP Server.
     *
     * @throws IOException if writing fails
     */
    private void writeArthasConfig() throws IOException {
        String userHome = System.getProperty("user.home");
        File confDir = new File(userHome, ARTHAS_CONF_DIR);
        if (!confDir.exists()) {
            confDir.mkdirs();
        }
        File propertiesFile = new File(confDir, "arthas.properties");
        try (FileWriter writer = new FileWriter(propertiesFile)) {
            writer.write(
                    "# MCP (Model Context Protocol) configuration\n");
            writer.write("arthas.mcpEndpoint="
                    + mcpConfig.getEndpoint() + "\n");
            writer.write("arthas.password=" + mcpPassword + "\n");
        }
    }

    /**
     * Attach Arthas to the target JVM process.
     *
     * @throws Exception if attachment fails
     */
    private void attachArthas() throws Exception {
        String arthasVersion = mcpConfig.getArthasVersion();
        String userHome = System.getProperty("user.home");
        String arthasHome = String.format(
                userHome + "/" + ARTHAS_LIB_PATTERN, arthasVersion);
        File arthasCore = new File(arthasHome, "arthas-core.jar");

        if (!arthasCore.exists()) {
            downloadAndAttach(arthasVersion);
        } else {
            attachWithExistingInstallation(arthasHome, arthasVersion);
        }
    }

    /**
     * Download Arthas and attach to target process.
     *
     * @param arthasVersion the Arthas version to download
     * @throws Exception if download or attachment fails
     */
    private void downloadAndAttach(final String arthasVersion)
            throws Exception {
        System.out.println(
                "[*] Arthas core not found. Downloading arthas-boot.jar...");
        ProcessBuilder pb = new ProcessBuilder(
                "curl", "-sL", "-O", ARTHAS_BOOT_URL);
        pb.inheritIO().start().waitFor();

        System.out.println(
                "[*] Running arthas-boot to download full Arthas package...");
        ProcessBuilder pb2 = new ProcessBuilder(
                "java", "-jar", "arthas-boot.jar",
                "--use-version", arthasVersion, "--attach-only", pid);
        pb2.inheritIO().start().waitFor();
        System.out.println(
                "[+] Arthas attached successfully via boot jar.");
    }

    /**
     * Attach using existing Arthas installation.
     *
     * @param arthasHome    the Arthas home directory
     * @param arthasVersion the Arthas version
     * @throws Exception if attachment fails
     */
    private void attachWithExistingInstallation(
            final String arthasHome,
            final String arthasVersion) throws Exception {
        System.out.println(
                "[*] Attaching with existing Arthas installation...");
        File arthasBoot = new File(arthasHome, "arthas-boot.jar");

        if (arthasBoot.exists()) {
            ProcessBuilder pb = new ProcessBuilder(
                    "java", "-jar",
                    arthasBoot.getAbsolutePath(), "--attach-only", pid);
            pb.inheritIO().start().waitFor();
            System.out.println(
                    "[+] Arthas attached successfully via boot jar.");
        } else {
            System.out.println(
                    "[!] arthas-boot.jar not found in " + arthasHome
                    + ", downloading...");
            downloadAndAttach(arthasVersion);
        }
    }

    /**
     * Connect to Arthas MCP Server.
     *
     * @throws Exception if connection fails
     */
    private void connectMcp() throws Exception {
        System.out.println(
                "[*] Connecting to Arthas MCP Server on port "
                + mcpConfig.getPort() + "...");
        mcpClient = new McpClient(mcpConfig.getBaseUrl(), mcpPassword);

        mcpClient.connect().get(
                mcpConfig.getConnectTimeoutSeconds(), TimeUnit.SECONDS);
        System.out.println("[+] Connected to SSE endpoint.");

        mcpClient.initialize().get(
                mcpConfig.getInitializeTimeoutSeconds(), TimeUnit.SECONDS);
        System.out.println("[+] Initialized MCP session.");
    }

    /**
     * Get the MCP client.
     *
     * @return McpClient instance
     */
    public McpClient getMcpClient() {
        return mcpClient;
    }

    /**
     * Get the MCP password.
     *
     * @return MCP password string
     */
    public String getMcpPassword() {
        return mcpPassword;
    }
}
