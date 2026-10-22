plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api("com.google.code.gson:gson:2.11.0")
    api("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r") { isTransitive = false }
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.17")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("user.language", "zh")
    systemProperty("user.country", "CN")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
