package edu.wpi.first.gradlerio.deploy.vmx;

import java.io.File;

import javax.inject.Inject;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask;
import org.gradle.nativeplatform.tasks.AbstractLinkTask;
import edu.wpi.first.deployutils.deploy.DeployExtension;
import edu.wpi.first.deployutils.deploy.target.location.SshDeployLocation;
import edu.wpi.first.gradlerio.deploy.FRCExtension;
import edu.wpi.first.gradlerio.deploy.WPIRemoteTarget;
import edu.wpi.first.toolchain.NativePlatforms;

/**
 * Studica VMX-pi (Raspberry Pi, Linux aarch64).
 *
 * The target platform is {@link NativePlatforms#linuxarm64}; the VMX HAL Maven
 * repository replaces the linuxarm64 HAL with the VMX-pi HAL (see VmxHalSupport).
 *
 * The VMX-pi image uses the fixed {@code ubuntu}/{@code password} login.
 */
public class VmxPi extends WPIRemoteTarget {

    /** Name of the systemd service (robot_manager) that starts the robot program. */
    public static final String DEFAULT_SERVICE_NAME = "robot_manager";

    /** WPILib RioLog's NetConsole-compatible TCP port, served by robot_manager. */
    public static final int RIOLOG_PORT = 1741;

    /** The VMX's wired port, always this address (it also hands out addresses to a computer plugged in directly). */
    public static final String WIRED_ADDRESS = "172.22.11.2";

    /**
     * The VMX's address on its own Wi-Fi network for a team number: 10.TE.AM.2 (1234 gives 10.12.34.2, 4444 gives
     * 10.44.44.2). The same rule as the robot's setupHotspot.
     */
    public static String teamAddress(int team) {
        if (team < 1 || team > 9999) {
            throw new GradleException("The team number must be 1-9999, got " + team);
        }
        return "10." + (team / 100) + "." + (team % 100) + ".2";
    }

    private String username = "ubuntu";
    private String password = "password";
    private String serviceName = DEFAULT_SERVICE_NAME;
    private Integer team;

    private final RobotProgramKillArtifact programKillArtifact;
    private final RobotProgramStartArtifact programStartArtifact;

    @Inject
    public VmxPi(String name, Project project, DeployExtension de, FRCExtension frcExtension) {
        super(name, project, de, frcExtension);

        setMaxChannels(4);
        // A Wi-Fi link can stall for ten seconds (TCP retransmits); a short timeout would fail the whole deploy.
        setTimeout(30);

        programKillArtifact = project.getObjects().newInstance(RobotProgramKillArtifact.class, "programKill" + name, this);
        programStartArtifact = project.getObjects().newInstance(RobotProgramStartArtifact.class, "programStart" + name, this);

        getTargetPlatform().set(NativePlatforms.linuxarm64);
        setDirectory("/home/ubuntu");

        getArtifacts().add(programKillArtifact);
        getArtifacts().add(programStartArtifact);

        // The VMX SDK (VMXPi.h, libvmxpi_hal_cpp.so) is only needed by C++ code that uses the
        // VMX HAL library directly; run fetchVmxSdk<name> for that. A normal build must stay
        // local and must not contact the robot just because a VMX target is configured.
        TaskProvider<FetchVmxSdkTask> fetchSdk = project.getTasks().register(
                "fetchVmxSdk" + name, FetchVmxSdkTask.class, this);
        fetchSdk.configure(task -> {
            task.getSdkDirectory().set(project.getLayout().getBuildDirectory().dir("vmxsdk" + name));
            task.getRemotePaths().convention(DEFAULT_SDK_PATHS);
            task.getPort().convention(22);
            task.getAttempts().convention(3);
            task.getRetryDelaySeconds().convention(3);
            task.onlyIf(ignored -> project.findProperty("refetchVmxSdk") != null  // ./gradlew -PrefetchVmxSdk deploy
                    || !sdkPath("/usr/local/include/vmxpi").exists()
                    || !sdkPath("/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so").exists());
        });
        // When both tasks are in the deploy graph, fetch the SDK before compiling/linking.
        // mustRunAfter does not add the fetch task to a normal build's task graph.
        project.getTasks().withType(AbstractNativeCompileTask.class).configureEach(task -> {
            if (task.getTargetPlatform().isPresent()
                    && NativePlatforms.linuxarm64.equals(task.getTargetPlatform().get().getName())) {
                task.mustRunAfter(fetchSdk);
            }
        });
        project.getTasks().withType(AbstractLinkTask.class).configureEach(task -> {
            if (task.getTargetPlatform().isPresent()
                    && NativePlatforms.linuxarm64.equals(task.getTargetPlatform().get().getName())) {
                task.mustRunAfter(fetchSdk);
                // libwpiHal.so needs libvmxpi_hal_cpp.so, which only the robot has (it is not
                // redistributed). Leave its symbols to the robot's dynamic linker.
                task.getLinkerArgs().add("-Wl,--allow-shlib-undefined");
            }
        });
    }

    /**
     * What only the robot has: the VMX headers and the VMX HAL library, both from the vmxpi-hal package.
     */
    public static final java.util.List<String> DEFAULT_SDK_PATHS = java.util.List.of(
            "/usr/local/include/vmxpi",
            "/usr/local/lib/vmxpi/libvmxpi_hal_cpp.so");

    /**
     * Location of a robot file after {@code fetchVmxSdk<name>}: {@code sdkPath("/usr/local/include/vmxpi")}
     * is the directory to pass as an include path.
     */
    public File sdkPath(String remotePath) {
        return new File(getProject().getLayout().getBuildDirectory().dir("vmxsdk" + getName()).get().getAsFile(),
                remotePath.startsWith("/") ? remotePath.substring(1) : remotePath);
    }

    public RobotProgramKillArtifact getProgramKillArtifact() {
        return programKillArtifact;
    }

    public RobotProgramStartArtifact getProgramStartArtifact() {
        return programStartArtifact;
    }

    public String getUsername() {
        return username;
    }

    /** Also sets the deploy directory to /home/&lt;username&gt;. Override with setDirectory afterwards. */
    public void setUsername(String username) {
        this.username = username;
        setDirectory("/home/" + username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /** Directory the robot command, classpath and libraries are deployed under. */
    public String getHomeDirectory() {
        if (username == null) {
            throw new GradleException("VmxPi target '" + getName() + "': username is not set");
        }
        return getDirectory();
    }

    public String getClasspathDirectory() {
        return getHomeDirectory() + "/wpilib/classpath";
    }

    public String getLibraryDirectory() {
        return getHomeDirectory() + "/wpilib/third-party/lib";
    }

    public Integer getTeam() {
        return team;
    }

    /**
     * Sets the team number and adds the two addresses the VMX has: the wired port (172.22.11.2) and its Wi-Fi access
     * point (10.TE.AM.2, 1234 gives 10.12.34.2). The deploy uses whichever one answers, so it works the same whether the
     * computer is on the cable or on the robot's Wi-Fi.
     */
    public void setTeam(int team) {
        String wifi = teamAddress(team);  // validates the number before anything is added
        this.team = team;
        addAddressOnce(WIRED_ADDRESS);
        addAddressOnce(wifi);
    }

    private void addAddressOnce(String address) {
        if (getLocations().findByName(address) == null) {
            addAddress(address);
        }
    }

    /**
     * Adds an SSH address (host name or IP).
     */
    public void addAddress(String address) {
        if (username == null || password == null) {
            throw new GradleException("VmxPi target '" + getName()
                    + "': set username and password before addAddress(\"" + address + "\")");
        }
        getLocations().create(address, SshDeployLocation.class, loc -> {
            loc.setAddress(address);
            loc.setIpv6(false);
            loc.setUser(username);
            loc.setPassword(password);
        });
    }

    @Override
    public String toString() {
        return "VmxPi[" + getName() + "]";
    }
}
