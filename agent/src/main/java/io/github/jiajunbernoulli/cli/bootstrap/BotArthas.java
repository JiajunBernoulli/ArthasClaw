/*
 * Copyright © 2026 Jiajun Bernoulli (jiajunbernoulli@users.noreply.github.com)
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
package io.github.jiajunbernoulli.cli.bootstrap;

import io.github.jiajunbernoulli.mcp.McpClient;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.TimeUnit;

/**
 * Bootstrap class for attaching Arthas to a target JVM process.
 * Handles Arthas installation, configuration, and MCP connection.
 */
public class BotArthas {

    private static final String ARTHAS_VERSION = "4.1.8";
    private static final String ARTHAS_MCP_ENDPOINT = "/mcp";
    private static final int ARTHAS_PORT = 8563;

    private final String pid;
    private String mcpPassword;
    private McpClient mcpClient;

    public BotArthas(String pid) {
        this.pid = pid;
    }

    /**
     * Attach Arthas to the target process and establish MCP connection.
     *
     * @return McpClient connected to Arthas
     * @throws Exception if attachment or connection fails
     */
    public McpClient attach() throws Exception {
        System.out.println("[*] Attaching Arthas to PID: " + pid);

        // Generate MCP password
        mcpPassword = java.util.UUID.randomUUID().toString().replace("-", "");

        // Write Arthas configuration
        writeArthasConfig();

        // Attach Arthas
        attachArthas();

        // Connect MCP
        connectMcp();

        return mcpClient;
    }

    /**
     * Write Arthas properties file to enable MCP Server.
     */
    private void writeArthasConfig() throws Exception {
        String userHome = System.getProperty("user.home");
        File confDir = new File(userHome, ".arthas/conf");
        if (!confDir.exists()) {
            confDir.mkdirs();
        }
        File propertiesFile = new File(confDir, "arthas.properties");
        try (FileWriter writer = new FileWriter(propertiesFile)) {
            writer.write("# MCP (Model Context Protocol) configuration\n");
            writer.write("arthas.mcpEndpoint=" + ARTHAS_MCP_ENDPOINT + "\n");
            writer.write("arthas.password=" + mcpPassword + "\n");
        }
    }

    /**
     * Attach Arthas to the target JVM process.
     */
    private void attachArthas() throws Exception {
        String userHome = System.getProperty("user.home");
        String arthasHome = userHome + "/.arthas/lib/" + ARTHAS_VERSION + "/arthas";
        File arthasCore = new File(arthasHome, "arthas-core.jar");

        if (!arthasCore.exists()) {
            downloadAndAttach(pid);
        } else {
            attachWithExistingInstallation(arthasHome, pid);
        }
    }

    /**
     * Download Arthas and attach to target process.
     */
    private void downloadAndAttach(String pid) throws Exception {
        System.out.println("[*] Arthas core not found. Downloading arthas-boot.jar...");
        ProcessBuilder pb = new ProcessBuilder("curl", "-sL", "-O", "https://arthas.aliyun.com/arthas-boot.jar");
        pb.inheritIO().start().waitFor();

        System.out.println("[*] Running arthas-boot to download full Arthas package...");
        ProcessBuilder pb2 = new ProcessBuilder("java", "-jar", "arthas-boot.jar", 
                "--use-version", ARTHAS_VERSION, "--attach-only", pid);
        pb2.inheritIO().start().waitFor();
        System.out.println("[+] Arthas attached successfully via boot jar.");
    }

    /**
     * Attach using existing Arthas installation.
     */
    private void attachWithExistingInstallation(String arthasHome, String pid) throws Exception {
        System.out.println("[*] Attaching with existing Arthas installation...");
        File arthasBoot = new File(arthasHome, "arthas-boot.jar");

        if (arthasBoot.exists()) {
            ProcessBuilder pb = new ProcessBuilder("java", "-jar", 
                    arthasBoot.getAbsolutePath(), "--attach-only", pid);
            pb.inheritIO().start().waitFor();
            System.out.println("[+] Arthas attached successfully via boot jar.");
        } else {
            System.out.println("[!] arthas-boot.jar not found in " + arthasHome + ", downloading...");
            downloadAndAttach(pid);
        }
    }

    /**
     * Connect to Arthas MCP Server.
     */
    private void connectMcp() throws Exception {
        System.out.println("[*] Connecting to Arthas MCP Server...");
        mcpClient = new McpClient("http://localhost:" + ARTHAS_PORT + ARTHAS_MCP_ENDPOINT, mcpPassword);

        mcpClient.connect().get(5, TimeUnit.SECONDS);
        System.out.println("[+] Connected to SSE endpoint.");

        mcpClient.initialize().get(5, TimeUnit.SECONDS);
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
