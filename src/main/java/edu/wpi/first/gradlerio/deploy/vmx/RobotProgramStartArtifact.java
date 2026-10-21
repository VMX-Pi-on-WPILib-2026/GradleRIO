package edu.wpi.first.gradlerio.deploy.vmx;

import javax.inject.Inject;

import edu.wpi.first.deployutils.deploy.artifact.AbstractArtifact;
import edu.wpi.first.deployutils.deploy.context.DeployContext;
import edu.wpi.first.gradlerio.deploy.DeployStage;

public class RobotProgramStartArtifact extends AbstractArtifact {

    private final VmxPi vmx;

    @Inject
    public RobotProgramStartArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        target.setDeployStage(this, DeployStage.ProgramStart);
    }

    @Override
    public void deploy(DeployContext ctx) {
        // On a real robot WPILib uses /home/lvuser (deploy directory, NetworkTables and log files).
        String lvuser = ctx.execute("[ -d /home/lvuser ] && echo present || echo missing").getResult();
        if (lvuser != null && lvuser.contains("missing")) {
            ctx.getLogger().log("WARNING: /home/lvuser does not exist on the robot. WPILib uses it on a real robot "
                    + "(deploy directory, networktables.json, logs). Create it once: sudo ln -s " + vmx.getHomeDirectory() + " /home/lvuser");
        }
        String service = vmx.getServiceName();
        ctx.execute("sudo systemctl enable " + service + " 2> /dev/null");
        ctx.execute("sudo systemctl start " + service + " 2> /dev/null");
        // Deploy completes before VS Code opens its standard RioLog view. Wait for
        // the VMX NetConsole-compatible listener so that view can connect at once.
        String portHex = String.format("%04X", VmxPi.RIOLOG_PORT);
        ctx.execute("i=0; while [ \"$i\" -lt 100 ]; do "
                + "if grep -qi ':" + portHex + " .* 0A ' /proc/net/tcp; then exit 0; fi; "
                + "i=$((i+1)); sleep 0.1; done; "
                + "echo 'robot_manager RioLog listener did not open TCP port " + VmxPi.RIOLOG_PORT
                + " within 10 seconds; robot program is running but RioLog may be unavailable' >&2; exit 0");
    }
}
