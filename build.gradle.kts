import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import com.github.gradle.node.npm.task.NpmTask

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.7.2"
    id("com.github.node-gradle.node") version "7.1.0"
}

group = "com.codexgui"
version = "2.1.4"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("javazoom:jlayer:1.0.1")
    implementation(project(":shared-core"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r") { isTransitive = false }

    intellijPlatform {
        intellijIdeaCommunity("2024.3.7")
        bundledModule("intellij.platform.vcs.impl")
        bundledPlugin("PerforceDirectPlugin")
        pluginVerifier()
        zipSigner()
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

node {
    version.set("22.14.0")
    download.set(true)
}

sourceSets {
    main {
        resources.srcDir(layout.buildDirectory.dir("generated-resources"))
    }
}

val frontendTypecheck by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("typecheck"))
    inputs.files(fileTree("src/main/ts"), fileTree("src/test"), fileTree("shared-core/src/main/resources/i18n"), "tsconfig.json", "playwright.config.ts", "vitest.config.ts")
}

val frontendTest by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("test"))
    inputs.files(fileTree("src/main/ts"), fileTree("src/test/ts"), fileTree("shared-core/src/main/resources/i18n"), "vitest.config.ts", "package.json", "package-lock.json")
}

val frontendBundle by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall)
    npmCommand.set(listOf("run"))
    args.set(listOf("build"))
    inputs.files(fileTree("src/main/ts"), fileTree("shared-core/src/main/resources/i18n"), "package.json", "package-lock.json")
    outputs.file(layout.buildDirectory.file("generated-resources/web/app.js"))
}

val frontendBrowserTest by tasks.registering(NpmTask::class) {
    dependsOn(tasks.npmInstall, frontendBundle)
    npmCommand.set(listOf("run"))
    args.set(listOf("test:browser:run"))
    inputs.files(
        fileTree("src/main/ts"),
        fileTree("shared-core/src/main/resources/i18n"),
        fileTree("src/test/browser"),
        "tools/ui-preview.html",
        "tools/serve-preview.mjs",
        "playwright.config.ts",
        "package.json",
        "package-lock.json"
    )
}

intellijPlatform {
    pluginConfiguration {
        id = "com.codexgui.jetbrains"
        name = "CodeDeck"
        version = project.version.toString()
        description = """
            <p>CodeDeck is a native Codex and Claude Code CLI interface for JetBrains IDEs.</p>
            <p>Review change lists and native Git/Perforce diffs with AI annotations. English and Simplified Chinese interfaces follow the IDE language.</p>
            <p>面向 JetBrains IDE 的 Codex / Claude Code 图形界面。</p>
            <p>内置 ChangeList 变更树、Git/Perforce 原生 Diff、文件操作与 AI 注释，统一安装和配置。</p>
            <p>连接 Codex app-server 与 Claude Code CLI，不注入第三方隐藏提示词。</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }

        vendor {
            name = "CodeDeck"
        }
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7")
        }
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISH_TOKEN")
    }
}

tasks {
    processResources {
        dependsOn(frontendBundle)
    }

    check {
        dependsOn(
            frontendTypecheck,
            frontendTest,
            frontendBrowserTest,
            frontendBundle,
            ":shared-core:check"
        )
    }

    test {
        useJUnitPlatform()
        // Existing transcript fixtures are Chinese; localization tests exercise other locales explicitly.
        systemProperty("user.language", "zh")
        systemProperty("user.country", "CN")
        classpath = classpath.filter { it.name != "testFramework.jar" }
        // An explicitly requested real-model acceptance run must never reuse cached test results.
        outputs.upToDateWhen { System.getenv("CODEDECK_REAL_CAPTURE_CODEX").isNullOrBlank() }
        outputs.cacheIf { System.getenv("CODEDECK_REAL_CAPTURE_CODEX").isNullOrBlank() }
    }

    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
