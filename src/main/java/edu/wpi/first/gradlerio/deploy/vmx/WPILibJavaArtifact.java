package edu.wpi.first.gradlerio.deploy.vmx;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import edu.wpi.first.deployutils.PathUtils;
import edu.wpi.first.deployutils.deploy.context.DeployContext;
import edu.wpi.first.gradlerio.deploy.DebuggableJavaArtifact;
import edu.wpi.first.gradlerio.deploy.DeployStage;
import edu.wpi.first.gradlerio.deploy.roborio.GarbageCollectorType;
import edu.wpi.first.gradlerio.wpi.WPIExtension;

/**
 * Deploys a Java robot program (jar) to a {@link VmxPi}. Same shape as the
 * roboRIO FRCJavaArtifact, but it runs on the robot's system Java (no JRE is
 * deployed), loads JNI libraries from the VMX library directory, and needs no
 * chown because robot_manager runs the program as root.
 *
 * The JNI libraries come from the vmxDebug / vmxRelease configurations, which
 * a build.gradle fills with wpi.java.deps.wpilibJniDebug/Release(wpi.platforms.linuxarm64).
 */
public class WPILibJavaArtifact extends DebuggableJavaArtifact {

    private final RobotCommandArtifact robotCommandArtifact;
    private final WPILibJNILibraryArtifact nativeZipArtifact;

    private final List<String> jvmArgs = new ArrayList<>();
    private final List<String> arguments = new ArrayList<>();

    private final VmxPi vmx;

    // A Pi 4 has four cores and enough memory for G1 (the 2020 VMX-pi port also used G1).
    private GarbageCollectorType gcType = GarbageCollectorType.G1_LongPause;

    private String javaCommand = "/usr/bin/java";

    @Inject
    public WPILibJavaArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        jvmArgs.add("-Djava.lang.invoke.stringConcat=BC_SB");

        var debugConfiguration = target.getProject().getConfigurations().maybeCreate("vmxDebug");
        var releaseConfiguration = target.getProject().getConfigurations().maybeCreate("vmxRelease");

        robotCommandArtifact = target.getArtifacts().create("robotCommand" + name, RobotCommandArtifact.class, art -> {
            art.setRobotCommandFunc(this::generateStartCommand);
            art.dependsOn(getJarProvider());
        });

        nativeZipArtifact = target.getArtifacts().create("nativeZips" + name, WPILibJNILibraryArtifact.class, artifact -> {
            target.setDeployStage(artifact, DeployStage.FileDeploy);

            var cbl = target.getProject().getProviders().provider(() -> {
                boolean debug = target.getProject().getExtensions().getByType(WPIExtension.class).getJava().getDebugJni().get();
                if (debug) {
                    return debugConfiguration;
                } else {
                    return releaseConfiguration;
                }
            });

            artifact.getConfiguration().set(cbl);
            artifact.setZipped(true);
            artifact.getFilter().include("**/*.so*");
            artifact.getFilter().include("**/*.so");
            artifact.getFilter().getExcludes().add("**/*.so.debug");
            artifact.getFilter().getExcludes().add("**/*.so.*.debug");
        });

        target.getProgramStartArtifact().getPostdeploy().add(this::postStart);

        target.setDeployStage(this, DeployStage.FileDeploy);
    }

    public String getJavaCommand() {
        return javaCommand;
    }

    public void setJavaCommand(String javaCommand) {
        this.javaCommand = javaCommand;
    }

    public GarbageCollectorType getGcType() {
        return gcType;
    }

    public void setGcType(GarbageCollectorType gcType) {
        this.gcType = gcType;
    }

    private String getBinFile(DeployContext ctx) {
        return PathUtils.combine(ctx.getWorkingDir(), getFilename().getOrElse(getFile().get().getName()));
    }

    @Override
    public void setJarTask(Jar jarTask) {
        robotCommandArtifact.getDeployTask().configure(x -> x.dependsOn(jarTask));
        super.setJarTask(jarTask);
    }

    @Override
    public void setJarTask(TaskProvider<Jar> jarTask) {
        robotCommandArtifact.getDeployTask().configure(x -> x.dependsOn(jarTask));
        super.setJarTask(jarTask);
    }

    public RobotCommandArtifact getRobotCommandArtifact() {
        return robotCommandArtifact;
    }

    public WPILibJNILibraryArtifact getNativeZipArtifact() {
        return nativeZipArtifact;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public List<String> getArguments() {
        return arguments;
    }

    String generateStartCommand(DeployContext ctx) {
        StringBuilder builder = new StringBuilder();
        builder.append("LD_LIBRARY_PATH=\"");
        builder.append(vmx.getLibraryDirectory());
        builder.append("\" ");
        builder.append(javaCommand);
        builder.append(" ");
        builder.append(String.join(" ", gcType.getGcArguments()));
        builder.append(" -Djava.library.path=");
        builder.append(vmx.getLibraryDirectory());
        builder.append(" ");
        builder.append(String.join(" ", jvmArgs));
        builder.append(" ");

        // Debug stuff
        boolean debug = vmx.getDebug().get();
        if (debug) {
            builder.append("-XX:+UsePerfData -agentlib:jdwp=transport=dt_socket,address=0.0.0.0:");
            builder.append(getDebugPort());
            builder.append(",server=y,suspend=y ");
        }

        String binFile = getBinFile(ctx);

        builder.append("-jar \"");
        builder.append(binFile);
        builder.append("\" ");
        builder.append(String.join(" ", arguments));

        return builder.toString();
    }

    private void postStart(DeployContext ctx) {
        boolean debug = vmx.getDebug().get();
        if (debug) {
            ctx.getLogger().withLock(x -> {
                x.log("====================================================================");
                x.log("DEBUGGING ACTIVE ON PORT " + getDebugPort() + "!");
                x.log("====================================================================");
            });
        }
    }

}
