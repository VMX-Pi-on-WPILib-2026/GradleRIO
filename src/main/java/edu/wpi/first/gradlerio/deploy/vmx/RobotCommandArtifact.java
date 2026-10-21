package edu.wpi.first.gradlerio.deploy.vmx;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import javax.inject.Inject;

import edu.wpi.first.deployutils.PathUtils;
import edu.wpi.first.deployutils.deploy.artifact.CommandArtifact;
import edu.wpi.first.deployutils.deploy.context.DeployContext;
import edu.wpi.first.gradlerio.deploy.DeployStage;
import edu.wpi.first.gradlerio.deploy.StagedDeployTarget;

/**
 * Writes the robotCommand (and optional robotCommand.args) that robot_manager
 * runs, as root. Unlike the roboRIO version, no chown to a robot user is done.
 */
public class RobotCommandArtifact extends CommandArtifact {

    public static final String ROBOT_COMMAND_FILE = "robotCommand";
    public static final String ARG_FILE = "robotCommand.args";

    private Function<DeployContext, String> robotCommandFunc;
    private Function<DeployContext, String> argFileFunc;

    @Inject
    public RobotCommandArtifact(String name, StagedDeployTarget target) {
        super(name, target);

        target.setDeployStage(this, DeployStage.FileDeploy);
    }

    public Function<DeployContext, String> getRobotCommandFunc() {
        return robotCommandFunc;
    }

    public void setRobotCommandFunc(Function<DeployContext, String> robotCommandFunc) {
        this.robotCommandFunc = robotCommandFunc;
    }

    public Function<DeployContext, String> getArgFileFunc() {
        return argFileFunc;
    }

    public void setArgFileFunc(Function<DeployContext, String> argFileFunc) {
        this.argFileFunc = argFileFunc;
    }

    /** Single-quotes a string for sh. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    public void deploy(DeployContext ctx) {
        String robotCommandContent = robotCommandFunc.apply(ctx);

        var robotCommandPath = PathUtils.combine(ctx.getWorkingDir(), ROBOT_COMMAND_FILE);
        var argsPath = PathUtils.combine(ctx.getWorkingDir(), ARG_FILE);

        Map<String, String> files = new LinkedHashMap<>();
        files.put(robotCommandPath, robotCommandContent);

        if (argFileFunc != null) {
            files.put(argsPath, argFileFunc.apply(ctx));
        }

        // The 2026 deploy context has no putStringFiles; write each file with echo like the roboRIO version.
        for (Map.Entry<String, String> file : files.entrySet()) {
            ctx.execute("echo " + shellQuote(file.getValue()) + " > " + shellQuote(file.getKey()));
        }

        ctx.execute("chmod +x " + robotCommandPath);
        if (argFileFunc != null) {
            ctx.execute("chmod +x " + argsPath);
        }
    }
}
