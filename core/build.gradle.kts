plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.register<JavaExec>("checkEngine") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("tf.dodoapps.parkbot.core.EngineTest")
}
tasks.check { dependsOn("checkEngine") }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
