/*
 * Copyright 2019 IceRock MAG Inc. Use of this source code is governed by the Apache 2.0 license.
 */

package dev.icerock.gradle

import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.LibraryPlugin
import com.android.build.gradle.api.AndroidSourceSet
import dev.icerock.gradle.generator.AndroidMRGenerator
import dev.icerock.gradle.generator.CommonMRGenerator
import dev.icerock.gradle.generator.IosMRGenerator
import dev.icerock.gradle.generator.MRGenerator
import dev.icerock.gradle.generator.ResourceGeneratorFeature
import dev.icerock.gradle.generator.SourceInfo
import dev.icerock.gradle.generator.fonts.FontsGeneratorFeature
import dev.icerock.gradle.generator.image.ImagesGeneratorFeature
import dev.icerock.gradle.generator.plurals.PluralsGeneratorFeature
import dev.icerock.gradle.generator.strings.StringsGeneratorFeature
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmAndroidCompilation
import org.jetbrains.kotlin.konan.target.Family
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MultiplatformResourcesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val mrExtension =
            target.extensions.create<MultiplatformResourcesPluginExtension>("multiplatformResources")

        target.plugins.withType<KotlinMultiplatformPluginWrapper> {
            val multiplatformExtension = target.extensions.getByType(this.projectExtensionClass)

            target.plugins.withType<LibraryPlugin> {
                val androidExtension = target.extensions.getByName("android") as LibraryExtension

                target.afterEvaluate {
                    configureGenerators(
                        target = target,
                        mrExtension = mrExtension,
                        multiplatformExtension = multiplatformExtension,
                        androidExtension = androidExtension
                    )
                }
            }
        }
    }

    private fun configureGenerators(
        target: Project,
        mrExtension: MultiplatformResourcesPluginExtension,
        multiplatformExtension: KotlinMultiplatformExtension,
        androidExtension: LibraryExtension
    ) {
        val androidMainSourceSet = androidExtension.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

        val commonSourceSet = multiplatformExtension.sourceSets.getByName(mrExtension.sourceSetName)
        val commonResources = commonSourceSet.resources

        val manifestFile = androidMainSourceSet.manifest.srcFile
        val androidPackage = getAndroidPackage(manifestFile)

        val generatedDir = File(target.buildDir, "generated/moko")
        val mrClassPackage: String = requireNotNull(mrExtension.multiplatformResourcesPackage)
        val sourceInfo = SourceInfo(
            generatedDir,
            commonResources,
            mrExtension.multiplatformResourcesPackage!!,
            androidPackage
        )
        val features = listOf(
            StringsGeneratorFeature(sourceInfo, mrExtension.iosBaseLocalizationRegion),
            PluralsGeneratorFeature(sourceInfo, mrExtension.iosBaseLocalizationRegion),
            ImagesGeneratorFeature(sourceInfo),
            FontsGeneratorFeature(sourceInfo)
        )
        val targets: List<KotlinTarget> = multiplatformExtension.targets.toList()

        setupCommonGenerator(commonSourceSet, generatedDir, mrClassPackage, features, target)
        setupAndroidGenerator(targets, androidMainSourceSet, generatedDir, mrClassPackage, features, target)
        setupIosGenerator(targets, generatedDir, mrClassPackage, features, target)
    }

    private fun setupCommonGenerator(
        commonSourceSet: KotlinSourceSet,
        generatedDir: File,
        mrClassPackage: String,
        features: List<ResourceGeneratorFeature>,
        target: Project
    ) {
        val commonGeneratorSourceSet: MRGenerator.SourceSet = createSourceSet(commonSourceSet)
        CommonMRGenerator(
            generatedDir,
            commonGeneratorSourceSet,
            mrClassPackage,
            generators = features.map { it.createCommonGenerator() }
        ).apply(target)
    }

    @Suppress("LongParameterList")
    private fun setupAndroidGenerator(
        targets: List<KotlinTarget>,
        androidMainSourceSet: AndroidSourceSet,
        generatedDir: File,
        mrClassPackage: String,
        features: List<ResourceGeneratorFeature>,
        target: Project
    ) {
        val kotlinSourceSets: List<KotlinSourceSet> = targets
            .filterIsInstance<KotlinAndroidTarget>()
            .flatMap { it.compilations }
            .filterNot { it.name.endsWith("Test") } // remove tests compilations
            .map { it.defaultSourceSet }

        val androidSourceSet: MRGenerator.SourceSet = createSourceSet(androidMainSourceSet, kotlinSourceSets)
        AndroidMRGenerator(
            generatedDir,
            androidSourceSet,
            mrClassPackage,
            generators = features.map { it.createAndroidGenerator() }
        ).apply(target)
    }

    private fun setupIosGenerator(
        targets: List<KotlinTarget>,
        generatedDir: File,
        mrClassPackage: String,
        features: List<ResourceGeneratorFeature>,
        target: Project
    ) {
        targets
            .filterIsInstance<KotlinNativeTarget>()
            .filter { it.konanTarget.family == Family.IOS }
            .map { kotlinNativeTarget ->
                kotlinNativeTarget.compilations
                    .getByName(KotlinCompilation.MAIN_COMPILATION_NAME)
            }
            .forEach { compilation ->
                val sourceSet = createSourceSet(compilation.defaultSourceSet)
                IosMRGenerator(
                    generatedDir,
                    sourceSet,
                    mrClassPackage,
                    generators = features.map { it.createIosGenerator() },
                    compilation = compilation
                ).apply(target)
            }
    }

    private fun createSourceSet(kotlinSourceSet: KotlinSourceSet): MRGenerator.SourceSet {
        return object : MRGenerator.SourceSet {
            override val name: String
                get() = kotlinSourceSet.name

            override fun addSourceDir(directory: File) {
                kotlinSourceSet.kotlin.srcDir(directory)
            }

            override fun addResourcesDir(directory: File) {
                kotlinSourceSet.resources.srcDir(directory)
            }
        }
    }

    private fun createSourceSet(
        androidSourceSet: AndroidSourceSet,
        kotlinSourceSets: List<KotlinSourceSet>
    ): MRGenerator.SourceSet {
        return object : MRGenerator.SourceSet {
            override val name: String
                get() = "android${androidSourceSet.name.capitalize()}"

            override fun addSourceDir(directory: File) {
                kotlinSourceSets.forEach { it.kotlin.srcDir(directory) }
            }

            override fun addResourcesDir(directory: File) {
                androidSourceSet.res.srcDir(directory)
            }
        }
    }

    private fun getAndroidPackage(manifestFile: File): String {
        val dbFactory = DocumentBuilderFactory.newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(manifestFile)

        val manifestNodes = doc.getElementsByTagName("manifest")
        val manifest = manifestNodes.item(0)

        return manifest.attributes.getNamedItem("package").textContent
    }
}
