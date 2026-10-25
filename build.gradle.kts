plugins {
    java
}

group = "ru.unknownsmp"
version = "2.17.3"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Библиотеки, вкладываемые прямо в JAR (хостингу не нужен доступ в интернет).
val bundle: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Фильтр консоли (тихие команды). На сервере log4j-core уже есть в ядре Paper.
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
    // SQLite JDBC уже входит в состав сервера Paper.
    compileOnly("org.xerial:sqlite-jdbc:3.47.1.0")
    compileOnly("com.zaxxer:HikariCP:6.2.1")
    compileOnly("org.postgresql:postgresql:42.7.4")
    bundle("com.zaxxer:HikariCP:6.2.1")
    bundle("org.postgresql:postgresql:42.7.4")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("UnknownSMP-${project.version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(bundle.map { zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "META-INF/versions/**/module-info.class", "module-info.class")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}
