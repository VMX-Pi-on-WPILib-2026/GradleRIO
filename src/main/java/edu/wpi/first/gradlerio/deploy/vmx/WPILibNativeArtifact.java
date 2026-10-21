package edu.wpi.first.gradlerio.deploy.vmx;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.util.PatternFilterable;
import org.gradle.nativeplatform.NativeExecutableBinarySpec;
import org.gradle.nativeplatform.NativeExecutableSpec;
import edu.wpi.first.deployutils.PathUtils;
import edu.wpi.first.deployutils.deploy.context.DeployContext;
import edu.wpi.first.gradlerio.deploy.DebuggableNativeArtifact;
import edu.wpi.first.gradlerio.deploy.DeployStage;
import edu.wpi.first.gradlerio.deploy.TargetDebugInfo;

/**
 * Deploys a C++ robot program to a {@link VmxPi}. Same shape as the 2027 W4Studica
 * VmxPi artifact, but the linuxarm64 HAL here is the VMX-pi HAL itself
 * (hal/src/main/native/mau in the VMX allwpilib fork), so no HALSIM extension is
 * loaded by default.
 */
public class WPILibNativeArtifact extends DebuggableNativeArtifact {

    private final RobotCommandArtifact robotCommandArtifact;
    private final List<String> arguments = new ArrayList<>();
    /** Extension libraries, relative to the library directory unless absolute (none by default). */
    private final List<String> halsimExtensions = new ArrayList<>();
    /** Extra environment variables for the robot program (for example HALSIMVMX_DIO_MAP). */
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final VmxPi vmx;

    private final Property<NativeExecutableSpec> componentSpec;

    public Property<NativeExecutableSpec> getComponent() {
        return componentSpec;
    }

    @Inject
    public WPILibNativeArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        componentSpec = target.getProject().getObjects().property(NativeExecutableSpec.class);

        getBinary().set(componentSpec.map(x -> {
            for (NativeExecutableBinarySpec bin : x.getBinaries().withType(NativeExecutableBinarySpec.class)) {
                if (bin.getTargetPlatform().getName().equals(getTarget().getTargetPlatform().get()) &&
                    bin.getBuildType().getName().equals(target.getBuildType().get())) {
                    return bin;
                }
            }
            return null;
        }));

        PatternFilterable filterable = getLibraryFilter();
        filterable.getExcludes().add("**/*.so.debug");
        filterable.getExcludes().add("**/*.so.*.debug");

        // Lazy: the username (and so the directory) is only known once the target is configured.
        this.getLibraryDirectory().set(target.getProject().provider(vmx::getLibraryDirectory));

        getPostdeploy().add(ctx -> {
            ctx.execute("sudo ldconfig " + vmx.getLibraryDirectory());
        });

        robotCommandArtifact = target.getArtifacts().create("robotCommand" + name, RobotCommandArtifact.class, art -> {
            art.setRobotCommandFunc(this::generateStartCommand);
            art.dependsOn(getInstallTaskProvider());
        });

        getPostdeploy().add(ctx -> {
            String binFile = getBinFile(ctx);
            // robot_manager runs the program as root, so no chown or capabilities are needed.
            ctx.execute("chmod +x \"" + binFile + "\"");
        });

        target.setDeployStage(this, DeployStage.FileDeploy);
    }

    private String getBinFile(DeployContext ctx) {
        File exeFile = getDeployedFile();
        return PathUtils.combine(ctx.getWorkingDir(), getFilename().getOrElse(exeFile.getName()));
    }

    public RobotCommandArtifact getRobotCommandArtifact() {
        return robotCommandArtifact;
    }

    public List<String> getArguments() {
        return arguments;
    }

    public List<String> getHalsimExtensions() {
        return halsimExtensions;
    }

    public Map<String, String> getEnvironment() {
        return environment;
    }

    /**
     * The inherited implementation looks up the roboRIO toolchain (gdb and sysroot), which is
     * the wrong toolchain for a VMX-pi. No gdb debug flow is provided yet.
     */
    @Override
    public TargetDebugInfo getTargetDebugInfo() {
        return null;
    }

    /** HALSIM_EXTENSIONS value: absolute library paths separated by ':' (Linux). */
    String extensionString() {
        List<String> paths = new ArrayList<>();
        // Elements may be Groovy GStrings ("${dir}/x.so"), which are not Strings: go through String.valueOf.
        for (Object element : (List<?>) halsimExtensions) {
            String ext = String.valueOf(element);
            paths.add(ext.startsWith("/") ? ext : PathUtils.combine(vmx.getLibraryDirectory(), ext));
        }
        return String.join(":", paths);
    }

    /** NAME="value" pairs for the robot command, each followed by a space. Values may be Groovy GStrings. */
    String environmentString() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) environment).entrySet()) {
            builder.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\" ");
        }
        return builder.toString();
    }

    String generateStartCommand(DeployContext ctx) {
        StringBuilder builder = new StringBuilder();
        builder.append("LD_LIBRARY_PATH=\"");
        builder.append(vmx.getLibraryDirectory());
        builder.append("\" ");
        if (!halsimExtensions.isEmpty()) {
            builder.append("HALSIM_EXTENSIONS=\"").append(extensionString()).append("\" ");
        }
        builder.append(environmentString());
        if (vmx.getDebug().get()) {
            builder.append("gdbserver :");
            builder.append(getDebugPort());
            builder.append(' ');
        }
        builder.append('\"');
        builder.append(getBinFile(ctx));
        builder.append("\" ");
        builder.append(String.join(" ", arguments));

        return builder.toString();
    }
}
