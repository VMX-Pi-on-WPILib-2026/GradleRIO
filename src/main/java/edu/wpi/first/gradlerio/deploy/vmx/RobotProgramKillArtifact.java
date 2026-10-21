package edu.wpi.first.gradlerio.deploy.vmx;

import javax.inject.Inject;

import edu.wpi.first.deployutils.deploy.artifact.AbstractArtifact;
import edu.wpi.first.deployutils.deploy.context.DeployContext;
import edu.wpi.first.gradlerio.deploy.DeployStage;

public class RobotProgramKillArtifact extends AbstractArtifact {

    private final VmxPi vmx;

    @Inject
    public RobotProgramKillArtifact(String name, VmxPi target) {
        super(name, target);
        vmx = target;

        target.setDeployStage(this, DeployStage.ProgramKill);
    }

    @Override
    public void deploy(DeployContext ctx) {
        ctx.execute("sudo systemctl stop " + vmx.getServiceName() + " 2> /dev/null");
    }
}
