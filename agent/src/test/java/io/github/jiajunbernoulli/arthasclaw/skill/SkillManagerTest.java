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
package io.github.jiajunbernoulli.arthasclaw.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillManagerTest {

    @TempDir
    Path tempDir;

    private SkillManager skillManager;
    private Path skillsDir;

    @BeforeEach
    void setUp() throws IOException {
        skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        skillManager = new SkillManager(skillsDir.toString());
    }

    @Test
    void testInstallFromLocalFile() throws IOException {
        // Create a test skill file
        String skillContent = "---\n" +
                "name: test-skill\n" +
                "description: A test skill\n" +
                "version: 1.0.0\n" +
                "author: test\n" +
                "tools:\n" +
                "  - thread\n" +
                "  - dashboard\n" +
                "---\n" +
                "This is a test prompt for the skill.";
        
        Path skillFile = tempDir.resolve("test-skill.md");
        Files.writeString(skillFile, skillContent);

        // Install the skill
        Skill skill = skillManager.install(skillFile.toString());

        assertNotNull(skill);
        assertEquals("test-skill", skill.getName());
        assertEquals("A test skill", skill.getDescription());
        assertEquals("1.0.0", skill.getVersion());
        assertEquals("test", skill.getAuthor());
        assertTrue(skill.hasTools());
        assertEquals(2, skill.getTools().size());
        assertTrue(skill.hasPrompt());
        assertTrue(skill.isEnabled());
    }

    @Test
    void testInstallPurePromptFile() throws IOException {
        // Create a simple prompt file without YAML front matter
        String skillContent = "This is a simple prompt without metadata.";
        
        Path skillFile = tempDir.resolve("simple-skill.md");
        Files.writeString(skillFile, skillContent);

        Skill skill = skillManager.install(skillFile.toString());

        assertNotNull(skill);
        assertEquals("simple-skill", skill.getName());
        assertTrue(skill.hasPrompt());
        assertEquals("This is a simple prompt without metadata.", skill.getPrompt());
    }

    @Test
    void testListSkills() throws IOException {
        // Install two skills
        String skill1 = "---\nname: skill-one\ndescription: First skill\n---\nPrompt 1";
        String skill2 = "---\nname: skill-two\ndescription: Second skill\n---\nPrompt 2";

        Files.writeString(tempDir.resolve("skill1.md"), skill1);
        Files.writeString(tempDir.resolve("skill2.md"), skill2);

        skillManager.install(tempDir.resolve("skill1.md").toString());
        skillManager.install(tempDir.resolve("skill2.md").toString());

        List<Skill> skills = skillManager.listAll();
        assertEquals(2, skills.size());
    }

    @Test
    void testEnableDisableSkill() throws IOException {
        String skillContent = "---\nname: toggle-skill\ndescription: Toggle test\n---\nPrompt";
        Path skillFile = tempDir.resolve("toggle.md");
        Files.writeString(skillFile, skillContent);

        skillManager.install(skillFile.toString());

        // Disable
        assertTrue(skillManager.disable("toggle-skill"));
        Optional<Skill> disabled = skillManager.get("toggle-skill");
        assertTrue(disabled.isPresent());
        assertFalse(disabled.get().isEnabled());

        // Enable
        assertTrue(skillManager.enable("toggle-skill"));
        Optional<Skill> enabled = skillManager.get("toggle-skill");
        assertTrue(enabled.isPresent());
        assertTrue(enabled.get().isEnabled());
    }

    @Test
    void testRemoveSkill() throws IOException {
        String skillContent = "---\nname: removable-skill\ndescription: To be removed\n---\nPrompt";
        Path skillFile = tempDir.resolve("removable.md");
        Files.writeString(skillFile, skillContent);

        skillManager.install(skillFile.toString());
        assertTrue(skillManager.get("removable-skill").isPresent());

        assertTrue(skillManager.remove("removable-skill"));
        assertFalse(skillManager.get("removable-skill").isPresent());
    }

    @Test
    void testGetCombinedPrompt() throws IOException {
        String skill1 = "---\nname: prompt-one\n---\nFirst prompt content.";
        String skill2 = "---\nname: prompt-two\n---\nSecond prompt content.";

        Files.writeString(tempDir.resolve("p1.md"), skill1);
        Files.writeString(tempDir.resolve("p2.md"), skill2);

        skillManager.install(tempDir.resolve("p1.md").toString());
        skillManager.install(tempDir.resolve("p2.md").toString());

        String combined = skillManager.getCombinedPrompt();
        assertTrue(combined.contains("prompt-one"));
        assertTrue(combined.contains("prompt-two"));
        assertTrue(combined.contains("First prompt content."));
        assertTrue(combined.contains("Second prompt content."));
    }

    @Test
    void testGetCombinedPromptOnlyEnabled() throws IOException {
        String skill1 = "---\nname: enabled-skill\n---\nEnabled prompt.";
        String skill2 = "---\nname: disabled-skill\n---\nDisabled prompt.";

        Files.writeString(tempDir.resolve("e1.md"), skill1);
        Files.writeString(tempDir.resolve("e2.md"), skill2);

        skillManager.install(tempDir.resolve("e1.md").toString());
        skillManager.install(tempDir.resolve("e2.md").toString());

        // Disable one skill
        skillManager.disable("disabled-skill");

        String combined = skillManager.getCombinedPrompt();
        assertTrue(combined.contains("enabled-skill"));
        assertTrue(combined.contains("Enabled prompt."));
        assertFalse(combined.contains("disabled-skill"));
        assertFalse(combined.contains("Disabled prompt."));
    }

    @Test
    void testGetEnabledTools() throws IOException {
        String skill1 = "---\nname: tool-skill\ntools:\n  - thread\n  - dashboard\n---\nPrompt";
        Files.writeString(tempDir.resolve("tool.md"), skill1);
        skillManager.install(tempDir.resolve("tool.md").toString());

        List<String> tools = skillManager.getEnabledTools();
        assertEquals(2, tools.size());
        assertTrue(tools.contains("thread"));
        assertTrue(tools.contains("dashboard"));
    }

    @Test
    void testInstallDeadlockAnalyzerSkill() throws IOException {
        // Test with realistic skill content
        String skillContent = "---\n" +
                "name: deadlock-analyzer\n" +
                "description: Detect and analyze thread deadlocks in Java applications\n" +
                "version: 1.0.0\n" +
                "author: jiajunbernoulli\n" +
                "tools:\n" +
                "  - thread\n" +
                "  - thread -b\n" +
                "  - thread -n 5\n" +
                "  - stack\n" +
                "---\n" +
                "You are a Java thread deadlock analysis expert.\n\n" +
                "## Deadlock Detection Workflow\n\n" +
                "1. Use `thread -b` to find blocking threads\n" +
                "2. Use `thread` for overall thread state\n" +
                "3. Use `stack` for detailed analysis";

        Path skillFile = tempDir.resolve("deadlock-analyzer.md");
        Files.writeString(skillFile, skillContent);

        Skill skill = skillManager.install(skillFile.toString());

        assertEquals("deadlock-analyzer", skill.getName());
        assertEquals("Detect and analyze thread deadlocks in Java applications", skill.getDescription());
        assertEquals("1.0.0", skill.getVersion());
        assertEquals(4, skill.getTools().size());
        assertTrue(skill.getPrompt().contains("deadlock analysis expert"));
    }
}