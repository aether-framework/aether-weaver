/*
 * The IntelliJ plugin is a standalone Gradle build. It is deliberately NOT part of the Maven
 * reactor: building it downloads an IntelliJ Platform distribution, and `mvn install` must never
 * depend on that.
 */
rootProject.name = "aether-weaver-idea"
