# Studica VMX-pi deploy (WPILib 2026)

This fork adds a `VmxPi` deploy target next to `RoboRIO`, for **C++ and Java** robot
programs on a Studica VMX-pi with a 64-bit OS. The target platform is `linuxarm64`.

The VMX-pi support itself is a HAL: the VMX allwpilib fork builds a `linuxarm64`
HAL that drives the VMX-pi (`hal/src/main/native/mau`, see its `vmx/README.md`).
This GradleRIO only has to use that HAL and deploy to the robot.

The deploy target is a port of the 2027 W4Studica `VmxPi` target (same names,
same robot-side `robot_manager` service), minus the halsim extension it needs there.

## Changes to upstream

- `deploy/vmx/` (new): the `VmxPi` target and its artifacts, `VmxHalSupport`.
- One-line hooks: `FRCDeployPlugin` (register the target), `WPINativeExtension`
  (`withCrossLinuxArm64()`, VMX HAL version), `WPIPlugin` (VMX HAL Maven repository),
  `WPIJavaDepsExtension` (VMX HAL version for `hal-java` / `hal-cpp` JNI),
  `WPIVersionsExtension` (`vmxHalVersion`).
- WPILib 2026.2.2 (upstream GradleRIO stopped at 2026.2.1 on this line).

## The HAL: `wpi.versions.vmxHalVersion`

`edu.wpi.first.hal` (C++ `hal_shared`/`hal_static`, Java `hal-java` and the JNI
`hal-cpp`) resolves to `wpi.versions.vmxHalVersion` (default `2026.2.2-vmx.1`)
from the VMX HAL Maven repository, by default `~/wpilib/2026/vmx/maven`
(`-PvmxHalMaven=/path` or `VMX_HAL_MAVEN` to change). Its `linuxarm64` binaries are
the VMX-pi HAL; all other platforms are the upstream HAL, so desktop simulation and
roboRIO builds are unchanged. Build it with `vmx/build_vmx_hal_maven.py` in the VMX
allwpilib fork. The plugin itself is published to the same folder:

```bash
./gradlew publishToMavenLocal -PpublishVersion=2026.2.2-vmx.1 -Dmaven.repo.local=$HOME/wpilib/2026/vmx/maven
```

`libvmxpi_hal_cpp.so` (Studica's) is never downloaded or redistributed: C++ links with
`--allow-shlib-undefined` for linuxarm64 and the robot provides the library.

## Projects

Start from `examples/vmx-cpp` or `examples/vmx-java`: a normal WPILib project with a
`VmxPi` target and `linuxarm64` instead of the roboRIO platform. Once per PC:
`./gradlew installArm64Toolchain`.

```groovy
deploy {
    targets {
        vmx(getTargetTypeClass('VmxPi')) {
            username = 'ubuntu'           // also sets the directory to /home/ubuntu
            password = 'password'
            team = project.frc.getTeamNumber()
            artifacts {
                frcCpp(getArtifactTypeClass('WPILibNativeArtifact')) { }   // or WPILibJavaArtifact
            }
        }
    }
}
```

Java: fill `vmxDebug` / `vmxRelease` with `wpi.java.deps.wpilibJniDebug/Release(wpi.platforms.linuxarm64)`
(instead of `roborioDebug` / `roborioRelease`).

`team` adds the wired address `172.22.11.2` and the robot Wi-Fi address `10.TE.AM.2`.

## Deploying

`./gradlew deploy` stops the `robot_manager` service, uploads the program and its
libraries to `/home/<user>/wpilib/third-party/lib` (Java: the jar to `/home/<user>`),
writes `/home/<user>/robotCommand`, runs `sudo ldconfig` on the library directory and
starts the service again.

| Artifact | |
|---|---|
| `WPILibNativeArtifact` | C++ program; `environment` adds variables, `halsimExtensions` is empty |
| `WPILibJavaArtifact` | jar run with the robot's `/usr/bin/java` (`javaCommand`), G1 GC, JNI from the library directory |
| `RobotCommandArtifact`, `RobotProgramKillArtifact`, `RobotProgramStartArtifact` | `robotCommand`; `systemctl stop` / `enable` + `start` |

`fetchVmxSdk<target>` copies `VMXPi.h` and `libvmxpi_hal_cpp.so` from the robot into
`build/vmxsdk<target>/`, for C++ code that uses the VMX library directly (`sdkPath(...)`).

## What the robot needs

- 64-bit Ubuntu 24.04 / 26.04 (glibc 2.36 or newer).
- Studica's VMX HAL: the `vmxpi-hal` package (`/usr/local/lib/vmxpi`).
- The `robot_manager` service from W4Studica Robot-Manager, which runs `robotCommand`
  as root, restarts it, stops it with SIGINT (pigpio clean-up) and serves RioLog on
  port 1741. The halsim parts are not needed:
  `./install.sh --user ubuntu --sudoers --skip-drivers --skip-plugin`.
  Its GPIO shim is needed on kernels without `/sys/class/gpio` (VMX interrupts).
- `/home/lvuser`: on a real robot WPILib reads the deploy directory, `networktables.json`
  and logs from there. Link it to the deploy user's home once:
  `sudo ln -s /home/ubuntu /home/lvuser` (deploy warns when it is missing).
- Java programs: `sudo apt install openjdk-17-jre-headless`.
