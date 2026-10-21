package edu.wpi.first.gradlerio.deploy.vmx;

import java.io.File;

import org.gradle.api.Project;
import org.gradle.api.provider.Provider;

import edu.wpi.first.gradlerio.wpi.WPIExtension;
import edu.wpi.first.nativeutils.NativeUtilsExtension;
import edu.wpi.first.nativeutils.dependencies.WPIMavenDependency;

/**
 * Points the WPILib HAL dependency at the VMX HAL Maven repository.
 *
 * That repository (built by vmx/build_vmx_hal_maven.py in the VMX allwpilib fork)
 * holds edu.wpi.first.hal:hal-cpp/hal-java at {@code wpi.versions.vmxHalVersion}:
 * the linuxarm64 binaries are the VMX-pi HAL, every other platform is the
 * upstream WPILib HAL of the same release. So only VMX-pi (linuxarm64) programs
 * change; desktop simulation and roboRIO builds get the upstream HAL.
 *
 * Kept out of the upstream classes to ease merging; they only call in here.
 */
public final class VmxHalSupport {
    private VmxHalSupport() {
    }

    /** Default location of the VMX HAL Maven repository: ~/wpilib/YEAR/vmx/maven. */
    public static Provider<File> defaultRepository(WPIExtension wpi) {
        return wpi.getFrcHome().map(home -> home.dir("vmx").dir("maven").getAsFile());
    }

    /**
     * Adds the VMX HAL Maven repository. The location can be changed with
     * -PvmxHalMaven=/path or the VMX_HAL_MAVEN environment variable.
     */
    public static void addMavenRepository(Project project, WPIExtension wpi) {
        Object override = project.findProperty("vmxHalMaven");
        String env = System.getenv("VMX_HAL_MAVEN");
        project.getRepositories().maven(repo -> {
            repo.setName("VMXHal");
            if (override != null) {
                repo.setUrl(project.file(override.toString()));
            } else if (env != null && !env.isEmpty()) {
                repo.setUrl(project.file(env));
            } else {
                repo.setUrl(defaultRepository(wpi));
            }
            repo.content(content -> content.includeGroup("edu.wpi.first.hal"));
        });
    }

    /** Uses the VMX HAL version for the native-utils HAL dependencies (C++). */
    public static void configureNativeHal(NativeUtilsExtension nte, Provider<String> vmxHalVersion) {
        for (String name : new String[] {"hal_shared", "hal_static"}) {
            nte.getNativeDependencyContainer().named(name, WPIMavenDependency.class,
                    dep -> dep.getVersion().set(vmxHalVersion));
        }
    }
}
